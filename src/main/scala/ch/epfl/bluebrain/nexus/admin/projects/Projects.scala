package ch.epfl.bluebrain.nexus.admin.projects

import java.time.Clock
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.MonadError
import cats.effect.{Async, ConcurrentEffect}
import cats.implicits._

import ch.epfl.bluebrain.nexus.admin.config.AppConfig
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.admin.exceptions.UnexpectedState
import ch.epfl.bluebrain.nexus.admin.index.Index
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectState._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.types.identity.Identity
import ch.epfl.bluebrain.nexus.sourcing.akka._

/**
  * The projects operations bundle.
  *
  * @param agg   an aggregate instance for projects
  * @param index the project and organization labels index
  * @tparam F    the effect type
  */
class Projects[F[_]](agg: Agg[F], index: Index[F], organizations: Organizations[F])(
    implicit F: MonadError[F, Throwable],
    http: HttpConfig,
    clock: Clock) {

  /**
    * Creates a project.
    *
    * @param project the project
    * @param caller  an implicitly available caller
    * @return project the created project resource metadata if the operation was successful, a rejection otherwise
    */
  def create(project: Project)(implicit caller: Identity): F[ProjectMetaOrRejection] =
    index.getOrganization(project.organization).flatMap {
      case Some(org) =>
        index.getProject(project.organization, project.label).flatMap {
          case None =>
            val projectId = UUID.randomUUID
            val command   = CreateProject(projectId, org.uuid, project.label, project.description, clock.instant, caller)
            evaluateAndUpdateIndex(command, project)
          case Some(_) => F.pure(Left(ProjectAlreadyExists))
        }
      case None => F.pure(Left(OrganizationDoesNotExist))
    }

  /**
    * Updates a project.
    *
    * @param project the project
    * @param caller  an implicitly available caller
    * @return the updated project resource metadata if the operation was successful, a rejection otherwise
    */
  def update(project: Project)(implicit caller: Identity): F[ProjectMetaOrRejection] =
    index.getProject(project.organization, project.label).flatMap {
      case Some(resource) =>
        evaluateAndUpdateIndex(
          UpdateProject(resource.uuid, project.label, project.description, resource.rev, clock.instant, caller),
          project)
      case None => F.pure(Left(ProjectDoesNotExists))
    }

  /**
    * Deprecates a project.
    *
    * @param project the project
    * @param rev     the project revision
    * @param caller  an implicitly available caller
    * @return the deprecated project resource metadata if the operation was successful, a rejection otherwise
    */
  def deprecate(project: Project, rev: Long)(implicit caller: Identity): F[ProjectMetaOrRejection] =
    index.getProject(project.organization, project.label).flatMap {
      case Some(resource) =>
        evaluateAndUpdateIndex(DeprecateProject(resource.uuid, rev, clock.instant, caller), resource.value)
      case None => F.pure(Left(ProjectDoesNotExists))
    }

  /**
    * Fetches a project from the index.
    *
    * @param organization the organization label
    * @param label        the project label
    * @return Some(project) if found, None otherwise
    */
  def fetch(organization: String, label: String): F[Option[ResourceF[Project]]] =
    index.getProject(organization, label)

  /**
    * Fetches a project from the aggregate.
    *
    * @param uuid the project permanent identifier
    * @return Some(project) if found, None otherwise
    */
  def fetch(uuid: UUID): F[Option[ResourceF[Project]]] = agg.currentState(uuid.toString).flatMap {
    case c: Current => toResource(c).map(_.toOption)
    case Initial    => F.pure(None)
  }

  /**
    * Fetches a specific revision of a project from the aggregate.
    *
    * @param id the project permanent identifier
    * @param rev the project revision
    * @return the project if found, a rejection otherwise
    */
  def fetch(id: UUID, rev: Long): F[ProjectResourceOrRejection] = {
    agg
      .foldLeft[ProjectState](id.toString, Initial) {
        case (state: Current, _) if state.rev == rev => state
        case (state, event)                          => Projects.next(state, event)
      }
      .flatMap {
        case c: Current if c.rev == rev => toResource(c)
        case _: Current                 => F.pure(Left(IncorrectRev(rev)))
        case Initial                    => F.pure(Left(ProjectDoesNotExists))
      }
  }

  private def eval(command: ProjectCommand): F[ProjectMetaOrRejection] =
    agg
      .evaluateS(command.id.toString, command)
      .flatMap {
        case Right(c: Current) => toResourceMetaData(c)
        case Left(rejection)   => F.pure(Left(rejection))
        case Right(Initial)    => F.raiseError(UnexpectedState(command.id.toString))
      }

  private def evaluateAndUpdateIndex(command: ProjectCommand, project: Project): F[ProjectMetaOrRejection] =
    eval(command).flatMap {
      case Right(metadata) =>
        index.updateProject(metadata.map(_ => project)) *> F.pure(Right(metadata))
      case Left(rej) => F.pure(Left(rej))
    }

  private def toResource(c: Current): F[ProjectResourceOrRejection] = organizations.fetch(c.organization).map {
    case Some(org) =>
      val iri = http.projectsIri + org.value.label + c.label
      Right(
        ResourceF(iri,
                  c.id,
                  c.rev,
                  c.deprecated,
                  types,
                  c.instant,
                  c.subject,
                  c.instant,
                  c.subject,
                  Project(c.label, org.value.label, c.description)))
    case None =>
      Left(OrganizationDoesNotExist)
  }

  private def toResourceMetaData(c: Current): F[ProjectMetaOrRejection] = toResource(c).map(_.map(_.discard))

}

