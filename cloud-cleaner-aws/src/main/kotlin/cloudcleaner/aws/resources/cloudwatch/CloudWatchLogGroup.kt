package cloudcleaner.aws.resources.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.deleteLogGroup
import aws.sdk.kotlin.services.cloudwatchlogs.model.ResourceNotFoundException
import aws.sdk.kotlin.services.cloudwatchlogs.paginators.describeLogGroupsPaginated
import cloudcleaner.aws.resources.Arn
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

val cloudWatchLogGroupLogger = KotlinLogging.logger {}

data class LogGroupName(val value: String) : Id {
  override fun toString() = value
}

private const val TYPE = "LogGroup"

data class CloudWatchLogGroup(
    val logGroupName: LogGroupName,
    val logGroupArn: Arn,
) : Resource {
  override val id: Id = logGroupName
  override val name: String = logGroupName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()
  override val dependsOn: Set<Id> = emptySet()
  override val containedResources: Set<Id> = emptySet()

  override fun toString() = name
}

class CloudWatchLogGroupResourceDefinitionFactory : AwsResourceDefinitionFactory<CloudWatchLogGroup> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<CloudWatchLogGroup> {
    val client = CloudWatchLogsClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = CloudWatchLogGroupDeleter(client),
        resourceScanner = CloudWatchLogGroupScanner(client),
        close = { client.close() },
    )
  }
}

class CloudWatchLogGroupScanner(private val cloudWatchLogsClient: CloudWatchLogsClient) :
    ResourceScanner<CloudWatchLogGroup> {
  override fun scan(): Flow<CloudWatchLogGroup> = flow {
    cloudWatchLogsClient.describeLogGroupsPaginated {}.collect { response ->
      response.logGroups?.forEach { logGroup ->
        val logGroupName = logGroup.logGroupName ?: return@forEach
        val logGroupArn = logGroup.arn?: return@forEach

        emit(
            CloudWatchLogGroup(
                logGroupName = LogGroupName(logGroupName),
                logGroupArn = Arn(logGroupArn),
            ),
        )
      }
    }
  }
}

class CloudWatchLogGroupDeleter(private val cloudWatchLogsClient: CloudWatchLogsClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val logGroup =
        resource as? CloudWatchLogGroup
            ?: throw IllegalArgumentException("Resource not a CloudWatchLogGroup")

    try {
      cloudWatchLogsClient.deleteLogGroup { logGroupName = logGroup.logGroupName.value }
    } catch (_: ResourceNotFoundException) {
      cloudWatchLogGroupLogger.debug {
        "Deletion failed because CloudWatch log group ${logGroup.logGroupName} already has been deleted."
      }
    } catch (e: Exception) {
      cloudWatchLogGroupLogger.error(e) {
        "Failed to delete CloudWatch log group ${logGroup.logGroupName}: ${e.message}"
      }
      throw e
    }
  }
}

