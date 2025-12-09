package cloudcleaner.aws.resources.ec2

import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.deleteDhcpOptions
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

val logger = KotlinLogging.logger {}

data class Ec2DhcpOptionsId(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "Ec2DhcpOptions"

class Ec2DhcpOptionsResourceDefinitionFactory : AwsResourceDefinitionFactory<Ec2DhcpOptions> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<Ec2DhcpOptions> {
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
        resourceDeleter = Ec2DhcpOptionsDeleter(client),
        resourceScanner = Ec2DhcpOptionsScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class Ec2DhcpOptionsScanner(private val ec2Client: Ec2Client, val region: String) : ResourceScanner<Ec2DhcpOptions> {
  override fun scan(): Flow<Ec2DhcpOptions> = flow {
    val response = ec2Client.describeDhcpOptions()
    response.dhcpOptions?.forEach { dhcpOptions ->
      val dhcpOptionsId = dhcpOptions.dhcpOptionsId ?: return@forEach

      // Skip default DHCP options (these cannot be deleted)
      if (dhcpOptionsId == "default") {
        return@forEach
      }

      emit(Ec2DhcpOptions(dhcpOptionsId = Ec2DhcpOptionsId(dhcpOptionsId, region)))
    }
  }
}

class Ec2DhcpOptionsDeleter(private val ec2Client: Ec2Client) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val dhcpOptions = resource as? Ec2DhcpOptions ?: throw IllegalArgumentException("Resource not an Ec2DhcpOptions")

    try {
      ec2Client.deleteDhcpOptions { dhcpOptionsId = dhcpOptions.dhcpOptionsId.value }
    } catch (e: ServiceException) {
      val errorCode = e.sdkErrorMetadata.errorCode
      when (errorCode) {
        "InvalidDhcpOptionsID.NotFound",
        "InvalidDhcpOptionID.NotFound" -> {
          logger.debug { "DHCP Options ${dhcpOptions.dhcpOptionsId.value} not found, assuming already deleted" }
        }
        "DependencyViolation" -> {
          logger.warn { "DHCP Options ${dhcpOptions.dhcpOptionsId.value} still in use by VPCs, cannot delete" }
          throw e
        }
        else -> {
          logger.error(e) { "Failed to delete DHCP Options ${dhcpOptions.dhcpOptionsId.value}: ${e.message}" }
          throw e
        }
      }
    }
  }
}

data class Ec2DhcpOptions(
    val dhcpOptionsId: Ec2DhcpOptionsId,
    val tags: Map<String, String> = emptyMap(),
    private val dependencies: Set<Id> = emptySet(),
) : Resource {
  override val id: Id = dhcpOptionsId
  override val containedResources: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = dependencies
  override val name: String = tags["Name"] ?: dhcpOptionsId.value
  override val type: String = TYPE
  override val properties: Map<String, String> = tags

  override fun toString() = dhcpOptionsId.toString()
}
