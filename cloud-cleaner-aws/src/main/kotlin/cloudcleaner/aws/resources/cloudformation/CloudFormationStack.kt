package cloudcleaner.aws.resources.cloudformation

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.deleteStack
import aws.sdk.kotlin.services.cloudformation.listStackResources
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksRequest
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import aws.sdk.kotlin.services.cloudformation.paginators.describeStacksPaginated
import aws.sdk.kotlin.services.cloudformation.updateTerminationProtection
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackDeleteComplete
import aws.smithy.kotlin.runtime.retries.getOrThrow
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.aws.resources.idFromCloudFormationStackResourceOrNull
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import cloudcleaner.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

typealias StackName = StringId

private const val TYPE = "CloudFormationStack"
private const val MAX_ATTEMPTS = 3

class CloudformationStackResourceDefinitionFactory : AwsResourceDefinitionFactory<CloudFormationStack> {
  override val type: String = TYPE

  override fun createResourceDefinition(
    awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<CloudFormationStack> {
    val client = CloudFormationClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = CloudFormationStackDeleter(client),
        resourceScanner =
            CloudFormationStackScanner(client, awsConnectionInformation.accountId, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class CloudFormationStackScanner(
  private val cloudFormationClient: CloudFormationClient,
  private val accountId: String,
  private val region: String
) : ResourceScanner<CloudFormationStack> {
  override fun scan(): Flow<CloudFormationStack> = flow {
    val outputDependencyMap = cloudFormationClient.exportDependencyMap()
    cloudFormationClient.describeStacksPaginated().collect { response ->
      response.stacks
          ?.filter { it.stackStatus != StackStatus.DeleteComplete }
          ?.forEach { stack ->
            val stackName = stack.stackName?.let { StackName(it) } ?: return@forEach
            val contains =
                cloudFormationClient
                    .listStackResources { this.stackName = stackName.value }
                    .stackResourceSummaries
                    ?.mapNotNull {
                      idFromCloudFormationStackResourceOrNull(
                          stackResourceSummary = it, accountId = accountId, region = region,
                      )
                    }
                    ?.toSet() ?: emptySet()
            val roleDependency = setOfNotNull(stack.roleArn?.let { Arn(it) })
            val exportDependencies: Set<Id> =
                outputDependencyMap.getOrElse(stackName.value) { emptySet() }.map { StackName(it) }.toSet()
            val parentDependency = setOfNotNull(stack.parentId?.let { StackName(extractStackNameFromStackId(it)) })
            val dependencies = roleDependency + exportDependencies + parentDependency
            emit(CloudFormationStack(stackName = stackName, containedResources = contains, dependsOn = dependencies))
          }
    }
  }
}

class CloudFormationStackDeleter(private val cloudFormationClient: CloudFormationClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val stack = resource as? CloudFormationStack ?: throw IllegalArgumentException("Resource not a CloudFormationStack")
    var attempt = 0
    var retrying = true
    while (retrying) {
      attempt++
      retrying = attempt < MAX_ATTEMPTS
      try {
        doDelete(stack)
        retrying = false
      } catch (e: Exception) {
        if (e is CloudFormationException && isStackNotFound(e)) {
          // we are done!
          return
        }
        if (retrying) {
          logger.info(e) { "CloudFormation stack $resource deletion failed. Retrying. Error: ${e.message}" }
        } else {
          throw e
        }
      }
    }
  }

  private suspend fun doDelete(stack: CloudFormationStack) {
    val currentState = cloudFormationClient.getStackDescription(stack.stackName.value)
    when (currentState.stackStatus) {
      StackStatus.DeleteInProgress ->
        cloudFormationClient.waitUntilStackDeleteComplete { stackName = stack.stackName.value }.getOrThrow()

      StackStatus.DeleteFailed -> {
        logger.warn {
          "Deletion of Cloudformation Stack $stack failed. Attempting to delete stack while retaining failed resources."
        }
        cloudFormationClient.deleteStackAndRetainAllUndeletedResources(stack.stackName.value).getOrThrow()
      }

      StackStatus.DeleteComplete -> Unit
      else -> {
        cloudFormationClient.waitForRunningOperationsToFinish(stack.stackName.value, currentState.stackStatus)
        if (currentState.enableTerminationProtection) {
          logger.info { "Disabling termination protection for CloudFormation stack $stack" }
          cloudFormationClient.updateTerminationProtection {
            stackName = stack.stackName.value
            enableTerminationProtection = false
          }
        }
        cloudFormationClient.deleteStack { stackName = stack.stackName.value }
        cloudFormationClient.waitUntilStackDeleteCompleteFixed(
            DescribeStacksRequest {
              stackName = stack.stackName.value
            },
        ).getOrThrow()
      }
    }
  }
}

data class CloudFormationStack(
  val stackName: StackName,
  override val containedResources: Set<Id>,
  override val dependsOn: Set<Id>
) : Resource {
  override val id: Id
    get() = stackName

  override val name: String = stackName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()

  override fun toString() = stackName.value
}
