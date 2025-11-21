package cloudcleaner.aws.resources.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.deleteParameter
import aws.sdk.kotlin.services.ssm.model.ParameterNotFound
import aws.sdk.kotlin.services.ssm.paginators.describeParametersPaginated
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val ssmParameterLogger = KotlinLogging.logger {}

data class SsmParameterName(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "SsmParameter"

data class SsmParameter(
  val parameterName: SsmParameterName,
  val parameterArn: Arn,
) : Resource {
  override val id: Id = parameterName
  override val name: String = parameterName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()
  override val dependsOn: Set<Id> = emptySet()
  override val containedResources: Set<Id> = emptySet()

  override fun toString() = name
}

class SsmParameterResourceDefinitionFactory : AwsResourceDefinitionFactory<SsmParameter> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<SsmParameter> {
    val client = SsmClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = SsmParameterDeleter(client),
        resourceScanner = SsmParameterScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class SsmParameterScanner(private val ssmClient: SsmClient, val region: String) :
    ResourceScanner<SsmParameter> {
  override fun scan(): Flow<SsmParameter> = flow {
    ssmClient.describeParametersPaginated().collect { response ->
      response.parameters?.forEach { parameter ->
        val parameterName = parameter.name ?: return@forEach
        val parameterArn = parameter.arn ?: return@forEach

        emit(
            SsmParameter(
                parameterName = SsmParameterName(parameterName, region),
                parameterArn = Arn(parameterArn),
            ),
        )
      }
    }
  }
}

class SsmParameterDeleter(private val ssmClient: SsmClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val parameter =
        resource as? SsmParameter
            ?: throw IllegalArgumentException("Resource not a SsmParameter")

    try {
      ssmClient.deleteParameter { name = parameter.parameterName.value }
    } catch (_: ParameterNotFound) {
      ssmParameterLogger.debug {
        "Deletion failed because SSM parameter ${parameter.parameterName} already has been deleted."
      }
    } catch (e: Exception) {
      ssmParameterLogger.error(e) {
        "Failed to delete SSM parameter ${parameter.parameterName}: ${e.message}"
      }
      throw e
    }
  }
}

