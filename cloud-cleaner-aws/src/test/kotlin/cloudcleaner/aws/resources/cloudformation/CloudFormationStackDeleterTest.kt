package cloudcleaner.aws.resources.cloudformation


import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.ResourceStatus
import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import cloudcleaner.aws.resources.cloudformation.CloudFormationClientStub.StackStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CloudFormationStackDeleterTest {
  private val cloudFormationClient = CloudFormationClientStub()
  private val underTest = CloudFormationStackDeleter(cloudFormationClient)

  @Test
  fun `delete should successfully delete a stack`() = runTest {
    // given
    val stack = CloudFormationStack(
        stackName = StackName("test-stack"),
        containedResources = emptySet(),
        dependsOn = emptySet(),
    )
    cloudFormationClient.stacks.add(StackStub(stack.stackName.value))

    // when
    underTest.delete(stack)

    // then
    cloudFormationClient.getActiveStackOrNull("test-stack").shouldBeNull()
  }

  @Test
  fun `delete should be able to delete stacks by retaining resources`() = runTest {
    // given
    val stack = CloudFormationStack(
        stackName = StackName("stack"),
        containedResources = emptySet(),
        dependsOn = emptySet(),
    )
    cloudFormationClient.stacks.add(
        StackStub(
            "stack",
            resources = Resources(CloudFormationClientStub.StackResource("my-resource")),
        ),
    )
    cloudFormationClient.undeletableResources.add("my-resource")

    // when
    underTest.delete(stack)

    // then
    val actualStack = cloudFormationClient.getStackOrNull("stack")
    actualStack.shouldNotBeNull()
    actualStack.stackStatus.shouldBe(StackStatus.DeleteComplete)
    actualStack.resources["my-resource"]?.resourceStatus.shouldBe(ResourceStatus.DeleteSkipped)
  }

  @Test
  fun `delete should throw error if delete fails`() = runTest {
    // given
    val stack = CloudFormationStack(
        stackName = StackName("failing-stack"),
        containedResources = emptySet(),
        dependsOn = emptySet(),
    )
    cloudFormationClient.stacks.add(StackStub(stack.stackName.value))
    cloudFormationClient.deleteFailsWithError = true

    // when & then
    shouldThrow<CloudFormationException> {
      underTest.delete(stack)
    }
  }

  @Test
  fun `delete should disable termination protection if enabled`() = runTest {
    // given
    val stack = CloudFormationStack(
        stackName = StackName("protected-stack"),
        containedResources = emptySet(),
        dependsOn = emptySet(),
    )
    cloudFormationClient.stacks.add(StackStub(stack.stackName.value, enableTerminationProtection = true))

    // when
    underTest.delete(stack)

    // then
    cloudFormationClient.getActiveStackOrNull("test-stack").shouldBeNull()
  }
}
