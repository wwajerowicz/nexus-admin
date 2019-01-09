package ch.epfl.bluebrain.nexus.admin.persistence

import java.time.Instant
import java.util.UUID

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.admin.client.types.events.OrganizationEvent._
import ch.epfl.bluebrain.nexus.admin.client.types.events.ProjectEvent._
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.iam.client.types.Identity
import ch.epfl.bluebrain.nexus.rdf.syntax.node.unsafe._
import ch.epfl.bluebrain.nexus.service.test.ActorSystemFixture
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.{EitherValues, Inspectors, Matchers}

class EventSerializerSpec
    extends ActorSystemFixture("EventSerializerSpec")
    with Matchers
    with Inspectors
    with EitherValues
    with Resources {

  private val orgId   = UUID.fromString("d8cf3015-1bce-4dda-ba80-80cd4b5281e5")
  private val projId  = UUID.fromString("27f5429c-b56f-4f8e-8481-f4334ebb334c")
  private val instant = Instant.parse("2018-12-21T15:37:44.203831Z")
  private val subject = Identity.User("alice", "bbp")
  private val base    = url"http://localhost:8080/base".value
  private val mappings = Map("nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/".value,
                             "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#".value)

  private val data: Map[AnyRef, (String, Json)] = Map(
    (OrganizationCreated(orgId, "myorg", "My organization", instant, subject),
     ("OrganizationEvent", jsonContentOf("/events/org-created.json"))),
    (OrganizationUpdated(orgId, 42L, "myorg", "My organization", instant, subject),
     ("OrganizationEvent", jsonContentOf("/events/org-updated.json"))),
    (OrganizationDeprecated(orgId, 42L, instant, subject),
     ("OrganizationEvent", jsonContentOf("/events/org-deprecated.json"))),
    (ProjectCreated(projId, orgId, "myproj", None, mappings, base, instant, subject),
     ("ProjectEvent", jsonContentOf("/events/project-created.json"))),
    (ProjectUpdated(projId, "myproj", Some("My project"), mappings, base, 42L, instant, subject),
     ("ProjectEvent", jsonContentOf("/events/project-updated.json"))),
    (ProjectDeprecated(projId, 42L, instant, subject),
     ("ProjectEvent", jsonContentOf("/events/project-deprecated.json")))
  )

  "An EventSerializer" should {
    val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

    "produce the correct event manifests" in {
      forAll(data.toList) {
        case (event, (manifest, _)) =>
          serializer.manifest(event) shouldEqual manifest
      }
    }

    "correctly serialize known events" in {
      forAll(data.toList) {
        case (event, (_, json)) =>
          parse(new String(serializer.toBinary(event))).right.value shouldEqual json
      }
    }

    "correctly deserialize known events" in {
      forAll(data.toList) {
        case (event, (manifest, json)) =>
          serializer.fromBinary(json.noSpaces.getBytes, manifest) shouldEqual event
      }
    }

    "fail to produce a manifest" in {
      intercept[IllegalArgumentException](serializer.manifest("aaa"))
    }

    "fail to serialize an unknown type" in {
      intercept[IllegalArgumentException](serializer.toBinary("aaa"))
    }

    "fail to deserialize an unknown type" in {
      forAll(data.toList) {
        case (event, (manifest, repr)) =>
          intercept[IllegalArgumentException] {
            serializer.fromBinary((repr + "typo").getBytes, manifest) shouldEqual event
          }
      }
    }
  }

}
