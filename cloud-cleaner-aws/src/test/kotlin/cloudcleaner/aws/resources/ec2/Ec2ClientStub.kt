package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.model.DeleteDhcpOptionsRequest
import aws.sdk.kotlin.services.ec2.model.DeleteDhcpOptionsResponse
import aws.sdk.kotlin.services.ec2.model.DescribeDhcpOptionsRequest
import aws.sdk.kotlin.services.ec2.model.DescribeDhcpOptionsResponse
import aws.sdk.kotlin.services.ec2.model.DhcpOptions
import aws.sdk.kotlin.services.ec2.model.Ec2Exception
import aws.sdk.kotlin.services.ec2.model.Tag
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import io.mockk.mockk

class Ec2ClientStub(
  val delegate: Ec2Client = mockk<Ec2Client>(),
) : Ec2Client by delegate {
  val dhcpOptions = mutableListOf<DhcpOptionsStub>()
  var deleteFailsWithError = false
  var deleteFailsWithDependencyViolation = false

  fun getDhcpOptionsOrNull(dhcpOptionsId: String): DhcpOptionsStub? =
      dhcpOptions.find { it.dhcpOptionsId == dhcpOptionsId }

  fun getDhcpOptions(dhcpOptionsId: String): DhcpOptionsStub =
      getDhcpOptionsOrNull(dhcpOptionsId) ?: throw resourceNotFoundException(dhcpOptionsId)

  data class DhcpOptionsStub(
    val dhcpOptionsId: String,
    val tags: Map<String, String> = emptyMap(),
  )

  override suspend fun describeDhcpOptions(input: DescribeDhcpOptionsRequest): DescribeDhcpOptionsResponse {
    return DescribeDhcpOptionsResponse {
      dhcpOptions = this@Ec2ClientStub.dhcpOptions.map { it.toDhcpOptions() }
    }
  }

  @OptIn(InternalApi::class)
  private fun resourceNotFoundException(dhcpOptionsId: String): Ec2Exception {
    val exception = Ec2Exception("The dhcpOptions ID '$dhcpOptionsId' does not exist")
    exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "InvalidDhcpOptionID.NotFound"
    return exception
  }

  @OptIn(InternalApi::class)
  private fun dependencyViolationException(dhcpOptionsId: String): Ec2Exception {
    val exception = Ec2Exception("The dhcpOptions '$dhcpOptionsId' has dependencies and cannot be deleted")
    exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "DependencyViolation"
    return exception
  }

  override suspend fun deleteDhcpOptions(input: DeleteDhcpOptionsRequest): DeleteDhcpOptionsResponse {
    if (deleteFailsWithError) {
      throw Ec2Exception("Delete DHCP options failed")
    }

    if (deleteFailsWithDependencyViolation) {
      throw dependencyViolationException(input.dhcpOptionsId!!)
    }

    val dhcpOptionsId = input.dhcpOptionsId!!
    val dhcpOption = getDhcpOptionsOrNull(dhcpOptionsId)
        ?: throw resourceNotFoundException(dhcpOptionsId)

    dhcpOptions.remove(dhcpOption)

    return DeleteDhcpOptionsResponse {}
  }

  private fun DhcpOptionsStub.toDhcpOptions(): DhcpOptions = DhcpOptions {
    dhcpOptionsId = this@toDhcpOptions.dhcpOptionsId
    tags = this@toDhcpOptions.tags.map { (key, value) ->
      Tag {
        this.key = key
        this.value = value
      }
    }
  }

  override fun close() {
    // No-op for stub
  }
}

