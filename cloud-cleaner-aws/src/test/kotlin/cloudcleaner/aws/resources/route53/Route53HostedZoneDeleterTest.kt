package cloudcleaner.aws.resources.route53

import aws.sdk.kotlin.services.route53.model.ResourceRecord
import aws.sdk.kotlin.services.route53.model.ResourceRecordSet
import aws.sdk.kotlin.services.route53.model.RrType
import cloudcleaner.aws.resources.route53.Route53ClientStub.HostedZoneStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class Route53HostedZoneDeleterTest {
  private val route53Client = Route53ClientStub()
  private val underTest = Route53HostedZoneDeleter(route53Client)

  @Test
  fun `delete should successfully delete an empty hosted zone`() = runTest {
    // given
    val hostedZone =
        Route53HostedZone(
            id = HostedZoneId("Z1234567890ABC"),
            hostedZoneName = "example.com.",
        )
    route53Client.hostedZones.add(HostedZoneStub(id = hostedZone.id.value, name = hostedZone.hostedZoneName, callerReference = "test-ref"))

    // when
    underTest.delete(hostedZone)

    // then
    route53Client.hostedZones.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a Route53HostedZone`() = runTest {
    // given
    val invalidResource =
        object : cloudcleaner.resources.Resource {
          override val id = StringId("invalid")
          override val name = "invalid"
          override val type = "NotARoute53HostedZone"
          override val properties = emptyMap<String, String>()
        }

    // when/then
    shouldThrow<IllegalArgumentException> { underTest.delete(invalidResource) }
  }

  @Test
  fun `delete should delete resource record sets before deleting hosted zone`() = runTest {
    // given
    val hostedZone =
        Route53HostedZone(
            id = HostedZoneId("Z1234567890ABC"),
            hostedZoneName = "example.com.",
        )
    val zoneStub = HostedZoneStub(id = hostedZone.id.value, name = hostedZone.hostedZoneName, callerReference = "test-ref")

    // Add some custom record sets
    zoneStub.resourceRecordSets.add(
        ResourceRecordSet {
          name = "www.example.com."
          type = RrType.A
          ttl = 300
          resourceRecords = listOf(ResourceRecord { value = "192.168.1.1" })
        })
    zoneStub.resourceRecordSets.add(
        ResourceRecordSet {
          name = "mail.example.com."
          type = RrType.A
          ttl = 300
          resourceRecords = listOf(ResourceRecord { value = "192.168.1.2" })
        })

    route53Client.hostedZones.add(zoneStub)

    // when
    underTest.delete(hostedZone)

    // then - verify all cleanup happened
    route53Client.hostedZones.shouldHaveSize(0)
  }

  @Test
  fun `delete should ignore not existing hosted zone`() = runTest {
    // given
    val hostedZone =
        Route53HostedZone(
            id = HostedZoneId("Z9999999999999"),
            hostedZoneName = "nonexistent.com.",
        )

    // when/then - should not throw
    underTest.delete(hostedZone)
  }

  @Test
  fun `delete should delete multiple hosted zones`() = runTest {
    // given
    val hostedZone1 =
        Route53HostedZone(
            id = HostedZoneId("Z1111111111111"),
            hostedZoneName = "example1.com.",
        )
    val hostedZone2 =
        Route53HostedZone(
            id = HostedZoneId("Z2222222222222"),
            hostedZoneName = "example2.com.",
        )

    route53Client.hostedZones.add(HostedZoneStub(id = hostedZone1.id.value, name = hostedZone1.hostedZoneName, callerReference = "ref-1"))
    route53Client.hostedZones.add(HostedZoneStub(id = hostedZone2.id.value, name = hostedZone2.hostedZoneName, callerReference = "ref-2"))

    // when
    underTest.delete(hostedZone1)
    underTest.delete(hostedZone2)

    // then
    route53Client.hostedZones.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified hosted zone`() = runTest {
    // given
    val hostedZone1 =
        Route53HostedZone(
            id = HostedZoneId("Z1111111111111"),
            hostedZoneName = "example1.com.",
        )
    val hostedZone2 =
        Route53HostedZone(
            id = HostedZoneId("Z2222222222222"),
            hostedZoneName = "example2.com.",
        )

    route53Client.hostedZones.add(HostedZoneStub(id = hostedZone1.id.value, name = hostedZone1.hostedZoneName, callerReference = "ref-1"))
    route53Client.hostedZones.add(HostedZoneStub(id = hostedZone2.id.value, name = hostedZone2.hostedZoneName, callerReference = "ref-2"))

    // when
    underTest.delete(hostedZone1)

    // then
    route53Client.hostedZones.shouldHaveSize(1)
    route53Client.hostedZones[0].id shouldBe hostedZone2.id.value
  }

  @Test
  fun `delete should delete NS and SOA records with other name during cleanup`() = runTest {
    // given
    val hostedZone = Route53HostedZone(id = HostedZoneId("Z1234567890ABC"), hostedZoneName = "example.com.")
    val zoneStub = HostedZoneStub(id = hostedZone.id.value, name = hostedZone.hostedZoneName, callerReference = "test-ref")
    zoneStub.resourceRecordSets.add(
        ResourceRecordSet {
          name = "my-name"
          type = RrType.Ns
          ttl = 172800
          resourceRecords =
              listOf(
                  ResourceRecord { value = "ns-1.awsdns-1.com." },
              )
        })
    zoneStub.resourceRecordSets.add(
        ResourceRecordSet {
          name = "my-name"
          type = RrType.Soa
          ttl = 172800
          resourceRecords =
              listOf(
                  ResourceRecord { value = "ns-1.awsdns-1.com." },
              )
        })
    route53Client.hostedZones.add(zoneStub)

    // when
    underTest.delete(hostedZone)

    // then - hosted zone should be deleted successfully
    route53Client.hostedZones.shouldHaveSize(0)
  }
}
