package ch.epfl.bluebrain.nexus.admin.core.projects

import java.util

import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, TestKit}
import cats.instances.future._
import ch.epfl.bluebrain.nexus.admin.core.CallerCtx._
import ch.epfl.bluebrain.nexus.admin.core.CommonRejections.IllegalPayload
import ch.epfl.bluebrain.nexus.admin.core.Fault.CommandRejected
import ch.epfl.bluebrain.nexus.admin.core.TestHepler
import ch.epfl.bluebrain.nexus.admin.core.config.AppConfig.ProjectsConfig
import ch.epfl.bluebrain.nexus.admin.core.projects.Project.ProjectValue
import ch.epfl.bluebrain.nexus.admin.core.projects.Projects.EvalProject
import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceState._
import ch.epfl.bluebrain.nexus.admin.core.types.Ref._
import ch.epfl.bluebrain.nexus.admin.core.types.RefVersioned
import ch.epfl.bluebrain.nexus.admin.query.QueryPayload
import ch.epfl.bluebrain.nexus.admin.refined.ld.Id
import ch.epfl.bluebrain.nexus.admin.refined.permissions._
import ch.epfl.bluebrain.nexus.admin.refined.project._
import ch.epfl.bluebrain.nexus.commons.http.JsonOps._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Permission._
import ch.epfl.bluebrain.nexus.commons.iam.acls.{FullAccessControlList, Path, Permission, Permissions}
import ch.epfl.bluebrain.nexus.commons.iam.identity.Caller.AnonymousCaller
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity.Anonymous
import ch.epfl.bluebrain.nexus.commons.shacl.validator.{ImportResolver, ShaclValidator}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlClient
import ch.epfl.bluebrain.nexus.commons.types.search.QueryResult.ScoredQueryResult
import ch.epfl.bluebrain.nexus.commons.types.search.QueryResults.ScoredQueryResults
import ch.epfl.bluebrain.nexus.commons.types.search.{Pagination, QueryResult}
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate._
import eu.timepit.refined.api.RefType.{applyRef, refinedRefType}
import eu.timepit.refined.auto._
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.graph.Node
import org.apache.jena.query.{QuerySolution, QuerySolutionMap, ResultSet}
import org.apache.jena.rdf.model._
import org.apache.jena.sparql.engine.binding.Binding
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{CancelAfterFailure, Matchers, TryValues, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectsSpec
    extends TestKit(ActorSystem("ProjectsSpec"))
    with DefaultTimeout
    with WordSpecLike
    with Matchers
    with TryValues
    with ScalaFutures
    with TestHepler
    with MockitoSugar
    with CancelAfterFailure {

  private implicit val caller: AnonymousCaller = AnonymousCaller(Anonymous())
  private implicit val config: ProjectsConfig =
    ProjectsConfig(3 seconds, "https://nexus.example.ch/v1/projects/", 100000L)
  private implicit val ex                             = system.dispatcher
  private val aggProject                              = MemoryAggregate("projects")(Initial, next, EvalProject().apply).toF[Future]
  private val cl                                      = mock[SparqlClient[Future]]
  implicit val shaclValidator: ShaclValidator[Future] = ShaclValidator(ImportResolver.noop[Future])
  private val projects                                = Projects(aggProject, cl)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(5 seconds, 100 milliseconds)

  trait Context {
    val id: ProjectReference = genReference()
    val value: ProjectValue  = genProjectValue()
  }

  "A Project bundle" should {
    implicit val hasWrite: HasWriteProjects =
      applyRef[HasWriteProjects](
        FullAccessControlList((Identity.Anonymous(), Path./, Permissions(Read, Permission("projects/write"))))
      ).toPermTry.success.value
    implicit val hasOwn: HasCreateProjects =
      applyRef[HasCreateProjects](FullAccessControlList(
        (Identity.Anonymous(), Path./, Permissions(Read, Permission("projects/create"))))).toPermTry.success.value
    implicit val hasRead: HasReadProjects =
      applyRef[HasReadProjects](FullAccessControlList(
        (Identity.Anonymous(), Path./, Permissions(Read, Permission("projects/read"))))).toPermTry.success.value

    "create a new project" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.fetch(id).futureValue shouldEqual Some(Project(id, 1L, value, value.asJson, deprecated = false))
    }

    "prevent creating a project without a name" in new Context {
      val rej =
        projects.create(id, value.asJson.removeKeys("name")).failed.futureValue.asInstanceOf[CommandRejected].rejection
      rej shouldBe a[ResourceRejection.ShapeConstraintViolations]
    }

    "prevent updating a project that overrides prefixMappings with other values" in new Context {
      val updatedValue: ProjectValue = genProjectValue(nxvPrefix = randomProjectPrefix())
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.update(id, 1L, updatedValue.asJson).failed.futureValue shouldEqual CommandRejected(WrappedRejection(
        IllegalPayload("Invalid 'prefixMappings' object",
                       Some("The 'prefixMappings' values cannot be overridden but just new values can be appended."))))
    }

    "prevent updating a project with maxAttachmentSize as a string" in new Context {
      val updatedValue: ProjectValue = genProjectUpdate()
      val updatedJson = updatedValue.asJson deepMerge Json.obj(
        "config" -> Json.obj("maxAttachmentSize" -> Json.fromString("one")))
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      val rej = projects.update(id, 1L, updatedJson).failed.futureValue.asInstanceOf[CommandRejected].rejection
      rej shouldBe a[ResourceRejection.ShapeConstraintViolations]
    }

    "update a project" in new Context {
      val updatedValue: ProjectValue = genProjectUpdate()
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.update(id, 1L, updatedValue.asJson).futureValue shouldEqual RefVersioned(id, 2L)
      projects.fetch(id).futureValue shouldEqual Some(
        Project(id, 2L, updatedValue, updatedValue.asJson, deprecated = false))
    }

    "deprecate a project" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(id, 1L).futureValue shouldEqual RefVersioned(id, 2L)
      projects.fetch(id).futureValue shouldEqual Some(Project(id, 2L, value, value.asJson, deprecated = true))
    }

    "fetch old revision of a project" in new Context {
      val updatedValue: ProjectValue = genProjectUpdate()
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.update(id, 1L, updatedValue.asJson).futureValue shouldEqual RefVersioned(id, 2L)

      projects.fetch(id, 2L).futureValue shouldEqual Some(
        Project(id, 2L, updatedValue, updatedValue.asJson, deprecated = false))
      projects.fetch(id, 1L).futureValue shouldEqual Some(Project(id, 1L, value, value.asJson, deprecated = false))

    }

    "return None when fetching a revision that does not exist" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.fetch(id, 10L).futureValue shouldEqual None
    }

    "prevent double deprecations" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(id, 1L).futureValue shouldEqual RefVersioned(id, 2L)
      projects.deprecate(id, 2L).failed.futureValue shouldEqual CommandRejected(ResourceIsDeprecated)
    }

    "prevent updating when deprecated" in new Context {
      val updatedValue: ProjectValue = genProjectValue()
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(id, 1L).futureValue shouldEqual RefVersioned(id, 2L)
      projects.update(id, 2L, updatedValue.asJson).failed.futureValue shouldEqual CommandRejected(ResourceIsDeprecated)
    }

    "prevent updating to non existing project" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.update(genReference(), 2L, value.asJson).failed.futureValue shouldEqual CommandRejected(
        ResourceDoesNotExists)
    }

    "prevent updating with incorrect rev" in new Context {
      val updatedValue: ProjectValue = genProjectValue()
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.update(id, 2L, updatedValue.asJson).failed.futureValue shouldEqual CommandRejected(
        IncorrectRevisionProvided)
    }

    "prevent deprecation with incorrect rev" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(id, 2L).failed.futureValue shouldEqual CommandRejected(IncorrectRevisionProvided)
    }

    "prevent deprecation to non existing project incorrect rev" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(genReference(), 1L).failed.futureValue shouldEqual CommandRejected(ResourceDoesNotExists)
    }

    "project cannot be used from a child resource when does not exist" in new Context {
      projects.validateUnlocked(genReference()).failed.futureValue shouldEqual CommandRejected(
        ParentResourceDoesNotExists)
    }

    "project cannot be used from a child resource when it is already deprecated" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.deprecate(id, 1L).futureValue shouldEqual RefVersioned(id, 2L)
      projects.validateUnlocked(id).failed.futureValue shouldEqual CommandRejected(ResourceIsDeprecated)
    }

    "project can be used from a child resource" in new Context {
      projects.create(id, value.asJson).futureValue shouldEqual RefVersioned(id, 1L)
      projects.validateUnlocked(id).futureValue shouldEqual (())
    }

    "list all projects for user with read on /" in new Context {
      val queryPayload  = QueryPayload(q = Some("test"), published = Some(true))
      val pagination    = Pagination(from = genInt().toLong, size = genInt())
      val expectedQuery = s"""
                            |PREFIX bds: <http://www.bigdata.com/rdf/search#>
                            |SELECT DISTINCT ?total ?s ?maxscore ?score ?rank
                            |WITH {
                            |  SELECT DISTINCT ?s  (max(?rsv) AS ?score) (max(?pos) AS ?rank)
                            |  WHERE {
                            |?s ?matchedProperty ?matchedValue .
                            |?matchedValue bds:search "test" .
                            |?matchedValue bds:relevance ?rsv .
                            |?matchedValue bds:rank ?pos .
                            |FILTER ( !isBlank(?s) )
                            |
                            |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/published> ?var_1 .
                            |?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/Project> .
                            |FILTER ( ?var_1 = true )
                            |
                            |
                            |  }
                            |GROUP BY ?s
                            |} AS %resultSet
                            |WHERE {
                            |  {
                            |    SELECT (COUNT(DISTINCT ?s) AS ?total) (max(?score) AS ?maxscore)
                            |    WHERE { INCLUDE %resultSet }
                            |  }
                            |  UNION
                            |  {
                            |    SELECT *
                            |    WHERE { INCLUDE %resultSet }
                            |    ORDER BY ?s
                            |    LIMIT ${pagination.size}
                            |    OFFSET ${pagination.from}
                            |  }
                            |}
                            |ORDER BY DESC(?score)""".stripMargin
      val expectedResult = ScoredQueryResults[Id](
        3,
        1.0f,
        List(
          ScoredQueryResult[Id](
            1.0f,
            applyRef[Id](s"https://nexus.example.ch/v1/projects/${genReference().value}").toOption.get),
          ScoredQueryResult[Id](
            0.5f,
            applyRef[Id](s"https://nexus.example.ch/v1/projects/${genReference().value}").toOption.get),
          ScoredQueryResult[Id](
            0.25f,
            applyRef[Id](s"https://nexus.example.ch/v1/projects/${genReference().value}").toOption.get)
        )
      )
      val resultSet: ResultSet = resultSetFromQueryResults(expectedResult)
      when(cl.query(expectedQuery)).thenReturn(Future.successful(resultSet))

      projects.list(queryPayload, pagination).futureValue shouldEqual expectedResult
    }

    "list only projects the user has access to" in new Context {
      val queryPayload = QueryPayload(q = Some("test"), published = Some(true))
      val pagination   = Pagination(from = genInt().toLong, size = genInt())
      val projectRef1  = genReference()
      val projectRef2  = genReference()
      val projectRef3  = genReference()
      val expectedQuery =
        s"""
           |PREFIX bds: <http://www.bigdata.com/rdf/search#>
           |SELECT DISTINCT ?total ?s ?maxscore ?score ?rank
           |WITH {
           |  SELECT DISTINCT ?s  (max(?rsv) AS ?score) (max(?pos) AS ?rank)
           |  WHERE {
           |?s ?matchedProperty ?matchedValue .
           |?matchedValue bds:search "test" .
           |?matchedValue bds:relevance ?rsv .
           |?matchedValue bds:rank ?pos .
           |FILTER ( !isBlank(?s) )
           |
           |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/published> ?var_1 .
           |?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/Project> .
           |FILTER ( ?var_1 = true )
           |
           |
           |FILTER ( ?s = <https://nexus.example.ch/v1/projects/${projectRef1.value}> || ?s = <https://nexus.example.ch/v1/projects/${projectRef2.value}> || ?s = <https://nexus.example.ch/v1/projects/${projectRef3.value}> )
           |
           |
           |  }
           |GROUP BY ?s
           |} AS %resultSet
           |WHERE {
           |  {
           |    SELECT (COUNT(DISTINCT ?s) AS ?total) (max(?score) AS ?maxscore)
           |    WHERE { INCLUDE %resultSet }
           |  }
           |  UNION
           |  {
           |    SELECT *
           |    WHERE { INCLUDE %resultSet }
           |    ORDER BY ?s
           |    LIMIT ${pagination.size}
           |    OFFSET ${pagination.from}
           |  }
           |}
           |ORDER BY DESC(?score)""".stripMargin
      val expectedResult = ScoredQueryResults[Id](
        3,
        1.0f,
        List(
          ScoredQueryResult[Id](
            1.0f,
            applyRef[Id](s"https://nexus.example.ch/v1/projects/${projectRef1.value}").toOption.get),
          ScoredQueryResult[Id](
            0.5f,
            applyRef[Id](s"https://nexus.example.ch/v1/projects/${projectRef2.value}").toOption.get),
          ScoredQueryResult[Id](0.25f,
                                applyRef[Id](s"https://nexus.example.ch/v1/projects/${projectRef3.value}").toOption.get)
        )
      )
      val resultSet: ResultSet = resultSetFromQueryResults(expectedResult)
      when(cl.query(expectedQuery)).thenReturn(Future.successful(resultSet))

      implicit val hasRead: HasReadProjects =
        applyRef[HasReadProjects](
          FullAccessControlList(
            (Identity.Anonymous(), Path./(projectRef1.value), Permissions(Read, Permission("projects/read"))),
            (Identity.Anonymous(), Path./(projectRef2.value), Permissions(Read, Permission("projects/read"))),
            (Identity.Anonymous(), Path./(projectRef3.value), Permissions(Read, Permission("projects/read")))
          )).toPermTry.success.value

      projects.list(queryPayload, pagination).futureValue shouldEqual expectedResult
    }
  }

  private def resultSetFromQueryResults(queryResults: ScoredQueryResults[Id]): ResultSet = {
    def rdfLiteralNode(float: Number): RDFNode = {
      new RDFNode {
        override def isAnon: Boolean = false

        override def isLiteral: Boolean = true

        override def isURIResource: Boolean = false

        override def isResource: Boolean = false

        override def as[T <: RDFNode](view: Class[T]): T = ???

        override def canAs[T <: RDFNode](view: Class[T]): Boolean = ???

        override def getModel: Model = ???

        override def inModel(m: Model): RDFNode = ???

        override def visitWith(rv: RDFVisitor): AnyRef = ???

        override def asResource(): Resource = ???

        override def asLiteral(): Literal = new Literal {
          override def inModel(m: Model): Literal = ???

          override def getValue: AnyRef = ???

          override def getDatatype: RDFDatatype = ???

          override def getDatatypeURI: String = ???

          override def getLexicalForm: String = float.toString

          override def getBoolean: Boolean = ???

          override def getByte: Byte = ???

          override def getShort: Short = ???

          override def getInt: Int = ???

          override def getLong: Long = ???

          override def getChar: Char = ???

          override def getFloat: Float = ???

          override def getDouble: Double = ???

          override def getString: String = ???

          override def getLanguage: String = ???

          override def isWellFormedXML: Boolean = ???

          override def sameValueAs(other: Literal): Boolean = ???

          override def isAnon: Boolean = ???

          override def isLiteral: Boolean = ???

          override def isURIResource: Boolean = ???

          override def isResource: Boolean = ???

          override def as[T <: RDFNode](view: Class[T]): T = ???

          override def canAs[T <: RDFNode](view: Class[T]): Boolean = ???

          override def getModel: Model = ???

          override def visitWith(rv: RDFVisitor): AnyRef = ???

          override def asResource(): Resource = ???

          override def asLiteral(): Literal = ???

          override def asNode(): Node = ???
        }

        override def asNode(): Node = ???
      }
    }

    def rdfResourceNode(uri: String): RDFNode = {
      new RDFNode {
        override def isAnon: Boolean = false

        override def isLiteral: Boolean = true

        override def isURIResource: Boolean = false

        override def isResource: Boolean = false

        override def as[T <: RDFNode](view: Class[T]): T = ???

        override def canAs[T <: RDFNode](view: Class[T]): Boolean = ???

        override def getModel: Model = ???

        override def inModel(m: Model): RDFNode = ???

        override def visitWith(rv: RDFVisitor): AnyRef = ???

        override def asResource(): Resource = new Resource {
          override def getId: AnonId = ???

          override def inModel(m: Model): Resource = ???

          override def hasURI(uri: String): Boolean = ???

          override def getURI: String = uri

          override def getNameSpace: String = ???

          override def getLocalName: String = ???

          override def getRequiredProperty(p: Property): Statement = ???

          override def getRequiredProperty(p: Property, lang: String): Statement = ???

          override def getProperty(p: Property): Statement = ???

          override def getProperty(p: Property, lang: String): Statement = ???

          override def listProperties(p: Property): StmtIterator = ???

          override def listProperties(p: Property, lang: String): StmtIterator = ???

          override def listProperties(): StmtIterator = ???

          override def addLiteral(p: Property, o: Boolean): Resource = ???

          override def addLiteral(p: Property, o: Long): Resource = ???

          override def addLiteral(p: Property, o: Char): Resource = ???

          override def addLiteral(value: Property, d: Double): Resource = ???

          override def addLiteral(value: Property, d: Float): Resource = ???

          override def addLiteral(p: Property, o: scala.Any): Resource = ???

          override def addLiteral(p: Property, o: Literal): Resource = ???

          override def addProperty(p: Property, o: String): Resource = ???

          override def addProperty(p: Property, o: String, l: String): Resource = ???

          override def addProperty(p: Property, lexicalForm: String, datatype: RDFDatatype): Resource = ???

          override def addProperty(p: Property, o: RDFNode): Resource = ???

          override def hasProperty(p: Property): Boolean = ???

          override def hasLiteral(p: Property, o: Boolean): Boolean = ???

          override def hasLiteral(p: Property, o: Long): Boolean = ???

          override def hasLiteral(p: Property, o: Char): Boolean = ???

          override def hasLiteral(p: Property, o: Double): Boolean = ???

          override def hasLiteral(p: Property, o: Float): Boolean = ???

          override def hasLiteral(p: Property, o: scala.Any): Boolean = ???

          override def hasProperty(p: Property, o: String): Boolean = ???

          override def hasProperty(p: Property, o: String, l: String): Boolean = ???

          override def hasProperty(p: Property, o: RDFNode): Boolean = ???

          override def removeProperties(): Resource = ???

          override def removeAll(p: Property): Resource = ???

          override def begin(): Resource = ???

          override def abort(): Resource = ???

          override def commit(): Resource = ???

          override def getPropertyResourceValue(p: Property): Resource = ???

          override def isAnon: Boolean = ???

          override def isLiteral: Boolean = ???

          override def isURIResource: Boolean = ???

          override def isResource: Boolean = ???

          override def as[T <: RDFNode](view: Class[T]): T = ???

          override def canAs[T <: RDFNode](view: Class[T]): Boolean = ???

          override def getModel: Model = ???

          override def visitWith(rv: RDFVisitor): AnyRef = ???

          override def asResource(): Resource = ???

          override def asLiteral(): Literal = ???

          override def asNode(): Node = ???
        }

        override def asLiteral(): Literal = ???

        override def asNode(): Node = ???
      }
    }

    def maxScoreAndTotalQuerySolution(qrs: ScoredQueryResults[Id]): QuerySolution = {
      val qs = new QuerySolutionMap
      qs.add("?maxscore", rdfLiteralNode(qrs.maxScore))
      qs.add("total", rdfLiteralNode(qrs.total))
      qs
    }

    def queryResultToSolution(queryResult: QueryResult[Id]): QuerySolution = queryResult match {
      case ScoredQueryResult(score, source) =>
        val querySolution = new QuerySolutionMap
        querySolution.add("?score", rdfLiteralNode(score))
        querySolution.add("?s", rdfResourceNode(source.value))
        querySolution
      case _ => ???
    }

    val iter: Iterator[QuerySolution] =
      (maxScoreAndTotalQuerySolution(queryResults) :: queryResults.results.map(queryResultToSolution)).iterator

    new ResultSet {
      override def hasNext: Boolean = iter.hasNext

      override def next(): QuerySolution = iter.next()

      override def nextSolution(): QuerySolution = iter.next()

      override def nextBinding(): Binding = ???

      override def getRowNumber: Int = ???

      override def getResultVars: util.List[String] = ???

      override def getResourceModel: Model = ???
    }

  }
}
