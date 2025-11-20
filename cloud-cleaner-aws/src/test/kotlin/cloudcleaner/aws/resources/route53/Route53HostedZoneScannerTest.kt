package cloudcleaner.aws.resources.route53

import cloudcleaner.aws.resources.route53.Route53ClientStub.HostedZoneStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class Route53HostedZoneScannerTest {
  private val route53Client = Route53ClientStub()
  private val underTest = Route53HostedZoneScanner(route53Client)

  @Test
  fun `scan should return empty list when no hosted zones are present`() = runTest {
    // when
    val hostedZones = underTest.scan()

    // then
    hostedZones.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated hosted zones`() = runTest {
    // given
    repeat(100) {
      route53Client.hostedZones.add(
          HostedZoneStub(
              id = "Z${100000000000 + it}",
              name = "example-$it.com.",
              callerReference = "ref-$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualHostedZones = actualFlow.toList()
    actualHostedZones.shouldHaveSize(100)
  }

  @Test
  fun `scan should return hosted zone details correctly`() = runTest {
    // given
    val hostedZone = HostedZoneStub(
        id = "Z1234567890ABC",
        name = "example.com.",
        callerReference = "test-ref",
        isPrivate = false
    )
    route53Client.hostedZones.add(hostedZone)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualHostedZones = actualFlow.toList()
    actualHostedZones.shouldHaveSize(1)
    val actualHostedZone = actualHostedZones.first()
    actualHostedZone.id.value shouldBe "Z1234567890ABC"
    actualHostedZone.hostedZoneName shouldBe "example.com."
    actualHostedZone.isPrivate shouldBe false
    actualHostedZone.dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle multiple hosted zones`() = runTest {
    // given
    route53Client.hostedZones.add(
        HostedZoneStub(
            id = "Z111111111111",
            name = "example1.com.",
            callerReference = "ref-1"
        )
    )
    route53Client.hostedZones.add(
        HostedZoneStub(
            id = "Z222222222222",
            name = "example2.com.",
            callerReference = "ref-2"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualHostedZones = actualFlow.toList()
    actualHostedZones.shouldHaveSize(2)
  }

  @Test
  fun `scan should handle private hosted zones`() = runTest {
    // given
    route53Client.hostedZones.add(
        HostedZoneStub(
            id = "Z333333333333",
            name = "internal.example.com.",
            callerReference = "ref-3",
            isPrivate = true
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualHostedZones = actualFlow.toList()
    actualHostedZones.shouldHaveSize(1)
    val actualHostedZone = actualHostedZones.first()
    actualHostedZone.isPrivate shouldBe true
  }

  @Test
  fun `scan should extract clean zone ID without prefix`() = runTest {
    // given
    route53Client.hostedZones.add(
        HostedZoneStub(
            id = "Z444444444444",
            name = "test.com.",
            callerReference = "ref-4"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualHostedZones = actualFlow.toList()
    actualHostedZones.shouldHaveSize(1)
    val actualHostedZone = actualHostedZones.first()
    // Should not have /hostedzone/ prefix
    actualHostedZone.id.value shouldBe "Z444444444444"
  }
}