object Projects {

  /**
    * Constructs a [[ch.epfl.bluebrain.nexus.admin.projects.Projects]] operations bundle.
    *
    * @param index           the project and organization label index
    * @param appConfig       the application configuration
    * @param sourcingConfig  the sourcing configuration
    * @tparam F              a [[cats.effect.ConcurrentEffect]] instance
    * @return the operations bundle in an ''F'' context.
    */
  def apply[F[_]: ConcurrentEffect](
      index: Index[F],
      organizations: Organizations[F],
      appConfig: AppConfig,
      sourcingConfig: AkkaSourcingConfig)(implicit as: ActorSystem, mt: ActorMaterializer): F[Projects[F]] = {
    implicit val http: HttpConfig = appConfig.http
    implicit val clock: Clock     = Clock.systemUTC
    val aggF: F[Agg[F]] =
      AkkaAggregate.shardedF(
        "projects",
        ProjectState.Initial,
        next,
        Eval.apply[F],
        PassivationStrategy.immediately,
        RetryStrategy.never,
        sourcingConfig,
        appConfig.cluster.shards
      )
    aggF.map(agg => new Projects(agg, index, organizations))
  }

  /**
    * State transition function for resources; considering a current state (the ''state'' argument) and an emitted
    * ''event'' it computes the next state.
    *
    * @param state the current state
    * @param event the emitted event
    * @return the next state
    */
  private[projects] def next(state: ProjectState, event: ProjectEvent): ProjectState = (state, event) match {
    case (Initial, ProjectCreated(id, org, label, rev, instant, subject, value)) =>
      Current(id, org, label, rev, instant, subject, value, deprecated = false)
    // $COVERAGE-OFF$
    case (Initial, _) => Initial
    // $COVERAGE-ON$
    case (c: Current, _) if c.deprecated => c
    case (c, _: ProjectCreated)          => c
    case (c: Current, ProjectUpdated(_, label, desc, rev, instant, subject)) =>
      c.copy(label = label, description = desc, rev = rev, instant = instant, subject = subject)
    case (c: Current, ProjectDeprecated(_, rev, instant, subject)) =>
      c.copy(rev = rev, instant = instant, subject = subject, deprecated = true)
  }

  private[projects] object Eval {

    private def createProject(state: ProjectState, c: CreateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial => Right(ProjectCreated(c.id, c.organization, c.label, c.description, 1L, c.instant, c.subject))
        case _       => Left(ProjectAlreadyExists)
      }

    private def updateProject(state: ProjectState, c: UpdateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial                      => Left(ProjectDoesNotExists)
        case s: Current if s.rev != c.rev => Left(IncorrectRev(c.rev))
        case s: Current if s.deprecated   => Left(ProjectIsDeprecated)
        case s: Current                   => updateProjectAfter(s, c)
      }

    private def updateProjectAfter(state: Current, c: UpdateProject): Either[ProjectRejection, ProjectEvent] =
      Right(ProjectUpdated(state.id, c.label, c.description, state.rev + 1, c.instant, c.subject))

    private def deprecateProject(state: ProjectState, c: DeprecateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial                      => Left(ProjectDoesNotExists)
        case s: Current if s.rev != c.rev => Left(IncorrectRev(c.rev))
        case s: Current if s.deprecated   => Left(ProjectIsDeprecated)
        case s: Current                   => deprecateProjectAfter(s, c)
      }

    private def deprecateProjectAfter(state: Current, c: DeprecateProject): Either[ProjectRejection, ProjectEvent] =
      Right(ProjectDeprecated(state.id, state.rev + 1, c.instant, c.subject))

    /**
      * Command evaluation logic for projects; considering a current ''state'' and a command to be evaluated either
      * reject the command or emit a new event that characterizes the change for an aggregate.
      *
      * @param state the current state
      * @param cmd   the command to be evaluated
      * @return either a rejection or the event emitted
      */
    final def apply[F[_]](state: ProjectState, cmd: ProjectCommand)(
        implicit F: Async[F]): F[Either[ProjectRejection, ProjectEvent]] = {

      cmd match {
        case c: CreateProject    => F.pure(createProject(state, c))
        case c: UpdateProject    => F.pure(updateProject(state, c))
        case c: DeprecateProject => F.pure(deprecateProject(state, c))
      }
    }
  }
}
