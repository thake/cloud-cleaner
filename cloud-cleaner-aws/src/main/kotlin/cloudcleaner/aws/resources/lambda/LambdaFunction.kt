package cloudcleaner.aws.resources.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.deleteFunction
import aws.sdk.kotlin.services.lambda.getFunction
import aws.sdk.kotlin.services.lambda.model.ResourceNotFoundException
import aws.sdk.kotlin.services.lambda.paginators.listFunctionsPaginated
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.aws.resources.cloudwatch.CloudWatchLogGroupName
import cloudcleaner.aws.resources.iam.toIamRoleName
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

data class LambdaFunctionName(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "LambdaFunction"

class LambdaFunctionResourceDefinitionFactory : AwsResourceDefinitionFactory<LambdaFunction> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<LambdaFunction> {
    val client = LambdaClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = LambdaFunctionDeleter(client),
        resourceScanner = LambdaFunctionScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class LambdaFunctionScanner(private val lambdaClient: LambdaClient, val region: String) : ResourceScanner<LambdaFunction> {
  override fun scan(): Flow<LambdaFunction> = flow {
    lambdaClient.listFunctionsPaginated().collect { response ->
      response.functions?.forEach { functionConfig ->
        val functionName = functionConfig.functionName
        if (functionName != null) {
          val functionNameId = LambdaFunctionName(functionName, region)
          val functionArn = functionConfig.functionArn?.let { Arn(it) }

          // Get function details to extract dependencies
          val dependencies = mutableSetOf<Id>()
          try {
            val functionDetails = lambdaClient.getFunction { this.functionName = functionName }

            functionDetails.configuration?.role?.let { roleArn ->
              dependencies.add(Arn(roleArn).toIamRoleName())
            }
            functionDetails.configuration?.loggingConfig?.logGroup?.let {
              dependencies.add(CloudWatchLogGroupName(it, region))
            }

          } catch (e: Exception) {
            logger.warn(e) { "Failed to get function details for $functionName: ${e.message}" }
          }

          emit(
              LambdaFunction(
                  functionName = functionNameId,
                  functionArn = functionArn,
                  runtime = functionConfig.runtime?.value,
                  dependencies = dependencies,
              ))
        }
      }
    }
  }
}

class LambdaFunctionDeleter(private val lambdaClient: LambdaClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val function = resource as? LambdaFunction ?: throw IllegalArgumentException("Resource not a LambdaFunction")

    try {
      lambdaClient.deleteFunction { functionName = function.name }
    } catch (_: ResourceNotFoundException) {
      logger.debug { "Lambda function ${function.name} not found, assuming already deleted" }
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete Lambda function ${function.name}: ${e.message}" }
      throw e
    }
  }
}

data class LambdaFunction(
  val functionName: LambdaFunctionName,
  val functionArn: Arn?,
  val runtime: String?,
  private val dependencies: Set<Id> = emptySet(),
) : Resource {
  override val id: Id = functionName
  override val containedResources: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = dependencies
  override val name: String = functionName.value
  override val type: String = TYPE
  override val properties: Map<String, String> =
      mapOf(
          "functionArn" to (functionArn?.value ?: ""),
          "runtime" to (runtime ?: ""),
      )

  override fun toString() = functionName.toString()
}

