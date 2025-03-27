package awspurge.resources.aws

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.deleteStack
import aws.sdk.kotlin.services.cloudformation.describeStacks
import aws.sdk.kotlin.services.cloudformation.listStackResources
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksResponse
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.StackNotFoundException
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import aws.sdk.kotlin.services.cloudformation.paginators.describeStacksPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listExportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listImportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listStackResourcesPaginated
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackCreateComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackDeleteComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackUpdateComplete
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.retries.Outcome
import aws.smithy.kotlin.runtime.retries.getOrThrow
import awspurge.resources.Id
import awspurge.resources.Resource
import awspurge.resources.ResourceDefinition
import awspurge.resources.ResourceDeleter
import awspurge.resources.ResourceScanner
import awspurge.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration.Companion.milliseconds


val logger = KotlinLogging.logger {}
typealias StackName = StringId

private const val MAX_ATTEMPTS = 3
private const val concurrencyResourceLookup = 3
fun createCloudFormationStackResource(connectionInformation: AwsConnectionInformation): ResourceDefinition<CloudFormationStack> {
    val client = CloudFormationClient {
        credentialsProvider = connectionInformation.credentialsProvider
        region = connectionInformation.region
        retryStrategy {
            maxAttempts = 99
            delayProvider {
                initialDelay = 400.milliseconds
            }
        }
    }
    return ResourceDefinition(
        resourceDeleter = CloudFormationStackDeleter(client),
        resourceScanner = CloudFormationStackScanner(client),
        close = { client.close() }
    )
}

class CloudFormationStackScanner(
    private val cloudFormationClient: CloudFormationClient
) : ResourceScanner<CloudFormationStack> {
    override fun scan(): Flow<CloudFormationStack> =
        flow {
            val outputDependencyMap = cloudFormationClient.outputDependencyMap()
            cloudFormationClient.describeStacksPaginated().collect { response ->
                response.stacks
                    ?.filter { it.stackStatus != StackStatus.DeleteComplete }
                    ?.forEach { stack ->
                        val stackName = stack.stackName?.let { StackName(it) } ?: return@forEach
                        val contains = cloudFormationClient.listStackResources {
                            this.stackName = stackName.value
                        }
                            .stackResourceSummaries
                            ?.mapNotNull { it.physicalResourceId }
                            ?.map { StringId(it) }
                            ?.toSet() ?: emptySet()
                        val dependencies: Set<Id> =
                            setOfNotNull(stack.roleArn?.let { StringId(it) }) + outputDependencyMap.getOrElse(
                                stackName
                            ) { emptySet() }
                        emit(
                            CloudFormationStack(
                                stackName = stackName,
                                contains = contains,
                                dependsOn = dependencies
                            )
                        )

                    }
            }
        }
}
fun extractStackNameFromStackId(stackId: String): String {
    val stackInfo = stackId.substringAfter(":stack/")
    return stackInfo.substringBefore("/")
}
suspend fun CloudFormationClient.outputDependencyMap(): Map<StackName, Set<StackName>> {
    data class Export(val name: String, val stackName: StackName)

    val exports = listExportsPaginated()
        .transform { it.exports?.forEach { obj -> this.emit(obj) } }
        .mapNotNull {
            val name = it.name
            val stackId = it.exportingStackId
            if (name != null && stackId != null) {
                Export(name, StackName(extractStackNameFromStackId(stackId)))
            } else {
                null
            }
        }
        .toCollection(mutableListOf())
    val groupingBy = exports
        .map { export ->
            val dependentStacks = try {
                listImportsPaginated {
                    exportName = export.name
                }
                    .transform { it.imports?.forEach { obj -> this.emit(obj) } }
                    .toCollection(mutableSetOf())
            } catch (e: CloudFormationException) {
                if (e.message.contains("is not imported by any stack.")) {
                    mutableSetOf()
                } else {
                    throw e
                }
            }
            dependentStacks.map { stackName ->
                StackName(stackName) to export.stackName
            }
        }
        .flatten()
        .groupBy({ it.first }) { it.second }
        .mapValues { it.value.toSet() }
    return groupingBy
}

