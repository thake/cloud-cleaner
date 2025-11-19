package cloudcleaner.aws.resources.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DeleteLogGroupRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DeleteLogGroupResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.LogGroup
import aws.sdk.kotlin.services.cloudwatchlogs.model.ResourceAlreadyExistsException
import aws.sdk.kotlin.services.cloudwatchlogs.model.ResourceNotFoundException
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk

class CloudWatchLogsClientStub(
    val delegate: CloudWatchLogsClient = mockk<CloudWatchLogsClient>()
) : CloudWatchLogsClient by delegate {
  val logGroups = mutableListOf<LogGroupStub>()

  data class LogGroupStub(
      val logGroupName: String,
      val logGroupArn: String = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:$logGroupName"
  )

  private fun findLogGroup(logGroupName: String?) =
      logGroups.find { it.logGroupName == logGroupName }
          ?: throw ResourceNotFoundException { message = "Log group $logGroupName not found" }

  override suspend fun createLogGroup(input: CreateLogGroupRequest): CreateLogGroupResponse {
    val logGroupName = input.logGroupName ?: throw IllegalArgumentException("Log group name is required")

    if (logGroups.any { it.logGroupName == logGroupName }) {
      throw ResourceAlreadyExistsException { message = "Log group $logGroupName already exists" }
    }

    logGroups.add(
        LogGroupStub(
            logGroupName = logGroupName,
            logGroupArn = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:$logGroupName"
        )
    )

    return CreateLogGroupResponse {}
  }

  override suspend fun describeLogGroups(input: DescribeLogGroupsRequest): DescribeLogGroupsResponse {
    // Filter by prefix if provided
    val filteredLogGroups = if (input.logGroupNamePrefix != null) {
      logGroups.filter { it.logGroupName.startsWith(input.logGroupNamePrefix!!) }
    } else {
      logGroups
    }

    val startIndex = input.nextToken?.toIntOrNull() ?: 0
    val limit = input.limit ?: 50
    val page = filteredLogGroups.drop(startIndex).take(limit)

    val nextToken = if (startIndex + limit < filteredLogGroups.size) {
      (startIndex + limit).toString()
    } else null

    return DescribeLogGroupsResponse {
      this.logGroups = page.map { stub ->
        LogGroup {
          logGroupName = stub.logGroupName
          arn = stub.logGroupArn
        }
      }
      this.nextToken = nextToken
    }
  }

  override suspend fun deleteLogGroup(input: DeleteLogGroupRequest): DeleteLogGroupResponse {
    val logGroup = findLogGroup(input.logGroupName)
    logGroups.remove(logGroup)
    return DeleteLogGroupResponse {}
  }

  override fun close() {
    // No-op for stub
  }
}

