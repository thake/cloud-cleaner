package cloudcleaner.aws.resources.lambda

import aws.sdk.kotlin.services.lambda.model.Runtime
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.cloudwatch.CloudWatchLogGroupName
import cloudcleaner.aws.resources.ec2.VpcId
import cloudcleaner.aws.resources.iam.IamRoleName
import cloudcleaner.aws.resources.lambda.LambdaClientStub.FunctionStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class LambdaFunctionScannerTest {
  private val lambdaClient = LambdaClientStub()
  private val underTest = LambdaFunctionScanner(lambdaClient, REGION)

  @Test
  fun `scan should return empty list when no functions are present`() = runTest {
    val functions = underTest.scan()
    // then
    functions.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of functions`() = runTest {
    // given
    repeat(10) {
      lambdaClient.functions.add(FunctionStub(functionName = "function$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(10)
    actualFunctions.map { it.name }.shouldContainExactlyInAnyOrder(
      (0..9).map { "function$it" }
    )
  }

  @Test
  fun `scan should include function ARN`() = runTest {
    // given
    lambdaClient.functions.add(FunctionStub("test-function"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    actualFunctions.first().functionArn?.value.shouldBe(
      "arn:aws:lambda:$REGION:$ACCOUNT_ID:function:test-function"
    )
  }

  @Test
  fun `scan should include runtime`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub("test-function", runtime = Runtime.Python312)
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    actualFunctions.first().runtime.shouldBe("python3.12")
  }

  @Test
  fun `scan should handle large number of functions with pagination`() = runTest {
    // given
    repeat(150) {
      lambdaClient.functions.add(FunctionStub(functionName = "function$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(150)
  }

  @Test
  fun `scan should set correct resource type`() = runTest {
    // given
    lambdaClient.functions.add(FunctionStub("test-function"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    actualFunctions.first().type.shouldBe("LambdaFunction")
  }

  @Test
  fun `scan should extract IAM role dependency from function configuration`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "test-function",
        role = "arn:aws:iam::$ACCOUNT_ID:role/my-lambda-role"
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    val dependencies = actualFunctions.first().dependsOn
    dependencies.shouldContainExactlyInAnyOrder(
      IamRoleName("my-lambda-role"),
      CloudWatchLogGroupName("/aws/lambda/test-function", REGION)
    )
  }

  @Test
  fun `scan should add CloudWatch log group dependency`() = runTest {
    // given
    lambdaClient.functions.add(FunctionStub("my-function"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    val dependencies = actualFunctions.first().dependsOn
    dependencies.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/my-function", REGION)
    )
  }

  @Test
  fun `scan should handle getFunction failure gracefully`() = runTest {
    // given
    lambdaClient.functions.add(FunctionStub("test-function"))
    lambdaClient.getFunctionFailsWithError = true
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    // Function should still be emitted but without dependencies
    actualFunctions.first().dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle role ARN with service-role path`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "test-function",
        role = "arn:aws:iam::$ACCOUNT_ID:role/service-role/my-service-role"
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    val dependencies = actualFunctions.first().dependsOn
    dependencies.shouldContainExactlyInAnyOrder(
      IamRoleName("my-service-role"),
      CloudWatchLogGroupName("/aws/lambda/test-function", REGION)
    )
  }

  @Test
  fun `scan should handle multiple functions with different dependencies`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "function1",
        role = "arn:aws:iam::$ACCOUNT_ID:role/role1"
      )
    )
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "function2",
        role = "arn:aws:iam::$ACCOUNT_ID:role/role2"
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(2)

    val function1 = actualFunctions.first { it.name == "function1" }
    function1.dependsOn.shouldContainExactlyInAnyOrder(
      IamRoleName("role1"),
      CloudWatchLogGroupName("/aws/lambda/function1", REGION)
    )

    val function2 = actualFunctions.first { it.name == "function2" }
    function2.dependsOn.shouldContainExactlyInAnyOrder(
      IamRoleName("role2"),
      CloudWatchLogGroupName("/aws/lambda/function2", REGION)
    )
  }

  @Test
  fun `scan should extract VPC dependency when function is in VPC`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "vpc-function",
        vpcId = "vpc-12345"
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    val dependencies = actualFunctions.first().dependsOn
    dependencies.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/vpc-function", REGION),
        VpcId("vpc-12345", REGION)
    )
  }

  @Test
  fun `scan should not have VPC dependency when function is not in VPC`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "non-vpc-function",
        vpcId = null
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(1)
    val dependencies = actualFunctions.first().dependsOn
    dependencies.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/non-vpc-function", REGION)
    )
  }

  @Test
  fun `scan should handle multiple functions with mixed VPC configurations`() = runTest {
    // given
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "vpc-function",
        vpcId = "vpc-11111"
      )
    )
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "non-vpc-function",
        vpcId = null
      )
    )
    lambdaClient.functions.add(
      FunctionStub(
        functionName = "another-vpc-function",
        vpcId = "vpc-22222"
      )
    )
    // when
    val actualFlow = underTest.scan()
    // then
    val actualFunctions = actualFlow.toList()
    actualFunctions.shouldHaveSize(3)

    val vpcFunc1 = actualFunctions.first { it.name == "vpc-function" }
    vpcFunc1.dependsOn.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/vpc-function", REGION),
      VpcId("vpc-11111", REGION)
    )

    val nonVpcFunc = actualFunctions.first { it.name == "non-vpc-function" }
    nonVpcFunc.dependsOn.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/non-vpc-function", REGION)
    )

    val vpcFunc2 = actualFunctions.first { it.name == "another-vpc-function" }
    vpcFunc2.dependsOn.shouldContainExactlyInAnyOrder(
      IamRoleName("lambda-execution-role"),
      CloudWatchLogGroupName("/aws/lambda/another-vpc-function", REGION),
      VpcId("vpc-22222", REGION)
    )
  }
}

