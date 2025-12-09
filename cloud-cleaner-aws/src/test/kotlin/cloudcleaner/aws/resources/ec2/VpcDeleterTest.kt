package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.model.Ec2Exception
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ec2.Ec2ClientStub.VpcStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test

class VpcDeleterTest {
  private val ec2Client = Ec2ClientStub()
  private val underTest = VpcDeleter(ec2Client)

  @Test
  fun `delete should successfully delete VPC`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test"))

    // when
    underTest.delete(vpc)

    // then
    ec2Client.getVpcOrNull("vpc-test").shouldBeNull()
  }

  @Test
  fun `delete should ignore already deleted VPC`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-nonexistent", REGION),
    )

    // when & then
    assertDoesNotThrow { underTest.delete(vpc) }
  }

  @Test
  fun `delete should throw exception for non-Vpc resource`() = runTest {
    // given
    val nonVpcResource = object : cloudcleaner.resources.Resource {
      override val id = cloudcleaner.resources.StringId("test")
      override val name = "test"
      override val type = "NotEc2Vpc"
      override val properties = emptyMap<String, String>()
      override val containedResources = emptySet<cloudcleaner.resources.Id>()
      override val dependsOn = emptySet<cloudcleaner.resources.Id>()
    }

    // when & then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(nonVpcResource)
    }
  }

  @Test
  fun `delete should propagate exceptions from EC2 client`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    ec2Client.deleteFailsWithError = true

    // when & then
    shouldThrow<Ec2Exception> {
      underTest.delete(vpc)
    }
  }

  @Test
  fun `delete should handle DependencyViolation error`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test"))
    ec2Client.deleteFailsWithDependencyViolation = true

    // when & then
    val exception = shouldThrow<Ec2Exception> {
      underTest.delete(vpc)
    }
    // Should propagate the exception (not swallow it)
    exception.sdkErrorMetadata.errorCode.shouldBe("DependencyViolation")
  }

  @Test
  fun `delete should handle multiple deletions`() = runTest {
    // given
    val vpc1 = Vpc(
        vpcId = VpcId("vpc-1", REGION),
    )
    val vpc2 = Vpc(
        vpcId = VpcId("vpc-2", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-1"))
    ec2Client.vpcs.add(VpcStub("vpc-2"))

    // when
    underTest.delete(vpc1)
    underTest.delete(vpc2)

    // then
    ec2Client.vpcs.size.shouldBe(0)
  }

  @Test
  fun `delete should only delete specified VPC`() = runTest {
    // given
    val vpc1 = Vpc(
        vpcId = VpcId("vpc-1", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-1"))
    ec2Client.vpcs.add(VpcStub("vpc-2"))

    // when
    underTest.delete(vpc1)

    // then
    ec2Client.getVpcOrNull("vpc-1").shouldBeNull()
    ec2Client.getVpcOrNull("vpc-2")?.vpcId.shouldBe("vpc-2")
  }

  @Test
  fun `delete should work with VPCs that have tags`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
        tags = mapOf("Name" to "my-vpc", "Environment" to "test"),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test", tags = mapOf("Name" to "my-vpc")))

    // when
    underTest.delete(vpc)

    // then
    ec2Client.getVpcOrNull("vpc-test").shouldBeNull()
  }

  @Test
  fun `delete should work with VPCs that have dependencies`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
        dependencies = setOf(Ec2DhcpOptionsId("dopt-12345", REGION)),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test", dhcpOptionsId = "dopt-12345"))

    // when
    underTest.delete(vpc)

    // then
    ec2Client.getVpcOrNull("vpc-test").shouldBeNull()
  }

  @Test
  fun `delete should handle VPCs with default DHCP options`() = runTest {
    // given
    val vpc = Vpc(
        vpcId = VpcId("vpc-test", REGION),
    )
    ec2Client.vpcs.add(VpcStub("vpc-test", dhcpOptionsId = "default"))

    // when
    underTest.delete(vpc)

    // then
    ec2Client.getVpcOrNull("vpc-test").shouldBeNull()
  }
}

