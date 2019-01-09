package ch.epfl.bluebrain.nexus.admin.persistence

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.admin.client.types.events.{OrganizationEvent, ProjectEvent}
import ch.epfl.bluebrain.nexus.admin.config.Settings
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.rdf.instances._
import ch.epfl.bluebrain.nexus.service.serialization.AkkaCoproductSerializer
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import shapeless.{:+:, CNil}

class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private implicit val iamClientConfig: IamClientConfig = Settings(system).appConfig.iam

  private implicit val config: Configuration = Configuration.default.withDiscriminator("@type")

  private implicit val projectEventDecoder: Decoder[ProjectEvent]           = deriveDecoder[ProjectEvent]
  private implicit val projectEventEncoder: Encoder[ProjectEvent]           = deriveEncoder[ProjectEvent]
  private implicit val organizationEventDecoder: Decoder[OrganizationEvent] = deriveDecoder[OrganizationEvent]
  private implicit val organizationEventEncoder: Encoder[OrganizationEvent] = deriveEncoder[OrganizationEvent]

  private val serializer = new AkkaCoproductSerializer[OrganizationEvent :+: ProjectEvent :+: CNil](1129)

  override val identifier: Int = serializer.identifier

  override def manifest(o: AnyRef): String = serializer.manifest(o)

  override def toBinary(o: AnyRef): Array[Byte] = serializer.toBinary(o)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = serializer.fromBinary(bytes, manifest)
}
