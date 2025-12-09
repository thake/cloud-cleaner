package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.deleteDhcpOptions
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class Ec2ClientBehaviorIntegrationTest {
  private val ec2Client = Ec2Client {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }
  private val stub = Ec2ClientStub()

  @Test
  fun `deleteDhcpOptions should throw InvalidDhcpOptionsID_NotFound for non-existent DHCP options`() = runTest {
    val dhcpOptionsId = "dopt-nonexistent-${Uuid.random()}"

    val actual = shouldThrow<ServiceException> { stub.deleteDhcpOptions { this.dhcpOptionsId = dhcpOptionsId } }
    val expected = shouldThrow<ServiceException> { ec2Client.deleteDhcpOptions { this.dhcpOptionsId = dhcpOptionsId } }
    actual.shouldBeEquivalentTo(expected)
  }
}

