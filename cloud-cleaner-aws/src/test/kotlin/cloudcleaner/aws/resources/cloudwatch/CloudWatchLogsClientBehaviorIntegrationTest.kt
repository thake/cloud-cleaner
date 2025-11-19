package cloudcleaner.aws.resources.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.createLogGroup
import aws.sdk.kotlin.services.cloudwatchlogs.deleteLogGroup
import aws.sdk.kotlin.services.cloudwatchlogs.describeLogGroups
import aws.sdk.kotlin.services.cloudwatchlogs.model.ResourceNotFoundException
import cloudcleaner.aws.resources.LocalStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CloudWatchLogsClientBehaviorIntegrationTest {
  private val cloudWatchLogsClient = CloudWatchLogsClient {
    endpointUrl = LocalStack.localstackUrl
    region = "eu-central-1"
  }

  @Test
  fun `describeLogGroups should return empty list when no log groups exist`() =
      shouldReturnEmptyListWhenNoLogGroupsExist(cloudWatchLogsClient)

  @Test
  fun `describeLogGroups should return empty list when no log groups exist with stub`() =
      shouldReturnEmptyListWhenNoLogGroupsExist(CloudWatchLogsClientStub())

  private fun shouldReturnEmptyListWhenNoLogGroupsExist(client: CloudWatchLogsClient) = runTest {
    val response = client.describeLogGroups {}
    response.logGroups.shouldBeEmpty()
  }

  @Test
  fun `createLogGroup should create a log group`() = shouldCreateLogGroup(cloudWatchLogsClient)

  @Test
  fun `createLogGroup should create a log group with stub`() = shouldCreateLogGroup(CloudWatchLogsClientStub())

  private fun shouldCreateLogGroup(client: CloudWatchLogsClient) = runTest {
    val logGroupName = "/aws/lambda/test-${Uuid.random()}"
    client.createLogGroup { this.logGroupName = logGroupName }

    val response = client.describeLogGroups { logGroupNamePrefix = logGroupName }
    response.logGroups?.shouldHaveSize(1)
    response.logGroups?.first()?.logGroupName shouldBe logGroupName
  }

  @Test
  fun `deleteLogGroup should delete an existing log group`() = shouldDeleteLogGroup(cloudWatchLogsClient)

  @Test
  fun `deleteLogGroup should delete an existing log group with stub`() = shouldDeleteLogGroup(CloudWatchLogsClientStub())

  private fun shouldDeleteLogGroup(client: CloudWatchLogsClient) = runTest {
    val logGroupName = "/aws/lambda/test-${Uuid.random()}"
    client.createLogGroup { this.logGroupName = logGroupName }

    client.deleteLogGroup { this.logGroupName = logGroupName }

    val response = client.describeLogGroups { logGroupNamePrefix = logGroupName }
    response.logGroups.shouldBeEmpty()
  }

  @Test
  fun `deleteLogGroup should throw ResourceNotFoundException for non-existent log group`() =
      shouldThrowExceptionForNonExistentLogGroup(cloudWatchLogsClient)

  @Test
  fun `deleteLogGroup should throw ResourceNotFoundException for non-existent log group with stub`() =
      shouldThrowExceptionForNonExistentLogGroup(CloudWatchLogsClientStub())

  private fun shouldThrowExceptionForNonExistentLogGroup(client: CloudWatchLogsClient) = runTest {
    val logGroupName = "/aws/lambda/non-existent-${Uuid.random()}"

    shouldThrow<ResourceNotFoundException> {
      client.deleteLogGroup { this.logGroupName = logGroupName }
    }
  }

  @Test
  fun `describeLogGroups should support pagination`() = shouldSupportPagination(cloudWatchLogsClient)

  @Test
  fun `describeLogGroups should support pagination with stub`() = shouldSupportPagination(CloudWatchLogsClientStub())

  private fun shouldSupportPagination(client: CloudWatchLogsClient) = runTest {
    val prefix = "test-pagination-${Uuid.random()}"
    // Create multiple log groups
    repeat(3) { index ->
      client.createLogGroup { logGroupName = "$prefix-$index" }
    }

    // Request with limit
    val firstPage = client.describeLogGroups {
      logGroupNamePrefix = prefix
      limit = 2
    }

    firstPage.logGroups?.shouldHaveSize(2)

    // If there's a next token, fetch the next page
    if (firstPage.nextToken != null) {
      val secondPage = client.describeLogGroups {
        logGroupNamePrefix = prefix
        nextToken = firstPage.nextToken
      }
      secondPage.logGroups?.shouldHaveSize(1)
    }
  }

  @Test
  fun `describeLogGroups should filter by prefix`() = shouldFilterByPrefix(cloudWatchLogsClient)

  @Test
  fun `describeLogGroups should filter by prefix with stub`() = shouldFilterByPrefix(CloudWatchLogsClientStub())

  private fun shouldFilterByPrefix(client: CloudWatchLogsClient) = runTest {
    val prefix1 = "prefix1-${Uuid.random()}"
    val prefix2 = "prefix2-${Uuid.random()}"

    client.createLogGroup { logGroupName = "$prefix1-group" }
    client.createLogGroup { logGroupName = "$prefix2-group" }

    val response = client.describeLogGroups { logGroupNamePrefix = prefix1 }
    response.logGroups?.shouldHaveSize(1)
    response.logGroups?.first()?.logGroupName shouldBe "$prefix1-group"
  }
}

