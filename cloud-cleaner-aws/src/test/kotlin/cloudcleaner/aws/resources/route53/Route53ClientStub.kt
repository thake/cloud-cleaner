@file:OptIn(InternalApi::class)

package cloudcleaner.aws.resources.route53

import aws.sdk.kotlin.services.route53.Route53Client
import aws.sdk.kotlin.services.route53.model.ChangeAction
import aws.sdk.kotlin.services.route53.model.ChangeInfo
import aws.sdk.kotlin.services.route53.model.ChangeResourceRecordSetsRequest
import aws.sdk.kotlin.services.route53.model.ChangeResourceRecordSetsResponse
import aws.sdk.kotlin.services.route53.model.ChangeStatus
import aws.sdk.kotlin.services.route53.model.CreateHostedZoneRequest
import aws.sdk.kotlin.services.route53.model.CreateHostedZoneResponse
import aws.sdk.kotlin.services.route53.model.DeleteHostedZoneRequest
import aws.sdk.kotlin.services.route53.model.DeleteHostedZoneResponse
import aws.sdk.kotlin.services.route53.model.HostedZone
import aws.sdk.kotlin.services.route53.model.HostedZoneAlreadyExists
import aws.sdk.kotlin.services.route53.model.HostedZoneConfig
import aws.sdk.kotlin.services.route53.model.HostedZoneNotEmpty
import aws.sdk.kotlin.services.route53.model.ListHostedZonesRequest
import aws.sdk.kotlin.services.route53.model.ListHostedZonesResponse
import aws.sdk.kotlin.services.route53.model.ListResourceRecordSetsRequest
import aws.sdk.kotlin.services.route53.model.ListResourceRecordSetsResponse
import aws.sdk.kotlin.services.route53.model.NoSuchHostedZone
import aws.sdk.kotlin.services.route53.model.ResourceRecord
import aws.sdk.kotlin.services.route53.model.ResourceRecordSet
import aws.sdk.kotlin.services.route53.model.RrType
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata.Companion.ErrorCode
import aws.smithy.kotlin.runtime.time.Instant
import io.mockk.mockk

