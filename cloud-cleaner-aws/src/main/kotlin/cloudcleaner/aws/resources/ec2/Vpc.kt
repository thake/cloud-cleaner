package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.deleteVpc
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

data class VpcId(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "Ec2Vpc"

class VpcResourceDefinitionFactory : AwsResourceDefinitionFactory<Vpc> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<Vpc> {
    val client = Ec2Client {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = VpcDeleter(client),
        resourceScanner = VpcScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class VpcScanner(private val ec2Client: Ec2Client, val region: String) : ResourceScanner<Vpc> {
  override fun scan(): Flow<Vpc> = flow {
    val response = ec2Client.describeVpcs()
    response.vpcs?.forEach { vpc ->
      val vpcId = vpc.vpcId ?: return@forEach

      // Skip default VPCs (they require special handling and are typically kept)
      if (vpc.isDefault == true) {
        return@forEach
      }

      val tags = vpc.tags?.associate { it.key!! to it.value!! } ?: emptyMap()

      // VPCs depend on their DHCP options set
      val dependencies = mutableSetOf<Id>()
      vpc.dhcpOptionsId?.let { dhcpOptionsId ->
        // Skip default DHCP options as they cannot be deleted
        if (dhcpOptionsId != "default") {
          dependencies.add(Ec2DhcpOptionsId(dhcpOptionsId, region))
        }
      }

      emit(
          Vpc(
              vpcId = VpcId(vpcId, region),
              tags = tags,
              dependencies = dependencies,
          ))
    }
  }
}

class VpcDeleter(private val ec2Client: Ec2Client) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val vpc = resource as? Vpc ?: throw IllegalArgumentException("Resource not an Ec2Vpc")

    try {
      ec2Client.deleteVpc { vpcId = vpc.vpcId.value }
    } catch (e: ServiceException) {
      val errorCode = e.sdkErrorMetadata.errorCode
      when (errorCode) {
        "InvalidVpcID.NotFound" -> {
          logger.debug { "VPC ${vpc.vpcId.value} not found, assuming already deleted" }
        }
        "DependencyViolation" -> {
          logger.warn { "VPC ${vpc.vpcId.value} has dependencies (subnets, IGWs, etc.), cannot delete" }
          throw e
        }
        else -> {
          logger.error(e) { "Failed to delete VPC ${vpc.vpcId.value}: ${e.message}" }
          throw e
        }
      }
    }
  }
}

data class Vpc(
  val vpcId: VpcId,
  val tags: Map<String, String> = emptyMap(),
  private val dependencies: Set<Id> = emptySet(),
) : Resource {
  override val id: Id = vpcId
  override val containedResources: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = dependencies
  override val name: String = tags["Name"] ?: vpcId.value
  override val type: String = TYPE
  override val properties: Map<String, String> = tags

  override fun toString() = vpcId.toString()
}

