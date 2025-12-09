package cloudcleaner.aws.resources.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.DeleteFunctionRequest
import aws.sdk.kotlin.services.lambda.model.DeleteFunctionResponse
import aws.sdk.kotlin.services.lambda.model.FunctionConfiguration
import aws.sdk.kotlin.services.lambda.model.GetFunctionRequest
import aws.sdk.kotlin.services.lambda.model.GetFunctionResponse
import aws.sdk.kotlin.services.lambda.model.LambdaException
import aws.sdk.kotlin.services.lambda.model.ListFunctionsRequest
import aws.sdk.kotlin.services.lambda.model.ListFunctionsResponse
import aws.sdk.kotlin.services.lambda.model.LoggingConfig
import aws.sdk.kotlin.services.lambda.model.ResourceNotFoundException
import aws.sdk.kotlin.services.lambda.model.Runtime
import aws.sdk.kotlin.services.lambda.model.VpcConfigResponse
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk

class LambdaClientStub(
  val delegate: LambdaClient = mockk<LambdaClient>(),
) : LambdaClient by delegate {
  val functions = mutableListOf<FunctionStub>()
  var deleteFailsWithError = false
  var getFunctionFailsWithError = false

  fun getFunctionOrNull(functionName: String): FunctionStub? =
      functions.find { it.functionName == functionName }

  fun getFunction(functionName: String): FunctionStub =
      getFunctionOrNull(functionName) ?: throw resourceNotFoundException(functionName)

  data class FunctionStub(
    val functionName: String,
    val functionArn: String = "arn:aws:lambda:$REGION:$ACCOUNT_ID:function:$functionName",
    val runtime: Runtime = Runtime.Python312,
    val role: String = "arn:aws:iam::$ACCOUNT_ID:role/lambda-execution-role",
    val handler: String = "index.handler",
    val logGroup: String = "/aws/lambda/$functionName",
    val vpcId: String? = null,
  )

  override suspend fun listFunctions(input: ListFunctionsRequest): ListFunctionsResponse {
    val startIndex = input.marker?.toIntOrNull() ?: 0
    val maxItems = input.maxItems ?: 50
    val page = functions.drop(startIndex).take(maxItems)
    val nextMarker = if (startIndex + maxItems < functions.size) {
      (startIndex + maxItems).toString()
    } else null

    return ListFunctionsResponse {
      functions = page.map { it.toFunctionConfiguration() }
      this.nextMarker = nextMarker
    }
  }

  @OptIn(InternalApi::class)
  override suspend fun getFunction(input: GetFunctionRequest): GetFunctionResponse {
    if (getFunctionFailsWithError) {
      throw LambdaException("Get function failed")
    }

    val function = getFunction(input.functionName!!)

    return GetFunctionResponse {
      configuration = function.toFunctionConfiguration()
    }
  }

  @OptIn(InternalApi::class)
  private fun resourceNotFoundException(functionName: String): ResourceNotFoundException =
      ResourceNotFoundException {
        message = "Function $functionName does not exist"
      }.apply {
        sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "ResourceNotFoundException"
      }

  override suspend fun deleteFunction(input: DeleteFunctionRequest): DeleteFunctionResponse {
    if (deleteFailsWithError) {
      throw LambdaException("Delete function failed")
    }

    val functionName = input.functionName!!
    val function = getFunctionOrNull(functionName)
        ?: throw resourceNotFoundException(functionName)

    functions.remove(function)

    return DeleteFunctionResponse {}
  }

  private fun FunctionStub.toFunctionConfiguration(): FunctionConfiguration = FunctionConfiguration {
    functionName = this@toFunctionConfiguration.functionName
    functionArn = this@toFunctionConfiguration.functionArn
    runtime = this@toFunctionConfiguration.runtime
    role = this@toFunctionConfiguration.role
    handler = this@toFunctionConfiguration.handler
    loggingConfig = this@toFunctionConfiguration.toLoggingConfig()
    vpcConfig = this@toFunctionConfiguration.vpcId?.let { toVpcConfig(it) }
  }

  private fun FunctionStub.toLoggingConfig() = LoggingConfig {
    this.logGroup = this@toLoggingConfig.logGroup
  }

  private fun toVpcConfig(vpcId: String) = VpcConfigResponse {
    this.vpcId = vpcId
  }
}