class Route53ClientStub(
    val delegate: Route53Client = mockk<Route53Client>()
) : Route53Client by delegate {
  val hostedZones = mutableListOf<HostedZoneStub>()

  data class HostedZoneStub(
      val id: String,
      val name: String,
      val isPrivate: Boolean = false,
      val callerReference: String,
      val resourceRecordSets: MutableList<ResourceRecordSet> = mutableListOf()
  ) {
    init {
      // Add default NS and SOA records that AWS creates automatically
      if (resourceRecordSets.isEmpty()) {
        resourceRecordSets.add(
            ResourceRecordSet {
              name = this@HostedZoneStub.name
              type = RrType.Ns
              ttl = 172800
              listOf(
                  ResourceRecord { value = "ns-1.awsdns-1.com." },
                  ResourceRecord { value = "ns-2.awsdns-2.org." },
              ).also { resourceRecords = it }
            }
        )
        resourceRecordSets.add(
            ResourceRecordSet {
              name = this@HostedZoneStub.name
              type = RrType.Soa
              ttl = 900
              resourceRecords = listOf(
                  ResourceRecord {
                    value = "ns-1.awsdns-1.com. awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400"
                  }
              )
            }
        )
      }
    }
  }

  private fun findHostedZone(id: String?) =
      hostedZones.find { it.id == id || "/hostedzone/${it.id}" == id }
          ?: throw NoSuchHostedZone {}.apply {
            sdkErrorMetadata.attributes[ErrorCode] = "NoSuchHostedZone"
          }

  override suspend fun createHostedZone(input: CreateHostedZoneRequest): CreateHostedZoneResponse {
    val name = input.name ?: throw IllegalArgumentException("Hosted zone name is required")
    val callerReference = input.callerReference ?: throw IllegalArgumentException("Caller reference is required")

    // Check if a zone with the same caller reference already exists
    if (hostedZones.any { it.callerReference == callerReference }) {
      throw HostedZoneAlreadyExists {}
    }

    val id = "Z${(100000000000..999999999999).random()}"
    val isPrivate = input.hostedZoneConfig?.privateZone ?: false

    val newZone = HostedZoneStub(
        id = id,
        name = name,
        isPrivate = isPrivate,
        callerReference = callerReference
    )
    hostedZones.add(newZone)

    return CreateHostedZoneResponse {
      hostedZone = newZone.toHostedZone()
      location = "https://route53.amazonaws.com/2013-04-01/hostedzone/$id"
      changeInfo = ChangeInfo {
        this.id = "change-$id"
        status = ChangeStatus.Insync
        submittedAt = Instant.now()
      }
    }
  }

  override suspend fun listHostedZones(input: ListHostedZonesRequest): ListHostedZonesResponse {
    val startIndex = input.marker?.toIntOrNull() ?: 0
    val requestMaxItems = input.maxItems ?: 100
    val page = hostedZones.drop(startIndex).take(requestMaxItems)

    val nextMarker = if (startIndex + requestMaxItems < hostedZones.size) {
      (startIndex + requestMaxItems).toString()
    } else null

    return ListHostedZonesResponse {
      this.hostedZones = page.map { it.toHostedZone() }
      marker = nextMarker ?: ""
      isTruncated = nextMarker != null
      maxItems = requestMaxItems
    }
  }

  override suspend fun listResourceRecordSets(
      input: ListResourceRecordSetsRequest
  ): ListResourceRecordSetsResponse {
    val zone = findHostedZone(input.hostedZoneId)

    return ListResourceRecordSetsResponse {
      resourceRecordSets = zone.resourceRecordSets.toList()
      isTruncated = false
      maxItems = zone.resourceRecordSets.size
    }
  }

  override suspend fun changeResourceRecordSets(
      input: ChangeResourceRecordSetsRequest
  ): ChangeResourceRecordSetsResponse {
    val zone = findHostedZone(input.hostedZoneId)
    val changes = input.changeBatch?.changes ?: emptyList()

    changes.forEach { change ->
      when (change.action) {
        ChangeAction.Create -> {
          change.resourceRecordSet?.let { zone.resourceRecordSets.add(it) }
        }
        ChangeAction.Delete -> {
          change.resourceRecordSet?.let { recordSetToDelete ->
            zone.resourceRecordSets.removeIf {
              it.name == recordSetToDelete.name && it.type == recordSetToDelete.type
            }
          }
        }
        ChangeAction.Upsert -> {
          change.resourceRecordSet?.let { recordSet ->
            zone.resourceRecordSets.removeIf {
              it.name == recordSet.name && it.type == recordSet.type
            }
            zone.resourceRecordSets.add(recordSet)
          }
        }
        else -> {} // Handle unknown action types
      }
    }

    return ChangeResourceRecordSetsResponse {
      changeInfo = ChangeInfo {
        id = "change-${zone.id}"
        status = ChangeStatus.Insync
        submittedAt = Instant.now()
      }
    }
  }

  override suspend fun deleteHostedZone(input: DeleteHostedZoneRequest): DeleteHostedZoneResponse {
    val zone = findHostedZone(input.id)

    // Check if zone has non-default record sets
    val nonDefaultRecords = zone.resourceRecordSets.filter {
      it.type != RrType.Ns && it.type != RrType.Soa
    }
    if (nonDefaultRecords.isNotEmpty()) {
      throw HostedZoneNotEmpty {}.apply {
        sdkErrorMetadata.attributes[ErrorCode] = "HostedZoneNotEmpty"
      }
    }

    hostedZones.remove(zone)

    return DeleteHostedZoneResponse {
      changeInfo = ChangeInfo {
        id = "change-${zone.id}"
        status = ChangeStatus.Insync
        submittedAt = Instant.now()
      }
    }
  }

  private fun HostedZoneStub.toHostedZone(): HostedZone = HostedZone {
    id = "/hostedzone/${this@toHostedZone.id}"
    name = this@toHostedZone.name
    callerReference = this@toHostedZone.callerReference
    config = HostedZoneConfig {
      privateZone = this@toHostedZone.isPrivate
    }
    resourceRecordSetCount = this@toHostedZone.resourceRecordSets.size.toLong()
  }

  override fun close() {
    // No-op for stub
  }
}

