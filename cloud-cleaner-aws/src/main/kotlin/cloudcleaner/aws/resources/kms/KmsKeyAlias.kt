package cloudcleaner.aws.resources.kms

import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.kms.deleteAlias
import aws.sdk.kotlin.services.kms.model.NotFoundException
import aws.sdk.kotlin.services.kms.paginators.listAliasesPaginated
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

val kmsKeyAliasLogger = KotlinLogging.logger {}

data class KmsKeyAliasName(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "KmsKeyAlias"

data class KmsKeyAlias(
  val aliasName: KmsKeyAliasName,
  val aliasArn: Arn,
  val targetKeyId: KmsKeyId,
) : Resource {
  override val id: Id = aliasName
  override val name: String = aliasName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = mapOf("targetKeyId" to targetKeyId.value)
  override val dependsOn: Set<Id> = setOf(targetKeyId)
  override val containedResources: Set<Id> = emptySet()

  override fun toString() = name
}

class KmsKeyAliasResourceDefinitionFactory : AwsResourceDefinitionFactory<KmsKeyAlias> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<KmsKeyAlias> {
    val client = KmsClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = KmsKeyAliasDeleter(client),
        resourceScanner = KmsKeyAliasScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class KmsKeyAliasScanner(private val kmsClient: KmsClient, val region: String) :
    ResourceScanner<KmsKeyAlias> {
  override fun scan(): Flow<KmsKeyAlias> = flow {
    kmsClient.listAliasesPaginated().collect { response ->
      response.aliases?.forEach { alias ->
        val aliasName = alias.aliasName ?: return@forEach
        val aliasArn = alias.aliasArn ?: return@forEach
        val targetKeyId = alias.targetKeyId ?: return@forEach

        // Skip AWS managed aliases (they start with "alias/aws/")
        if (aliasName.startsWith("alias/aws/")) {
          return@forEach
        }

        emit(
            KmsKeyAlias(
                aliasName = KmsKeyAliasName(aliasName, region),
                aliasArn = Arn(aliasArn),
                targetKeyId = KmsKeyId(targetKeyId, region),
            ),
        )
      }
    }
  }
}

class KmsKeyAliasDeleter(private val kmsClient: KmsClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val alias =
        resource as? KmsKeyAlias
            ?: throw IllegalArgumentException("Resource not a KmsKeyAlias")

    try {
      kmsClient.deleteAlias { aliasName = alias.aliasName.value }
    } catch (_: NotFoundException) {
      kmsKeyAliasLogger.debug {
        "Deletion failed because KMS key alias ${alias.aliasName} already has been deleted."
      }
    } catch (e: Exception) {
      kmsKeyAliasLogger.error(e) {
        "Failed to delete KMS key alias ${alias.aliasName}: ${e.message}"
      }
      throw e
    }
  }
}

