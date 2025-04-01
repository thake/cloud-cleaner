package cloudcleaner.aws.resources

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.deleteStack
import aws.sdk.kotlin.services.cloudformation.describeStacks
import aws.sdk.kotlin.services.cloudformation.listStackResources
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksRequest
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksResponse
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.StackNotFoundException
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import aws.sdk.kotlin.services.cloudformation.paginators.describeStacksPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listExportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listImportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listStackResourcesPaginated
import aws.sdk.kotlin.services.cloudformation.updateTerminationProtection
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackCreateComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackDeleteComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackUpdateComplete
import aws.smithy.kotlin.runtime.retries.Outcome
import aws.smithy.kotlin.runtime.retries.getOrThrow
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import cloudcleaner.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration.Companion.milliseconds


val logger = KotlinLogging.logger {}
typealias StackName = StringId

private const val TYPE = "CloudFormationStack"
private const val MAX_ATTEMPTS = 3
private const val concurrencyResourceLookup = 3

class CloudformationStackResourceDefinitionFactory : AwsResourceDefinitionFactory<CloudFormationStack> {
    override val type: String = TYPE

    override fun createResourceDefinition(
        connectionInformation: AwsConnectionInformation,
    ): ResourceDefinition<CloudFormationStack> {
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
            type = TYPE,
            resourceDeleter = CloudFormationStackDeleter(client),
            resourceScanner = CloudFormationStackScanner(
                client,
                connectionInformation.accountId,
                connectionInformation.region
            ),
            close = { client.close() }
        )
    }
}

class CloudFormationStackScanner(
    private val cloudFormationClient: CloudFormationClient,
    private val accountId: String,
    private val region: String
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
                            ?.mapNotNull {
                                idFromCloudFormationStackResourceOrNull(
                                    stackResourceSummary = it,
                                    accountId = accountId,
                                    region = region
                                )
                            }
                            ?.toSet() ?: emptySet()
                        val dependencies: Set<Id> =
                            setOfNotNull(stack.roleArn?.let { Arn(it) }) + outputDependencyMap.getOrElse(
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
    private val cloudFormationClient: CloudFormationClient
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
        data class StackState(
            val stackStatus: StackStatus,
            val enableTerminationProtection: Boolean = false
        )

        val currentState = try {
            cloudFormationClient.describeStacks {
                stackName = stack.stackName.value
            }.stacks?.firstOrNull()?.let {
                val stackStatus = it.stackStatus ?: return@let null
                val enableTerminationProtectionstackStatus = it.enableTerminationProtection == true
                StackState(stackStatus, enableTerminationProtectionstackStatus)
            } ?: StackState(StackStatus.DeleteComplete)
        } catch (e: CloudFormationException) {
            if (isStackNotFound(e)) {
                StackState(StackStatus.DeleteComplete)
            } else {
                throw e
            }
        }
        return when (currentState.stackStatus) {
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
                cloudFormationClient.waitUntilStackDeleteCompleteFixed {
                    stackName = stack.stackName.value
                }
            }

            else -> {
                waitForRunningOperationsToFinish(stack, currentState.stackStatus)
                if (currentState.enableTerminationProtection) {
                    logger.info { "Disabling termination protection for CloudFormation stack $stack" }
                    cloudFormationClient.updateTerminationProtection {
                        stackName = stack.stackName.value
                        enableTerminationProtection = false
                    }
                }
                cloudFormationClient.deleteStack {
                    stackName = stack.stackName.value
                }
                cloudFormationClient.waitUntilStackDeleteCompleteFixed {
                    stackName = stack.stackName.value
                }
            }
        }


    }

    private fun isStackNotFound(
        e: CloudFormationException
    ) = e is StackNotFoundException || (e.message.contains("Stack with id") && e.message.contains("does not exist"))

    private suspend fun CloudFormationClient.waitUntilStackDeleteCompleteFixed(
        block: DescribeStacksRequest.Builder.() -> Unit
    ): Outcome<DescribeStacksResponse> {
        return try {
            this.waitUntilStackDeleteComplete(block)
        } catch (e: CloudFormationException) {
            if (isStackNotFound(e)) {
                Outcome.Response(attempts = 1, DescribeStacksResponse { stacks = emptyList() })
            } else {
                throw e
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
    override val name: String = stackName.value
    override val type: String = TYPE
    override val properties: Map<String, String> = emptyMap()

    override fun toString() = stackName.value
}