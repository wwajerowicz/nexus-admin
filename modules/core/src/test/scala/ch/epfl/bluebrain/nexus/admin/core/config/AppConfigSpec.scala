package ch.epfl.bluebrain.nexus.admin.core.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.core.config.AppConfig._
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.auto._
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration._

class AppConfigSpec extends WordSpecLike with Matchers with ScalatestRouteTest {

  "An AppConfig" should {
    val valid = ConfigFactory.parseResources("test-app.conf").resolve()

    "provide the appropriate config" in {
      implicit val appConfig = new Settings(valid).appConfig

      appConfig.description shouldEqual DescriptionConfig("admin", "local")

      appConfig.instance shouldEqual InstanceConfig("127.0.0.1")

      appConfig.http shouldEqual HttpConfig("127.0.0.1", 8080, "v1", Uri("http://127.0.0.1:8080"))
      implicitly[HttpConfig] shouldEqual HttpConfig("127.0.0.1", 8080, "v1", Uri("http://127.0.0.1:8080"))

      appConfig.runtime shouldEqual RuntimeConfig(30 seconds)

      appConfig.cluster shouldEqual ClusterConfig(10 seconds, 100, None)

      appConfig.persistence shouldEqual PersistenceConfig("cassandra-journal",
                                                          "cassandra-snapshot-store",
                                                          "cassandra-query-journal")

      appConfig.projects shouldEqual ProjectsConfig(10 minutes, "http://127.0.0.1:8080/v1/projects/")
      implicitly[ProjectsConfig] shouldEqual ProjectsConfig(10 minutes, "http://127.0.0.1:8080/v1/projects/")

      appConfig.prefixes shouldEqual PrefixesConfig(
        "http://127.0.0.1:8080/v1/contexts/nexus/core/resource/v0.3.0",
        "http://127.0.0.1:8080/v1/contexts/nexus/core/standards/v0.1.0",
        "http://127.0.0.1:8080/v1/contexts/nexus/core/links/v0.2.0",
        "http://127.0.0.1:8080/v1/contexts/nexus/core/search/v0.1.0",
        "http://127.0.0.1:8080/v1/contexts/nexus/core/distribution/v0.1.0",
        "http://127.0.0.1:8080/v1/contexts/nexus/core/error/v0.1.0"
      )
    }
  }
}
