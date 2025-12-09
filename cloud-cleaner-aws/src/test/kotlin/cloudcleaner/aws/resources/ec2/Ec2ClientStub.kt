package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.model.DeleteDhcpOptionsRequest
import aws.sdk.kotlin.services.ec2.model.DeleteDhcpOptionsResponse
import aws.sdk.kotlin.services.ec2.model.DeleteVpcRequest
import aws.sdk.kotlin.services.ec2.model.DeleteVpcResponse
import aws.sdk.kotlin.services.ec2.model.DescribeDhcpOptionsRequest
import aws.sdk.kotlin.services.ec2.model.DescribeDhcpOptionsResponse
import aws.sdk.kotlin.services.ec2.model.DescribeVpcsRequest
import aws.sdk.kotlin.services.ec2.model.DescribeVpcsResponse
import aws.sdk.kotlin.services.ec2.model.DhcpOptions
import aws.sdk.kotlin.services.ec2.model.Ec2Exception
import aws.sdk.kotlin.services.ec2.model.Tag
import aws.sdk.kotlin.services.ec2.model.Vpc
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import io.mockk.mockk

class Ec2ClientStub(
  val delegate: Ec2Client = mockk<Ec2Client>(),
) : Ec2Client by delegate {
  val dhcpOptions = mutableListOf<DhcpOptionsStub>()
  val vpcs = mutableListOf<VpcStub>()
  var deleteFailsWithError = false
  var deleteFailsWithDependencyViolation = false

  fun getDhcpOptionsOrNull(dhcpOptionsId: String): DhcpOptionsStub? =
      dhcpOptions.find { it.dhcpOptionsId == dhcpOptionsId }

  fun getDhcpOptions(dhcpOptionsId: String): DhcpOptionsStub =
      getDhcpOptionsOrNull(dhcpOptionsId) ?: throw dhcpOptionsNotFoundException(dhcpOptionsId)

  fun getVpcOrNull(vpcId: String): VpcStub? =
      vpcs.find { it.vpcId == vpcId }

  fun getVpc(vpcId: String): VpcStub =
      getVpcOrNull(vpcId) ?: throw vpcNotFoundException(vpcId)

  data class DhcpOptionsStub(
    val dhcpOptionsId: String,
    val tags: Map<String, String> = emptyMap(),
  )

  data class VpcStub(
    val vpcId: String,
    val tags: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
    val dhcpOptionsId: String? = null,
  )

  override suspend fun describeDhcpOptions(input: DescribeDhcpOptionsRequest): DescribeDhcpOptionsResponse {
    return DescribeDhcpOptionsResponse {
      dhcpOptions = this@Ec2ClientStub.dhcpOptions.map { it.toDhcpOptions() }
    }
  }

  override suspend fun describeVpcs(input: DescribeVpcsRequest): DescribeVpcsResponse {
    return DescribeVpcsResponse {
      vpcs = this@Ec2ClientStub.vpcs.map { it.toVpc() }
    }
  }

  @OptIn(InternalApi::class)
  private fun dhcpOptionsNotFoundException(dhcpOptionsId: String): Ec2Exception {
    val exception = Ec2Exception("The dhcpOptions ID '$dhcpOptionsId' does not exist")
    exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "InvalidDhcpOptionID.NotFound"
    return exception
  }

  @OptIn(InternalApi::class)
  private fun vpcNotFoundException(vpcId: String): Ec2Exception {
    val exception = Ec2Exception("The VPC ID '$vpcId' does not exist")
    exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "InvalidVpcID.NotFound"
    return exception
  }

  @OptIn(InternalApi::class)
  private fun dependencyViolationException(resourceId: String): Ec2Exception {
    val exception = Ec2Exception("The resource '$resourceId' has dependencies and cannot be deleted")
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
        ?: throw dhcpOptionsNotFoundException(dhcpOptionsId)

    dhcpOptions.remove(dhcpOption)

    return DeleteDhcpOptionsResponse {}
  }

  override suspend fun deleteVpc(input: DeleteVpcRequest): DeleteVpcResponse {
    if (deleteFailsWithError) {
      throw Ec2Exception("Delete VPC failed")
    }

    if (deleteFailsWithDependencyViolation) {
      throw dependencyViolationException(input.vpcId!!)
    }

    val vpcId = input.vpcId!!
    val vpc = getVpcOrNull(vpcId)
        ?: throw vpcNotFoundException(vpcId)

    vpcs.remove(vpc)

    return DeleteVpcResponse {}
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

  private fun VpcStub.toVpc(): Vpc = Vpc {
    vpcId = this@toVpc.vpcId
    isDefault = this@toVpc.isDefault
    dhcpOptionsId = this@toVpc.dhcpOptionsId
    tags = this@toVpc.tags.map { (key, value) ->
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

