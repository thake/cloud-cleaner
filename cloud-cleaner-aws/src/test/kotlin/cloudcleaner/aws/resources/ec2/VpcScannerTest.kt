package cloudcleaner.aws.resources.ec2

import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ec2.Ec2ClientStub.VpcStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class VpcScannerTest {
  private val ec2Client = Ec2ClientStub()
  private val underTest = VpcScanner(ec2Client, REGION)

  @Test
  fun `scan should return empty list when no VPCs are present`() = runTest {
    val vpcs = underTest.scan()
    // then
    vpcs.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of VPCs`() = runTest {
    // given
    repeat(5) {
      ec2Client.vpcs.add(VpcStub(vpcId = "vpc-$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(5)
  }

  @Test
  fun `scan should skip default VPCs`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub(vpcId = "vpc-default", isDefault = true))
    ec2Client.vpcs.add(VpcStub(vpcId = "vpc-12345"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().vpcId.value.shouldBe("vpc-12345")
  }

  @Test
  fun `scan should set correct resource type`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().type.shouldBe("Ec2Vpc")
  }

  @Test
  fun `scan should handle VPCs without tags`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().properties.shouldBeEmpty()
  }

  @Test
  fun `scan should handle VPCs with tags`() = runTest {
    // given
    val tags = mapOf("Name" to "my-vpc", "Environment" to "test")
    ec2Client.vpcs.add(VpcStub("vpc-test", tags = tags))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().properties.shouldBe(tags)
    actual.first().tags.shouldBe(tags)
  }

  @Test
  fun `scan should include region in ID`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().vpcId.region.shouldBe(REGION)
    actual.first().vpcId.toString().shouldBe("vpc-test ($REGION)")
  }

  @Test
  fun `scan should include DHCP options as dependency`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test", dhcpOptionsId = "dopt-12345"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    val dependencies = actual.first().dependsOn
    dependencies.shouldHaveSize(1)
    dependencies.first().shouldBe(Ec2DhcpOptionsId("dopt-12345", REGION))
  }

  @Test
  fun `scan should not include default DHCP options as dependency`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test", dhcpOptionsId = "default"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle VPCs without DHCP options`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test", dhcpOptionsId = null))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should use VPC name from tags as resource name`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test", tags = mapOf("Name" to "my-vpc")))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().name.shouldBe("my-vpc")
  }

  @Test
  fun `scan should use VPC ID as name when no Name tag exists`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().name.shouldBe("vpc-test")
  }

  @Test
  fun `scan should have no contained resources`() = runTest {
    // given
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actual = actualFlow.toList()
    actual.shouldHaveSize(1)
    actual.first().containedResources.shouldBeEmpty()
  }
}

