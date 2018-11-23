package ch.epfl.bluebrain.nexus.admin.marshallers

import akka.http.scaladsl.marshalling.GenericMarshallers.eitherMarshaller
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import ch.epfl.bluebrain.nexus.admin.CommonRejection
import ch.epfl.bluebrain.nexus.admin.CommonRejection.DownstreamServiceError
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.orderedKeys
import ch.epfl.bluebrain.nexus.admin.config.Contexts._
import ch.epfl.bluebrain.nexus.admin.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.admin.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.commons.http.syntax.circe._
import ch.epfl.bluebrain.nexus.commons.types.HttpRejection
import ch.epfl.bluebrain.nexus.rdf.syntax.circe.context._
import ch.epfl.bluebrain.nexus.service.http.directives.StatusFrom
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveEncoder
import io.circe.syntax._

import scala.collection.immutable.Seq

object instances extends FailFastCirceSupport {

  private implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("code")

  implicit val resourceRejectionEncoder: Encoder[ResourceRejection] =
    deriveEncoder[ResourceRejection].mapJson(_ addContext errorCtxUri)

  implicit val commonRejectionEncoder: Encoder[CommonRejection] =
    deriveEncoder[CommonRejection].mapJson(_ addContext errorCtxUri)

  implicit val httpRejectionEncoder: Encoder[HttpRejection] =
    deriveEncoder[HttpRejection].mapJson(_ addContext errorCtxUri)

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`)

  /**
    * `Json` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  final implicit def jsonLd(implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
                            keys: OrderedKeys = orderedKeys): ToEntityMarshaller[Json] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map(contentType =>
      Marshaller.withFixedContentType[Json, MessageEntity](contentType) { json =>
        HttpEntity(`application/ld+json`, printer.pretty(json.sortKeys))
    })
    Marshaller.oneOf(marshallers: _*)
  }

  /**
    * `A` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  final implicit def httpEntity[A](implicit encoder: Encoder[A],
                                   printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
                                   keys: OrderedKeys = orderedKeys): ToEntityMarshaller[A] =
    jsonLd.compose(encoder.apply)

  /**
    * `Either[Rejection,A]` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def either[A: Encoder, B: StatusFrom: Encoder](
      implicit
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true)): ToResponseMarshaller[Either[B, A]] =
    eitherMarshaller(rejection[B], httpEntity[A])

  /**
    * `Rejection` => HTTP response
    *
    * @return marshaller for Rejection value
    */
  implicit final def rejection[A: Encoder](implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
                                           ordered: OrderedKeys = orderedKeys,
                                           statusCodeFrom: StatusFrom[A]): ToResponseMarshaller[A] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map { contentType =>
      Marshaller.withFixedContentType[A, HttpResponse](contentType) { rejection =>
        HttpResponse(status = statusCodeFrom(rejection),
                     entity = HttpEntity(contentType, printer.pretty(rejection.asJson.sortKeys)))
      }
    }
    Marshaller.oneOf(marshallers: _*)
  }

  implicit val rejectionStatusCode: StatusFrom[ResourceRejection] = StatusFrom {
    case IncorrectRevisionProvided   => Conflict
    case ResourceAlreadyExists       => Conflict
    case ResourceDoesNotExists       => NotFound
    case ParentResourceDoesNotExist  => NotFound
    case ResourceIsDeprecated        => BadRequest
    case ResourceValidationError     => BadRequest
    case _: ResourceValidationFailed => BadRequest
    case _: InvalidJsonLD            => BadRequest
    case _: WrappedRejection         => BadRequest
  }

  implicit val commonStatusCode: StatusFrom[CommonRejection] = StatusFrom {
    case _: DownstreamServiceError => InternalServerError
    case _                         => BadRequest
  }

}