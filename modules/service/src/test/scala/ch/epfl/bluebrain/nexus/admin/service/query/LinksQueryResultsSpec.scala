package ch.epfl.bluebrain.nexus.admin.service.query

import java.util.UUID

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.admin.refined.ld.Id
import ch.epfl.bluebrain.nexus.admin.service.encoders.RoutesEncoder
import ch.epfl.bluebrain.nexus.admin.service.types.Links
import ch.epfl.bluebrain.nexus.commons.http.ContextUri
import ch.epfl.bluebrain.nexus.commons.types.search.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.commons.types.search.{Pagination, QueryResult, QueryResults}
import ch.epfl.bluebrain.nexus.commons.types.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import eu.timepit.refined.api.RefType.applyRef
import io.circe.{Encoder, Json}
import org.scalatest.{Matchers, WordSpecLike}
import io.circe.syntax._

class LinksQueryResultsSpec extends WordSpecLike with Matchers {

  private val sqr: Encoder[ScoredQueryResult[String]] = Encoder.encodeJson.contramap { res =>
    Json.obj(
      "source" -> Json.fromString(res.source),
      "score"  -> Json.fromFloatOrNull(res.score)
    )
  }

  implicit val idExtractor: (String) => Id = { p =>
    applyRef[Id](s"http://localhost/v0/$p").toOption.get
  }
  val routesEncoder         = new RoutesEncoder[String](ContextUri("http://127.0.0.1/context"))
  implicit val linksEncoder = routesEncoder.linksEncoder
  private val uqr: Encoder[UnscoredQueryResult[String]] = Encoder.encodeJson.contramap { res =>
    Json.obj("source" -> Json.fromString(res.source))
  }
  private implicit val qr  = QueryResult.queryResultEncoder(sqr, uqr)
  private implicit val lqr = LinksQueryResults.encodeLinksQueryResults(qr, linksEncoder)

  "A LinksQueryResults" should {
    val total                      = 17L
    val size                       = 5
    val page                       = List.fill(size)(UnscoredQueryResult(UUID.randomUUID().toString))
    val resp: QueryResults[String] = UnscoredQueryResults[String](total, page)

    "return the correct next links when pagination offset is 0" in {
      val pagination = Pagination(0L, size)

      val uri = Uri("http://localhost/v0/schemas/nexus/core?size=10&other=4")
      LinksQueryResults(resp, pagination, uri).links shouldEqual Links(
        "self" -> uri,
        "next" -> uri.withQuery(Query("size" -> "5", "other" -> "4", "from" -> "5")))
      LinksQueryResults(resp, pagination, uri).response shouldEqual resp
    }

    "return the correct next and previous links when pagination offset is 5" in {
      val pagination = Pagination(5L, size)

      val uri = Uri("http://localhost/v0/schemas/nexus/core?size=4")
      LinksQueryResults(resp, pagination, uri).links shouldEqual Links(
        "self"     -> uri,
        "previous" -> uri.withQuery(Query("size" -> "5", "from" -> "0")),
        "next"     -> uri.withQuery(Query("size" -> "5", "from" -> "10")))
    }

    "return the correct previous links when pagination offset is 15" in {
      val pagination                     = Pagination(15L, size)
      val page                           = List.fill(3)(UnscoredQueryResult(UUID.randomUUID().toString))
      val response: QueryResults[String] = UnscoredQueryResults[String](total, page)

      val uri = Uri("http://localhost/v0/schemas/nexus/core")
      LinksQueryResults(response, pagination, uri).links shouldEqual Links(
        "self"     -> uri,
        "previous" -> uri.withQuery(Query("from" -> "10", "size" -> "5")))
    }

    "return the correct previous and next links when offset is 2" in {
      val pagination = Pagination(2L, size)
      val uri        = Uri("http://localhost/v0/schemas/nexus/core")
      LinksQueryResults(resp, pagination, uri).links shouldEqual Links(
        "self"     -> uri,
        "previous" -> uri.withQuery(Query("from" -> "0", "size" -> "2")),
        "next"     -> uri.withQuery(Query("from" -> "7", "size" -> "5")))
    }

    "return the correct previous links when offset is out of scope" in {
      val pagination                     = Pagination(44L, 5)
      val response: QueryResults[String] = UnscoredQueryResults[String](total, List())

      val uri = Uri("http://localhost/v0/schemas/nexus/core")
      LinksQueryResults(response, pagination, uri).links shouldEqual Links(
        "self"     -> uri,
        "previous" -> uri.withQuery(Query("from" -> "12", "size" -> "5")))
    }

    "return the correct previous links when offset is out of scope and list has one element" in {
      val pagination                     = Pagination(200L, 3)
      val response: QueryResults[String] = UnscoredQueryResults[String](1, List())

      val uri = Uri("http://localhost/v0/schemas/nexus/core")
      LinksQueryResults(response, pagination, uri).links shouldEqual Links(
        "self"     -> uri,
        "previous" -> uri.withQuery(Query("from" -> "0", "size" -> "3")))
    }

    "return a correct Json representation from an unscored response" in {
      val uri = Uri("http://localhost/v0/schemas/nexus/core")
      val links = Links("self" -> uri,
                        "previous" -> uri.withQuery(Query("from" -> "0", "size"  -> "5")),
                        "next"     -> uri.withQuery(Query("from" -> "10", "size" -> "5")))
      val linksResults = LinksQueryResults(resp, links)
      linksResults.asJson shouldEqual Json.obj(
        "total"   -> Json.fromLong(linksResults.response.total),
        "results" -> linksResults.response.results.asJson,
        "links"   -> links.asJson
      )
    }

    "return a correct Json representation form a scored response" in {
      val uri = Uri("http://localhost/v0/schemas/nexus/core")
      val links = Links("self" -> uri,
                        "previous" -> uri.withQuery(Query("from" -> "0", "size"  -> "5")),
                        "next"     -> uri.withQuery(Query("from" -> "10", "size" -> "5")))
      val scoredPage                       = List.fill(size)(ScoredQueryResult(1F, UUID.randomUUID().toString))
      val scoredResp: QueryResults[String] = ScoredQueryResults[String](total, 1F, scoredPage)

      val linksResults = LinksQueryResults(scoredResp, links)
      linksResults.asJson shouldEqual Json.obj(
        "total"    -> Json.fromLong(linksResults.response.total),
        "maxScore" -> Json.fromFloatOrNull(1F),
        "results"  -> linksResults.response.results.asJson,
        "links"    -> links.asJson
      )
    }
  }
}