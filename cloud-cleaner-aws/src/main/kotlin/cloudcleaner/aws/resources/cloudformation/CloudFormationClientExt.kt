package cloudcleaner.aws.resources.cloudformation

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.deleteStack
import aws.sdk.kotlin.services.cloudformation.describeStacks
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksRequest
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksResponse
import aws.sdk.kotlin.services.cloudformation.model.ListImportsRequest
import aws.sdk.kotlin.services.cloudformation.model.ListImportsResponse
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.StackNotFoundException
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import aws.sdk.kotlin.services.cloudformation.paginators.listExportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listImportsPaginated
import aws.sdk.kotlin.services.cloudformation.paginators.listStackResourcesPaginated
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackCreateComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackDeleteComplete
import aws.sdk.kotlin.services.cloudformation.waiters.waitUntilStackUpdateComplete
import aws.smithy.kotlin.runtime.retries.Outcome
import aws.smithy.kotlin.runtime.retries.StandardRetryStrategy
import aws.smithy.kotlin.runtime.retries.delay.InfiniteTokenBucket
import aws.smithy.kotlin.runtime.retries.getOrThrow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration.Companion.seconds

data class StackDescription(val stackStatus: StackStatus, val enableTerminationProtection: Boolean = false)

suspend fun CloudFormationClient.deleteStackAndRetainAllUndeletedResources(
  stackName: String
): Outcome<DescribeStacksResponse> {
  val resourcesToRetain = mutableListOf<String>()

  listStackResourcesPaginated { this.stackName = stackName }
      .collect { response ->
        response.stackResourceSummaries
            ?.filter { it.resourceStatus != ResourceStatus.DeleteComplete }
            ?.mapNotNull { it.logicalResourceId }
            ?.forEach { resourcesToRetain.add(it) }
      }
  deleteStack {
    this.stackName = stackName
    retainResources = resourcesToRetain
  }
  return waitUntilStackDeleteCompleteFixed(DescribeStacksRequest { this.stackName = stackName })
}

suspend fun CloudFormationClient.waitForRunningOperationsToFinish(stackName: String, currentStatus: StackStatus) {
  when (currentStatus) {
    StackStatus.UpdateInProgress,
    StackStatus.UpdateRollbackInProgress,
    StackStatus.UpdateRollbackCompleteCleanupInProgress -> {
      logger.atInfo { message = "CloudFormationStack $stackName update in progress. Waiting for operation to finish." }
      waitUntilStackUpdateComplete { this.stackName = stackName }.getOrThrow()
    }

    StackStatus.CreateInProgress,
    StackStatus.RollbackInProgress -> {
      logger.atInfo { message = "CloudFormationStack $stackName create in progress. Waiting for operation to finish." }
      waitUntilStackCreateComplete { this.stackName = stackName }.getOrThrow()
    }

    else -> Unit
  }
}

suspend fun CloudFormationClient.waitUntilStackDeleteCompleteFixed(
  request: DescribeStacksRequest,
  retryStrategy: StandardRetryStrategy = StandardRetryStrategy {
    maxAttempts = 80
    tokenBucket = InfiniteTokenBucket
    delayProvider {
      initialDelay = 30.seconds
      scaleFactor = 1.5
      jitter = 1.0
      maxBackoff = 60.seconds
    }
  },
): Outcome<DescribeStacksResponse> {
  return try {
    this.waitUntilStackDeleteComplete(request, retryStrategy)
  } catch (e: CloudFormationException) {
    if (isStackNotFound(e)) {
      Outcome.Response(attempts = 1, DescribeStacksResponse { stacks = emptyList() })
    } else {
      throw e
    }
  }
}

suspend fun CloudFormationClient.getStackDescription(stackName: String) =
    try {
      describeStacks { this.stackName = stackName }
          .stacks
          ?.firstOrNull()
          ?.let {
            val stackStatus = it.stackStatus ?: return@let null
            val enableTerminationProtection = it.enableTerminationProtection == true
            StackDescription(stackStatus, enableTerminationProtection)
          } ?: StackDescription(StackStatus.DeleteComplete)
    } catch (e: CloudFormationException) {
      if (isStackNotFound(e)) {
        StackDescription(StackStatus.DeleteComplete)
      } else {
        throw e
      }
    }

fun isStackNotFound(e: CloudFormationException) =
    e is StackNotFoundException || (e.message.contains("Stack with id") && e.message.contains("does not exist"))

/**
 * Returns a map that contains the names of the stacks which depend on other stacks based on exports.
 */
suspend fun CloudFormationClient.exportDependencyMap(): Map<String, Set<String>> {
  data class Export(val name: String, val stackName: String)

  val exports =
      listExportsPaginated()
          .transform { it.exports?.forEach { obj -> this.emit(obj) } }
          .mapNotNull {
            val name = it.name
            val stackId = it.exportingStackId
            if (name != null && stackId != null) {
              Export(name, extractStackNameFromStackId(stackId))
            } else {
              null
            }
          }
          .toCollection(mutableListOf())
  val groupingBy =
      exports
          .map { export ->
            val dependentStacks =
                listImportsPaginatedHandlingNotImported { exportName = export.name }
                    .transform { it.imports?.forEach { obj -> this.emit(obj) } }
                    .toCollection(mutableSetOf())

            dependentStacks.map { stackName -> stackName to export.stackName }
          }
          .flatten()
          .groupBy({ it.first }) { it.second }
          .mapValues { it.value.toSet() }
  return groupingBy
}

fun CloudFormationClient.listImportsPaginatedHandlingNotImported(
  block: ListImportsRequest.Builder.() -> Unit
): Flow<ListImportsResponse> {
  return listImportsPaginated(block).catch { e ->
    if (!(e is CloudFormationException && e.message.contains("is not imported by any stack."))) {
      throw e
    }
  }
}

fun extractStackNameFromStackId(stackId: String): String {
  val stackInfo = stackId.substringAfter(":stack/")
  return stackInfo.substringBefore("/")
}
