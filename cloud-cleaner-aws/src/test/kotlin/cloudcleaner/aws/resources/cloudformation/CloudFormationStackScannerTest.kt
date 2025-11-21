package cloudcleaner.aws.resources.cloudformation

import aws.sdk.kotlin.services.cloudformation.model.StackStatus
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.cloudformation.CloudFormationClientStub.StackResource
import cloudcleaner.aws.resources.cloudformation.CloudFormationClientStub.StackStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test


class CloudFormationStackScannerTest {
  private val cloudFormationClient = CloudFormationClientStub()
  private val underTest = CloudFormationStackScanner(cloudFormationClient, ACCOUNT_ID, REGION)

  @Test
  fun `scan should return empty list when no stacks are present`() = runTest {
    val stacks = underTest.scan()
    // then
    stacks.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated stacks`() = runTest {
    repeat(100) {
      cloudFormationClient.stacks.add(StackStub(stackName = "stackName$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualStacks = actualFlow.toList()
    actualStacks.shouldHaveSize(100)
  }

  @Test
  fun `scan should not return deleted stacks`() = runTest {
    // given
    val stacks = listOf(
        StackStub("stackName1", stackStatus = StackStatus.DeleteComplete),
        StackStub("stackName2", stackStatus = StackStatus.CreateComplete),
    )
    cloudFormationClient.stacks.addAll(stacks)
    // when
    val actualFlow = underTest.scan()
    // then
    val actualStacks = actualFlow.toList()
    actualStacks.shouldContainOnly(CloudFormationStack(StackName("stackName2", REGION), emptySet(), emptySet()))
  }

  @Test
  fun `scan should return stacks with dependencies`() = runTest {
    val nestedStackArn = generateStackIdFromStackName("nestedStack")
    val roleArn = "arn:aws:iam::$ACCOUNT_ID:role/roleName"
    val stacks = listOf(
        StackStub("exporting", exports = setOf("export1", "unused")),
        StackStub("exportingAndImporting", exports = setOf("export2"), imports = setOf("export1")),
        StackStub("importing1", imports = setOf("export1", "export2")),
        StackStub(
            stackName = "importing2",
            imports = setOf("export2"),
            resources = Resources(
                StackResource(
                    logicalId = "roleName",
                    physicalId = "roleName",
                    resourceType = "AWS::IAM::Role",
                ),
            ),
        ),
        StackStub(
            stackName = "usingRole",
            roleArn = roleArn,
            resources = Resources(
                StackResource(
                    logicalId = "nestedStack",
                    physicalId = nestedStackArn,
                    resourceType = "AWS::CloudFormation::Stack",
                ),
            ),
        ),
        StackStub(
            stackName = "nestedStack",
            parentId = generateStackIdFromStackName("usingRole"),
        ),
    )
    cloudFormationClient.stacks.addAll(stacks)
    val actualFlow = underTest.scan()

    // then
    val actualStacks = actualFlow.toList()
    actualStacks.shouldContainExactlyInAnyOrder(
        CloudFormationStack(
            stackName = StackName("exporting", REGION),
            dependsOn = emptySet(),
            containedResources = emptySet(),
        ),
        CloudFormationStack(
            stackName = StackName("exportingAndImporting", REGION),
            dependsOn = setOf(
                StackName("exporting", REGION),
            ),
            containedResources = emptySet(),
        ),
        CloudFormationStack(
            stackName = StackName("importing1", REGION),
            dependsOn = setOf(
                StackName("exporting", REGION), StackName("exportingAndImporting", REGION),
            ),
            containedResources = emptySet(),
        ),
        CloudFormationStack(
            stackName = StackName("importing2", REGION),
            dependsOn = setOf(
                StackName("exportingAndImporting", REGION),
            ),
            containedResources = setOf(
                Arn(roleArn),
            ),
        ),
        CloudFormationStack(
            stackName = StackName("usingRole", REGION),
            dependsOn = setOf(
                Arn(roleArn),
            ),
            containedResources = setOf(
                StackName("nestedStack", REGION),
            ),
        ),
        CloudFormationStack(
            stackName = StackName("nestedStack", REGION),
            dependsOn = setOf(
                StackName("usingRole", REGION),
            ),
            containedResources = emptySet(),
        ),
    )
  }
}
