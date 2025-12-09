package cloudcleaner.aws.resources.ec2

import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ec2.Ec2ClientStub.DhcpOptionsStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class Ec2DhcpOptionsScannerTest {
  private val ec2Client = Ec2ClientStub()
  private val underTest = Ec2DhcpOptionsScanner(ec2Client, REGION)

  @Test
  fun `scan should return empty list when no DHCP options are present`() = runTest {
    val dhcpOptions = underTest.scan()
    // then
    dhcpOptions.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of DHCP options`() = runTest {
    // given
    repeat(5) {
      ec2Client.dhcpOptions.add(DhcpOptionsStub(dhcpOptionsId = "dopt-$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(5)
  }

  @Test
  fun `scan should skip default DHCP options`() = runTest {
    // given
    ec2Client.dhcpOptions.add(DhcpOptionsStub(dhcpOptionsId = "default"))
    ec2Client.dhcpOptions.add(DhcpOptionsStub(dhcpOptionsId = "dopt-12345"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().dhcpOptionsId.value.shouldBe("dopt-12345")
  }

  @Test
  fun `scan should set correct resource type`() = runTest {
    // given
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().type.shouldBe("Ec2DhcpOptions")
  }

  @Test
  fun `scan should handle DHCP options without tags`() = runTest {
    // given
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().properties.shouldBeEmpty()
  }

  @Test
  fun `scan should include region in ID`() = runTest {
    // given
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().dhcpOptionsId.region.shouldBe(REGION)
    actual.first().dhcpOptionsId.toString().shouldBe("dopt-test ($REGION)")
  }

  @Test
  fun `scan should have no dependencies`() = runTest {
    // given
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().dependsOn.shouldBeEmpty()
  }
}

