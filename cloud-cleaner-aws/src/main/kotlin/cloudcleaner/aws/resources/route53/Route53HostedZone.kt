package cloudcleaner.aws.resources.route53

import aws.sdk.kotlin.services.route53.Route53Client
import aws.sdk.kotlin.services.route53.changeResourceRecordSets
import aws.sdk.kotlin.services.route53.deleteHostedZone
import aws.sdk.kotlin.services.route53.listResourceRecordSets
import aws.sdk.kotlin.services.route53.model.Change
import aws.sdk.kotlin.services.route53.model.ChangeAction
import aws.sdk.kotlin.services.route53.model.ChangeBatch
import aws.sdk.kotlin.services.route53.model.RrType
import aws.sdk.kotlin.services.route53.paginators.listHostedZonesPaginated
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

val route53Logger = KotlinLogging.logger {}

private const val TYPE = "Route53HostedZone"

data class HostedZoneId(val value: String) : Id {
  override fun toString() = "/hostedzone/$value"
}

data class Route53HostedZone(
    override val id: HostedZoneId,
    val hostedZoneName: String,
    val isPrivate: Boolean = false,
) : Resource {
  override val name: String = hostedZoneName
  override val type: String = TYPE
  override val properties: Map<String, String> = mapOf("isPrivate" to isPrivate.toString())
  override val dependsOn: Set<Id> = emptySet()
  override val containedResources: Set<Id> = emptySet()
}

class Route53HostedZoneResourceDefinitionFactory : AwsResourceDefinitionFactory<Route53HostedZone> {
  override val type: String = TYPE

  override fun isAvailableInRegion(region: String) = "global" == region

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<Route53HostedZone> {
    val client = Route53Client {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = Route53HostedZoneDeleter(client),
        resourceScanner = Route53HostedZoneScanner(client),
        close = { client.close() },
    )
  }
}

class Route53HostedZoneScanner(private val route53Client: Route53Client) : ResourceScanner<Route53HostedZone> {
  override fun scan(): Flow<Route53HostedZone> = flow {
    route53Client
        .listHostedZonesPaginated {}
        .collect { response ->
          response.hostedZones.forEach { hostedZone ->
            val hostedZoneId = hostedZone.id
            val hostedZoneName = hostedZone.name
            val isPrivate = hostedZone.config?.privateZone ?: false

            // Extract the zone ID from the full path format (/hostedzone/Z1234567890ABC)
            val cleanZoneId = hostedZoneId.removePrefix("/hostedzone/")

            emit(
                Route53HostedZone(
                    id = HostedZoneId(cleanZoneId),
                    hostedZoneName = hostedZoneName,
                    isPrivate = isPrivate,
                ),
            )
          }
        }
  }
}

private const val MAX_RESOURCE_RECORDS = 1000

class Route53HostedZoneDeleter(private val route53Client: Route53Client) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val hostedZone = resource as? Route53HostedZone ?: throw IllegalArgumentException("Resource not a Route53HostedZone")

    try {
      deleteResourceRecordSets(hostedZone.id.value)
      route53Client.deleteHostedZone { id = hostedZone.id.value }
    } catch (e: ServiceException) {
      if (e.message.contains("NoSuchHostedZone") || e.message.contains("not found", ignoreCase = true)) {
        route53Logger.debug { "Deletion failed because Route53 hosted zone ${hostedZone.hostedZoneName} already has been deleted." }
      } else {
        route53Logger.error(e) { "Failed to delete Route53 hosted zone ${hostedZone.hostedZoneName}: ${e.message}" }
        throw e
      }
    }
  }

  private suspend fun deleteResourceRecordSets(hostedZoneId: String) {
    val recordSets = route53Client.listResourceRecordSets { this.hostedZoneId = hostedZoneId }

    val recordSetsToDelete =
        recordSets.resourceRecordSets.filter { recordSet ->
          // Don't delete the default NS and SOA records
          val type = recordSet.type
          !(type == RrType.Ns || type == RrType.Soa)
        }

    if (recordSetsToDelete.isNotEmpty()) {
      // Delete record sets in batches (Route53 allows up to 1000 changes per request)
      recordSetsToDelete.chunked(MAX_RESOURCE_RECORDS).forEach { batch ->
        route53Client.changeResourceRecordSets {
          this.hostedZoneId = hostedZoneId
          this.changeBatch = ChangeBatch {
            changes =
                batch.map { recordSet ->
                  Change {
                    action = ChangeAction.Delete
                    resourceRecordSet = recordSet
                  }
                }
          }
        }
      }
    }
  }
}
