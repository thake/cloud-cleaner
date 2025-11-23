package cloudcleaner.aws.resources.kms

import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.kms.describeKey
import aws.sdk.kotlin.services.kms.model.KeyState
import aws.sdk.kotlin.services.kms.model.KmsInvalidStateException
import aws.sdk.kotlin.services.kms.model.NotFoundException
import aws.sdk.kotlin.services.kms.paginators.listKeysPaginated
import aws.sdk.kotlin.services.kms.scheduleKeyDeletion
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

val kmsKeyLogger = KotlinLogging.logger {}

data class KmsKeyId(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "KmsKey"

data class KmsKey(
    val keyId: KmsKeyId,
    val keyArn: Arn,
    val keyManager: String?,
    val keyState: String?,
) : Resource {
  override val id: Id = keyId
  override val name: String = keyId.value
  override val type: String = TYPE
  override val properties: Map<String, String> = buildMap {
    keyManager?.let { put("keyManager", it) }
    keyState?.let { put("keyState", it) }
  }
  override val dependsOn: Set<Id> = emptySet()
  override val containedResources: Set<Id> = emptySet()

  override fun toString() = name
}

class KmsKeyResourceDefinitionFactory : AwsResourceDefinitionFactory<KmsKey> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<KmsKey> {
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
        resourceDeleter = KmsKeyDeleter(client),
        resourceScanner = KmsKeyScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class KmsKeyScanner(private val kmsClient: KmsClient, val region: String) : ResourceScanner<KmsKey> {
  override fun scan(): Flow<KmsKey> = flow {
    kmsClient.listKeysPaginated().collect { response ->
      response.keys?.forEach { keyListEntry ->
        val keyId = keyListEntry.keyId ?: return@forEach
        val keyArn = keyListEntry.keyArn ?: return@forEach
        // Describe the key to get more details
        try {
          val describeResponse = kmsClient.describeKey { this.keyId = keyId }
          val keyMetadata = describeResponse.keyMetadata

          // Skip AWS managed keys
          if (keyMetadata?.keyManager?.value == "AWS") {
            return@forEach
          }

          // Skip keys that are already pending deletion
          if (keyMetadata?.keyState == KeyState.fromValue("PendingDeletion")) {
            return@forEach
          }

          emit(
              KmsKey(
                  keyId = KmsKeyId(keyId, region),
                  keyArn = Arn(keyArn),
                  keyManager = keyMetadata?.keyManager?.value,
                  keyState = keyMetadata?.keyState?.value,
              ),
          )
        } catch (e: NotFoundException) {
          kmsKeyLogger.debug(e) { "Key $keyId not found during scan, skipping" }
        }
      }
    }
  }
}

class KmsKeyDeleter(private val kmsClient: KmsClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val key = resource as? KmsKey ?: throw IllegalArgumentException("Resource not a KmsKey")

    try {
      kmsClient.scheduleKeyDeletion {
        keyId = key.keyId.value
        pendingWindowInDays = 7
      }
    } catch (_: NotFoundException) {
      kmsKeyLogger.debug { "Deletion failed because KMS key ${key.keyId} already has been deleted." }
    } catch (e: KmsInvalidStateException) {
      if (e.message.contains("is pending deletion")) {
        kmsKeyLogger.debug { "Deletion failed because KMS key ${key.keyId} already is pending deletion." }
        return
      } else {
        throw e
      }
    }
  }
}
