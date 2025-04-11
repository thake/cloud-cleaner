package cloudcleaner.aws.resources.cloudformation

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.DeleteStackRequest
import aws.sdk.kotlin.services.cloudformation.model.DeleteStackResponse
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksRequest
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksResponse
import aws.sdk.kotlin.services.cloudformation.model.Export
import aws.sdk.kotlin.services.cloudformation.model.ListExportsRequest
import aws.sdk.kotlin.services.cloudformation.model.ListExportsResponse
import aws.sdk.kotlin.services.cloudformation.model.ListImportsRequest
import aws.sdk.kotlin.services.cloudformation.model.ListImportsResponse
import aws.sdk.kotlin.services.cloudformation.model.ListStackResourcesRequest
import aws.sdk.kotlin.services.cloudformation.model.ListStackResourcesResponse
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.Stack
import aws.sdk.kotlin.services.cloudformation.model.StackResourceSummary
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import aws.sdk.kotlin.services.cloudformation.model.UpdateTerminationProtectionRequest
import aws.sdk.kotlin.services.cloudformation.model.UpdateTerminationProtectionResponse
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CloudFormationClientStub(
  val delegate: CloudFormationClient = mockk<CloudFormationClient>(),
) : CloudFormationClient by delegate {
  val stacks = mutableListOf<StackStub>()
  val undeletableResources = mutableListOf<String>()
  var deleteFailsWithError = false

  fun getStackOrNull(stackName: String): StackStub? = stacks.find { it.stackName == stackName }
  fun getActiveStackOrNull(stackName: String) =
      getStackOrNull(stackName)?.takeIf { it.stackStatus != StackStatus.DeleteComplete }

  fun getActiveStack(stackName: String): StackStub =
      getActiveStackOrNull(stackName) ?: throw CloudFormationException("Stack with id $stackName does not exist")

  data class StackStub(
    val stackName: String,
    val resources: MutableMap<String, StackResource> = mutableMapOf(),
    var stackStatus: StackStatus = StackStatus.CreateComplete,
    val roleArn: String? = null,
    val exports: Set<String> = emptySet(),
    val imports: Set<String> = emptySet(),
    val parentId: String? = null,
    var enableTerminationProtection: Boolean = false,
  )

  data class StackResource(
    val logicalId: String,
    val resourceStatus: ResourceStatus = ResourceStatus.CreateComplete,
    val physicalId: String = logicalId,
    val resourceType: String? = null,
  )

  override suspend fun describeStacks(input: DescribeStacksRequest): DescribeStacksResponse {
    val stackName = input.stackName
    val activeStacks = if (stackName != null) {
      listOf(getActiveStack(stackName))
    } else {
      stacks.dropWhile { input.nextToken != null && input.nextToken != it.stackName }
          .filter { it.stackStatus != StackStatus.DeleteComplete }
    }
    val page = activeStacks.take(25)
    val nextToken = activeStacks.getOrNull(25)?.stackName
    return DescribeStacksResponse {
      this.stacks = page.map {
        Stack {
          this.stackName = it.stackName
          this.stackStatus = it.stackStatus
          this.enableTerminationProtection = it.enableTerminationProtection
          this.roleArn = it.roleArn
          this.parentId = it.parentId
        }
      }
      this.nextToken = nextToken
    }
  }

  override suspend fun listStackResources(input: ListStackResourcesRequest): ListStackResourcesResponse {
    val stack = getActiveStack(input.stackName!!)
    return ListStackResourcesResponse {
      stackResourceSummaries =
          stack.resources.values.map {
            StackResourceSummary {
              logicalResourceId = it.logicalId
              resourceStatus = it.resourceStatus
              physicalResourceId = it.physicalId
              resourceType = it.resourceType
            }
          }
    }
  }

  override suspend fun deleteStack(input: DeleteStackRequest): DeleteStackResponse {
    if (deleteFailsWithError) {
      throw CloudFormationException("Delete stack failed")
    }
    val stackName = input.stackName!!
    val stack = getActiveStack(stackName)
    val resourcesToRetain = input.retainResources?.toSet() ?: emptySet()
    val newResourceStates = stack.resources.mapValues {
      val resource = it.value
      val newStatus = when (resource.logicalId) {
        in resourcesToRetain -> ResourceStatus.DeleteSkipped
        in undeletableResources -> ResourceStatus.DeleteFailed
        else -> ResourceStatus.DeleteComplete
      }
      resource.copy(resourceStatus = newStatus)
    }
    stack.resources.putAll(newResourceStates)
    if (stack.resources.values.any { it.resourceStatus == ResourceStatus.DeleteFailed }) {
      transitionStackStatus(stackName, StackStatus.DeleteInProgress, StackStatus.DeleteFailed)
    } else {
      transitionStackStatus(stackName, StackStatus.DeleteInProgress, StackStatus.DeleteComplete)
    }
    return DeleteStackResponse {}
  }

  suspend fun transitionStackStatus(
    stackName: String,
    stateDuringTransition: StackStatus,
    transitTo: StackStatus,
    duration: Duration = 30.minutes
  ) {
    val stack = getActiveStack(stackName)
    stack.stackStatus = stateDuringTransition
    val scope = (coroutineContext.job as? TestScope)?.backgroundScope ?: CoroutineScope(Dispatchers.Default)
    scope.launch {
      delay(duration)
      stack.stackStatus = transitTo
    }
  }

  override suspend fun listExports(input: ListExportsRequest): ListExportsResponse {
    return ListExportsResponse {
      exports = stacks.flatMap { stack ->
        stack.exports.map { exportName ->
          Export {
            exportingStackId = generateStackIdFromStackName(stack.stackName, "000000000000", "eu-north-1")
            name = exportName
          }
        }
      }
    }
  }

  override suspend fun listImports(input: ListImportsRequest): ListImportsResponse {
    val export = input.exportName!!
    val stacksImportingExport = stacks.filter { stack -> export in stack.imports }.map { it.stackName }
    if (stacksImportingExport.isEmpty()) {
      throw CloudFormationException("Export '$export' is not imported by any stack.")
    } else {
      return ListImportsResponse {
        imports = stacksImportingExport
      }
    }
  }

  override suspend fun updateTerminationProtection(input: UpdateTerminationProtectionRequest): UpdateTerminationProtectionResponse {
    val stack = getActiveStack(input.stackName!!)
    stack.enableTerminationProtection = input.enableTerminationProtection == true
    return UpdateTerminationProtectionResponse {
      this.stackId = generateStackIdFromStackName(stack.stackName)
    }
  }
}

fun Resources(vararg resources: CloudFormationClientStub.StackResource) =
    resources.associateBy { it.logicalId }.toMutableMap()

@OptIn(ExperimentalUuidApi::class)
fun generateStackIdFromStackName(stackName: String, accountId: String = ACCOUNT_ID, region: String = REGION): String {
  return "arn:aws:cloudformation:$region:$accountId:stack/$stackName/${Uuid.random().toHexString()}"

}
