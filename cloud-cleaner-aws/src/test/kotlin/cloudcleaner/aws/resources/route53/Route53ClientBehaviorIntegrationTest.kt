package cloudcleaner.aws.resources.route53

import aws.sdk.kotlin.services.route53.Route53Client
import aws.sdk.kotlin.services.route53.changeResourceRecordSets
import aws.sdk.kotlin.services.route53.createHostedZone
import aws.sdk.kotlin.services.route53.deleteHostedZone
import aws.sdk.kotlin.services.route53.listResourceRecordSets
import aws.sdk.kotlin.services.route53.model.Change
import aws.sdk.kotlin.services.route53.model.ChangeAction
import aws.sdk.kotlin.services.route53.model.ResourceRecord
import aws.sdk.kotlin.services.route53.model.ResourceRecordSet
import aws.sdk.kotlin.services.route53.model.RrType
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class Route53ClientBehaviorIntegrationTest {
  private val route53Client = Route53Client {
    endpointUrl = LocalStack.localstackUrl
    region = "us-east-1"
  }
  private val stub = Route53ClientStub()

  @Test
  fun `listResourceRecordSets should throw if hosted zone does not exist`() = runTest {
    val nonExistentZoneId = "Z${Uuid.random()}"
    val actual = shouldThrow<ServiceException> {
      stub.listResourceRecordSets {
        hostedZoneId = nonExistentZoneId
      }
    }
    val expected = shouldThrow<ServiceException> {
      route53Client.listResourceRecordSets {
        hostedZoneId = nonExistentZoneId
      }
    }
    actual.shouldBeEquivalentTo(expected)
  }
  @Test
  fun `deleteHostedZone should throw exception for zone with record sets`() = runTest {
    val zoneName = "test-${Uuid.random()}.com."
    val callerReference = "ref-${Uuid.random()}"

    val createResponse =
        route53Client.createHostedZone {
          name = zoneName
          this.callerReference = callerReference
        }

    val zoneId = createResponse.hostedZone?.id
    zoneId.shouldNotBeNull()

      // Add a record set
    route53Client.changeResourceRecordSets {
        hostedZoneId = zoneId
        changeBatch {
          changes =
              listOf(
                  Change {
                    action = ChangeAction.Create
                    resourceRecordSet = ResourceRecordSet {
                      name = "test.$zoneName"
                      type = RrType.A
                      ttl = 300
                      resourceRecords = listOf(ResourceRecord { value = "192.168.1.1" })
                    }
                  }
              )
        }
      }
    stub.hostedZones.add(
        Route53ClientStub.HostedZoneStub(
            id = zoneId,
            name = zoneName,
            callerReference = callerReference,
            resourceRecordSets =
                mutableListOf(
                    ResourceRecordSet {
                      name = "test.$zoneName"
                      type = RrType.A
                      ttl = 300
                      resourceRecords = listOf(ResourceRecord { value = "192.168.1.1" })
                    }
                )
        )
    )

    val actual = shouldThrow<ServiceException> { stub.deleteHostedZone { id = zoneId } }
    val expected = shouldThrow<ServiceException> { route53Client.deleteHostedZone { id = zoneId } }
    actual.shouldBeEquivalentTo(expected)
  }
}
