package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.model.Ec2Exception
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ec2.Ec2ClientStub.DhcpOptionsStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test

class Ec2DhcpOptionsDeleterTest {
  private val ec2Client = Ec2ClientStub()
  private val underTest = Ec2DhcpOptionsDeleter(ec2Client)

  @Test
  fun `delete should successfully delete DHCP options`() = runTest {
    // given
    val dhcpOptions = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-test", REGION),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))

    // when
    underTest.delete(dhcpOptions)

    // then
    ec2Client.getDhcpOptionsOrNull("dopt-test").shouldBeNull()
  }

  @Test
  fun `delete should ignore already deleted DHCP options`() = runTest {
    // given
    val dhcpOptions = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-nonexistent", REGION),
    )

    // when & then
    assertDoesNotThrow { underTest.delete(dhcpOptions) }
  }

  @Test
  fun `delete should throw exception for non-Ec2DhcpOptions resource`() = runTest {
    // given
    val nonEc2Resource = object : cloudcleaner.resources.Resource {
      override val id = cloudcleaner.resources.StringId("test")
      override val name = "test"
      override val type = "NotEc2DhcpOptions"
      override val properties = emptyMap<String, String>()
      override val containedResources = emptySet<cloudcleaner.resources.Id>()
      override val dependsOn = emptySet<cloudcleaner.resources.Id>()
    }

    // when & then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(nonEc2Resource)
    }
  }

  @Test
  fun `delete should propagate exceptions from EC2 client`() = runTest {
    // given
    val dhcpOptions = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-test", REGION),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    ec2Client.deleteFailsWithError = true

    // when & then
    shouldThrow<Ec2Exception> {
      underTest.delete(dhcpOptions)
    }
  }

  @Test
  fun `delete should handle DependencyViolation error`() = runTest {
    // given
    val dhcpOptions = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-test", REGION),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test"))
    ec2Client.deleteFailsWithDependencyViolation = true

    // when & then
    val exception = shouldThrow<Ec2Exception> {
      underTest.delete(dhcpOptions)
    }
    // Should propagate the exception (not swallow it)
    exception.sdkErrorMetadata.errorCode.shouldBe("DependencyViolation")
  }

  @Test
  fun `delete should handle multiple deletions`() = runTest {
    // given
    val dhcpOptions1 = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-1", REGION),
    )
    val dhcpOptions2 = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-2", REGION),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-1"))
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-2"))

    // when
    underTest.delete(dhcpOptions1)
    underTest.delete(dhcpOptions2)

    // then
    ec2Client.dhcpOptions.size.shouldBe(0)
  }

  @Test
  fun `delete should only delete specified DHCP options`() = runTest {
    // given
    val dhcpOptions1 = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-1", REGION),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-1"))
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-2"))

    // when
    underTest.delete(dhcpOptions1)

    // then
    ec2Client.getDhcpOptionsOrNull("dopt-1").shouldBeNull()
    ec2Client.getDhcpOptionsOrNull("dopt-2")?.dhcpOptionsId.shouldBe("dopt-2")
  }

  @Test
  fun `delete should work with DHCP options that have tags`() = runTest {
    // given
    val dhcpOptions = Ec2DhcpOptions(
        dhcpOptionsId = Ec2DhcpOptionsId("dopt-test", REGION),
        tags = mapOf("Name" to "my-dhcp", "Environment" to "test"),
    )
    ec2Client.dhcpOptions.add(DhcpOptionsStub("dopt-test", tags = mapOf("Name" to "my-dhcp")))

    // when
    underTest.delete(dhcpOptions)

    // then
    ec2Client.getDhcpOptionsOrNull("dopt-test").shouldBeNull()
  }
}

