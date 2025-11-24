package cloudcleaner.aws.resources.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.deleteFunction
import aws.sdk.kotlin.services.lambda.getFunction
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LambdaClientBehaviorIntegrationTest {
  private val lambdaClient = LambdaClient {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }
  private val stub = LambdaClientStub()

  @Test
  fun `deleteFunction should throw ResourceNotFoundException for non-existent function`() = runTest {
    val functionName = "non-existent-function-${Uuid.random()}"

    val actual = shouldThrow<ServiceException> { stub.deleteFunction { this.functionName = functionName } }
    val expected = shouldThrow<ServiceException> { lambdaClient.deleteFunction { this.functionName = functionName } }
    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `getFunction should throw ResourceNotFoundException for non-existent function`() = runTest {
    val functionName = "non-existent-function-${Uuid.random()}"

    val actual = shouldThrow<ServiceException> { stub.getFunction { this.functionName = functionName } }
    val expected = shouldThrow<ServiceException> { lambdaClient.getFunction { this.functionName = functionName } }
    actual.shouldBeEquivalentTo(expected)
  }
}