class CloudFormationStackDeleter(
    val cloudFormationClient: CloudFormationClient
) : ResourceDeleter {
    override suspend fun delete(resource: Resource) {
        val stack =
            resource as? CloudFormationStack ?: throw IllegalArgumentException("Resource not a CloudFormationStack")
        var attempt = 0
        var retrying = true
        while (retrying) {
            attempt++
            retrying = attempt < MAX_ATTEMPTS
            try {
                doDelete(stack).getOrThrow()
            } catch (e: Exception) {
                if (retrying) {
                    logger.info(e) { "CloudFormation stack $resource deletion failed. Retrying. Error: ${e.message}" }
                } else {
                    logger.error { "CloudFormation stack $resource deletion failed. Skipping resource. Error: ${e.message}" }
                }
            }
        }
    }

    private suspend fun doDelete(stack: CloudFormationStack): Outcome<DescribeStacksResponse> {
        val currentStatus = try {
            cloudFormationClient.describeStacks {
                stackName = stack.stackName.value
            }.stacks?.firstOrNull()?.stackStatus ?: StackStatus.DeleteComplete
        } catch (e: StackNotFoundException) {
            StackStatus.DeleteComplete
        }
        return when (currentStatus) {
            StackStatus.DeleteInProgress -> cloudFormationClient.waitUntilStackDeleteComplete {
                stackName = stack.stackName.value
            }

            StackStatus.DeleteFailed -> {
                logger.atWarn {
                    message =
                        "Deletion of Cloudformation Stack $stack failed. Attempting to delete stack while retaining failed resources."
                }
                val resourcesToRetain = mutableListOf<String>()
                cloudFormationClient.listStackResourcesPaginated {
                    stackName = stack.stackName.value
                }.collect {
                    it.stackResourceSummaries?.filter {
                        it.resourceStatus != ResourceStatus.DeleteComplete
                    }?.mapNotNull { it.logicalResourceId }?.forEach {
                        resourcesToRetain.add(it)
                    }
                }
                cloudFormationClient.deleteStack {
                    stackName = stack.stackName.value
                    retainResources = resourcesToRetain
                }
                cloudFormationClient.waitUntilStackDeleteComplete {
                    stackName = stack.stackName.value
                }
            }

            else -> {
                waitForRunningOperationsToFinish(stack, currentStatus)
                cloudFormationClient.deleteStack {
                    stackName = stack.stackName.value
                }
                cloudFormationClient.waitUntilStackDeleteComplete {
                    stackName = stack.stackName.value
                }
            }
        }


    }

    private suspend fun waitForRunningOperationsToFinish(stack: CloudFormationStack, currentStatus: StackStatus) {
        when (currentStatus) {
            StackStatus.UpdateInProgress,
            StackStatus.UpdateRollbackInProgress,
            StackStatus.UpdateRollbackCompleteCleanupInProgress -> {
                logger.atInfo {
                    message = "CloudFormationStack $stack update in progress. Waiting for operation to finish."
                }
                cloudFormationClient.waitUntilStackUpdateComplete {
                    stackName = stack.stackName.value
                }.getOrThrow()
            }

            StackStatus.CreateInProgress,
            StackStatus.RollbackInProgress -> {
                logger.atInfo {
                    message = "CloudFormationStack $stack create in progress. Waiting for operation to finish."
                }
                cloudFormationClient.waitUntilStackCreateComplete {
                    stackName = stack.stackName.value
                }.getOrThrow()
            }

            else -> Unit
        }
    }
}

class CloudFormationStack(
    val stackName: StackName,
    override val contains: Set<Id>,
    override val dependsOn: Set<Id>
) : Resource {
    override val id: Id
        get() = stackName
    override val type: String = "AWS::CloudFormation::Stack"

    override fun toString() = stackName.value
}