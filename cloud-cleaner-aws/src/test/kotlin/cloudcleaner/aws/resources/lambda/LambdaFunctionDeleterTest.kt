package cloudcleaner.aws.resources.lambda

import aws.sdk.kotlin.services.lambda.model.LambdaException
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.lambda.LambdaClientStub.FunctionStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test

class LambdaFunctionDeleterTest {
  private val lambdaClient = LambdaClientStub()
  private val underTest = LambdaFunctionDeleter(lambdaClient)

  @Test
  fun `delete should successfully delete a function`() = runTest {
    // given
    val function = LambdaFunction(
        functionName = LambdaFunctionName("test-function", REGION),
        functionArn = null,
        runtime = "python3.12",
    )
    lambdaClient.functions.add(FunctionStub(function.name))

    // when
    underTest.delete(function)

    // then
    lambdaClient.getFunctionOrNull("test-function").shouldBeNull()
  }

  @Test
  fun `delete should ignore already deleted function`() = runTest {
    // given
    val function = LambdaFunction(
        functionName = LambdaFunctionName("does-not-exist-function", REGION),
        functionArn = null,
        runtime = "python3.12",
    )

    // when & then
    assertDoesNotThrow { underTest.delete(function) }
  }

  @Test
  fun `delete should throw exception for non-LambdaFunction resource`() = runTest {
    // given
    val nonLambdaResource = object : cloudcleaner.resources.Resource {
      override val id = cloudcleaner.resources.StringId("test")
      override val name = "test"
      override val type = "NotLambda"
      override val properties = emptyMap<String, String>()
      override val containedResources = emptySet<cloudcleaner.resources.Id>()
      override val dependsOn = emptySet<cloudcleaner.resources.Id>()
    }

    // when & then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(nonLambdaResource)
    }
  }

  @Test
  fun `delete should propagate exceptions from Lambda client`() = runTest {
    // given
    val function = LambdaFunction(
        functionName = LambdaFunctionName("test-function", REGION),
        functionArn = null,
        runtime = "python3.12",
    )
    lambdaClient.functions.add(FunctionStub(function.name))
    lambdaClient.deleteFailsWithError = true

    // when & then
    shouldThrow<LambdaException> {
      underTest.delete(function)
    }
  }

  @Test
  fun `delete should handle multiple deletions`() = runTest {
    // given
    val function1 = LambdaFunction(
        functionName = LambdaFunctionName("function1", REGION),
        functionArn = null,
        runtime = "python3.12",
    )
    val function2 = LambdaFunction(
        functionName = LambdaFunctionName("function2", REGION),
        functionArn = null,
        runtime = "nodejs20.x",
    )
    lambdaClient.functions.add(FunctionStub("function1"))
    lambdaClient.functions.add(FunctionStub("function2"))

    // when
    underTest.delete(function1)
    underTest.delete(function2)

    // then
    lambdaClient.functions.shouldHaveSize(0)
  }

  @Test
  fun `delete should work with functions that have dependencies`() = runTest {
    // given
    val function = LambdaFunction(
        functionName = LambdaFunctionName("test-function", REGION),
        functionArn = null,
        runtime = "python3.12",
        dependencies = setOf(
            cloudcleaner.aws.resources.iam.IamRoleName("test-role"),
            cloudcleaner.aws.resources.cloudwatch.CloudWatchLogGroupName("/aws/lambda/test-function", REGION)
        ),
    )
    lambdaClient.functions.add(FunctionStub(function.name))

    // when
    underTest.delete(function)

    // then
    lambdaClient.getFunctionOrNull("test-function").shouldBeNull()
  }

  @Test
  fun `delete should only delete specified function`() = runTest {
    // given
    val function1 = LambdaFunction(
        functionName = LambdaFunctionName("function1", REGION),
        functionArn = null,
        runtime = "python3.12",
    )
    lambdaClient.functions.add(FunctionStub("function1"))
    lambdaClient.functions.add(FunctionStub("function2"))

    // when
    underTest.delete(function1)

    // then
    lambdaClient.getFunctionOrNull("function1").shouldBeNull()
    lambdaClient.getFunctionOrNull("function2")?.functionName.shouldBe("function2")
  }
}

