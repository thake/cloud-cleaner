package cloudcleaner.aws.resources.cloudformation

import aws.sdk.kotlin.services.cloudformation.deleteStack
import aws.sdk.kotlin.services.cloudformation.model.DescribeStacksRequest
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import cloudcleaner.aws.resources.cloudformation.CloudFormationClientStub.StackResource
import cloudcleaner.aws.resources.cloudformation.CloudFormationClientStub.StackStub
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CloudFormationClientExtKtTest {

  @Test
  fun `deleteStackAndRetainAllUndeletedResources should delete stack with not deleted resources retained - stubbed`() =
      runTest {
        val cloudFormationClient = CloudFormationClientStub()
        val stackName = "stackName"
        cloudFormationClient.undeletableResources.add("LogicalResourceId2")
        cloudFormationClient.stacks.add(
            StackStub(
                stackName,
                resources = Resources(StackResource("DeletableResource"), StackResource("LogicalResourceId2")),
            ),
        )
        // when
        cloudFormationClient.deleteStack {
          this.stackName = stackName
        }
        cloudFormationClient.deleteStackAndRetainAllUndeletedResources(stackName)
        // then
        val stack = cloudFormationClient.getStackOrNull(stackName)
        stack.shouldNotBeNull()
        stack.stackStatus.shouldBe(StackStatus.DeleteComplete)
        val retainedResource = stack.resources.values.find { it.logicalId == "LogicalResourceId2" }
        retainedResource.shouldNotBeNull()
        retainedResource.resourceStatus.shouldBe(ResourceStatus.DeleteSkipped)
      }

  @Test
  fun `waitUntilStackDeleteCompleteFixed should correctly handle if stack no longer exists`() = runTest {
    val cloudFormationClient = CloudFormationClientStub()
    val stackName = "stackName"
    cloudFormationClient.stacks.add(StackStub(stackName))
    cloudFormationClient.deleteStack { this.stackName = stackName }
    // when & then
    // TODO file bug in aws kotlin sdk
    cloudFormationClient.waitUntilStackDeleteCompleteFixed(DescribeStacksRequest { this.stackName = stackName })
  }

  @Test
  fun `getStackDescription should correctly detect a deleted stack`() = runTest {
    val cloudFormationClient = CloudFormationClientStub()
    cloudFormationClient.getStackDescription("doesNotExist").shouldBe(StackDescription(StackStatus.DeleteComplete))
  }

  @Test
  fun `getStackDescription should correctly detect a termination protection`() = runTest {
    val cloudFormationClient = CloudFormationClientStub()
    cloudFormationClient.stacks.add(
        StackStub(
            "stackName",
            stackStatus = StackStatus.UpdateFailed,
            enableTerminationProtection = true,
        ),
    )
    cloudFormationClient.getStackDescription("stackName").shouldBe(StackDescription(StackStatus.UpdateFailed, true))
  }

  @Test
  fun `exportDependencyMap should correctly map dependencies`() = runTest {
    val cloudFormationClient = CloudFormationClientStub()
    cloudFormationClient.stacks.add(StackStub(stackName = "stackWithExport", exports = setOf("export1", "export2")))
    cloudFormationClient.stacks.add(StackStub(stackName = "stackWithExport2", exports = setOf("export3")))
    cloudFormationClient.stacks.add(
        StackStub(
            stackName = "stackWithImport",
            imports = setOf("export1", "export3"),
        ),
    )
    cloudFormationClient.stacks.add(StackStub(stackName = "stackWithImport2", imports = setOf("export1")))

    // when
    val actual = cloudFormationClient.exportDependencyMap()
    // then
    actual.shouldBe(
        mapOf(
            "stackWithImport" to setOf("stackWithExport", "stackWithExport2"),
            "stackWithImport2" to setOf("stackWithExport"),
        ),
    )
  }
}
