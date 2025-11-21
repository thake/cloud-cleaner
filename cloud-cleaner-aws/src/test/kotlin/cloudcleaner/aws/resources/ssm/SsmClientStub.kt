@file:OptIn(InternalApi::class)

package cloudcleaner.aws.resources.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.DeleteParameterRequest
import aws.sdk.kotlin.services.ssm.model.DeleteParameterResponse
import aws.sdk.kotlin.services.ssm.model.DescribeParametersRequest
import aws.sdk.kotlin.services.ssm.model.DescribeParametersResponse
import aws.sdk.kotlin.services.ssm.model.ParameterAlreadyExists
import aws.sdk.kotlin.services.ssm.model.ParameterMetadata
import aws.sdk.kotlin.services.ssm.model.ParameterNotFound
import aws.sdk.kotlin.services.ssm.model.PutParameterRequest
import aws.sdk.kotlin.services.ssm.model.PutParameterResponse
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata.Companion.ErrorCode
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk

class SsmClientStub(
    val delegate: SsmClient = mockk<SsmClient>()
) : SsmClient by delegate {
  val parameters = mutableListOf<ParameterStub>()

  data class ParameterStub(
      val parameterName: String,
      val parameterArn: String = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter$parameterName"
  )

  private fun findParameter(parameterName: String?) =
      parameters.find { it.parameterName == parameterName }
          ?: throw ParameterNotFound { message = "Parameter $parameterName not found" }.apply {
            sdkErrorMetadata.attributes[ErrorCode] = "ParameterNotFoundException"
          }

  override suspend fun putParameter(input: PutParameterRequest): PutParameterResponse {
    val parameterName = input.name ?: throw IllegalArgumentException("Parameter name is required")

    if (parameters.any { it.parameterName == parameterName }) {
      throw ParameterAlreadyExists { message = "Parameter $parameterName already exists" }
    }

    parameters.add(
        ParameterStub(
            parameterName = parameterName,
            parameterArn = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter$parameterName"
        )
    )

    return PutParameterResponse {}
  }

  override suspend fun describeParameters(input: DescribeParametersRequest): DescribeParametersResponse {
    val startIndex = input.nextToken?.toIntOrNull() ?: 0
    val limit = input.maxResults ?: 50
    val page = parameters.drop(startIndex).take(limit)

    val nextToken = if (startIndex + limit < parameters.size) {
      (startIndex + limit).toString()
    } else null

    return DescribeParametersResponse {
      this.parameters = page.map { stub ->
        ParameterMetadata {
          name = stub.parameterName
          arn = stub.parameterArn
        }
      }
      this.nextToken = nextToken
    }
  }

  override suspend fun deleteParameter(input: DeleteParameterRequest): DeleteParameterResponse {
    val parameter = findParameter(input.name)
    parameters.remove(parameter)
    return DeleteParameterResponse {}
  }

  override fun close() {
    // No-op for stub
  }
}

