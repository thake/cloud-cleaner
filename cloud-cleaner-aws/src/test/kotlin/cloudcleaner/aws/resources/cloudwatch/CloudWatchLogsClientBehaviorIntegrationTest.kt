package cloudcleaner.aws.resources.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.deleteLogGroup
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
class CloudWatchLogsClientBehaviorIntegrationTest {
  private val cloudWatchLogsClient = CloudWatchLogsClient {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }
  private val stub = CloudWatchLogsClientStub()

  @Test
  fun `deleteLogGroup should throw ResourceNotFoundException for non-existent log group`() = runTest {
    val logGroupName = "/aws/lambda/non-existent-${Uuid.random()}"

    val actual = shouldThrow<ServiceException> { stub.deleteLogGroup { this.logGroupName = logGroupName } }
    val expected = shouldThrow<ServiceException> { cloudWatchLogsClient.deleteLogGroup { this.logGroupName = logGroupName } }
    actual.shouldBeEquivalentTo(expected)
  }
}
