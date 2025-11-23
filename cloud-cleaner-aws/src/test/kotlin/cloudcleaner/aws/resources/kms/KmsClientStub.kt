@file:OptIn(InternalApi::class)

package cloudcleaner.aws.resources.kms

import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.kms.model.AliasListEntry
import aws.sdk.kotlin.services.kms.model.CreateAliasRequest
import aws.sdk.kotlin.services.kms.model.CreateAliasResponse
import aws.sdk.kotlin.services.kms.model.CreateKeyRequest
import aws.sdk.kotlin.services.kms.model.CreateKeyResponse
import aws.sdk.kotlin.services.kms.model.DeleteAliasRequest
import aws.sdk.kotlin.services.kms.model.DeleteAliasResponse
import aws.sdk.kotlin.services.kms.model.DescribeKeyRequest
import aws.sdk.kotlin.services.kms.model.DescribeKeyResponse
import aws.sdk.kotlin.services.kms.model.KeyListEntry
import aws.sdk.kotlin.services.kms.model.KeyManagerType
import aws.sdk.kotlin.services.kms.model.KeyMetadata
import aws.sdk.kotlin.services.kms.model.KeyState
import aws.sdk.kotlin.services.kms.model.KmsInvalidStateException
import aws.sdk.kotlin.services.kms.model.ListAliasesRequest
import aws.sdk.kotlin.services.kms.model.ListAliasesResponse
import aws.sdk.kotlin.services.kms.model.ListKeysRequest
import aws.sdk.kotlin.services.kms.model.ListKeysResponse
import aws.sdk.kotlin.services.kms.model.NotFoundException
import aws.sdk.kotlin.services.kms.model.ScheduleKeyDeletionRequest
import aws.sdk.kotlin.services.kms.model.ScheduleKeyDeletionResponse
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata.Companion.ErrorCode
import aws.smithy.kotlin.runtime.time.Instant
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk
import kotlin.random.Random

class KmsClientStub(
    val delegate: KmsClient = mockk<KmsClient>()
) : KmsClient by delegate {
  val aliases = mutableListOf<AliasStub>()
  val keys = mutableListOf<KeyStub>()

  data class AliasStub(
      val aliasName: String,
      val targetKeyId: String,
      val aliasArn: String = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/${aliasName.removePrefix("alias/")}"
  )

  data class KeyStub(
    val keyId: String,
    val keyArn: String = "arn:aws:kms:$REGION:$ACCOUNT_ID:key/$keyId",
    val keyManager: KeyManagerType = KeyManagerType.Customer,
    val keyState: KeyState = KeyState.Enabled,
    val description: String? = null
  )

  private fun findAlias(aliasName: String?) =
      aliases.find { it.aliasName == aliasName }
          ?: throw NotFoundException { message = "Alias $aliasName not found" }.apply {
            sdkErrorMetadata.attributes[ErrorCode] = "NotFoundException"
          }

  private fun findKey(keyId: String?) =
      keys.find { it.keyId == keyId }
          ?: throw NotFoundException { message = "Key $keyId not found" }.apply {
            sdkErrorMetadata.attributes[ErrorCode] = "NotFoundException"
          }

  override suspend fun createAlias(input: CreateAliasRequest): CreateAliasResponse {
    val aliasName = input.aliasName ?: throw IllegalArgumentException("Alias name is required")
    val targetKeyId = input.targetKeyId ?: throw IllegalArgumentException("Target key ID is required")

    if (aliases.any { it.aliasName == aliasName }) {
      throw IllegalArgumentException("Alias $aliasName already exists")
    }

    aliases.add(
        AliasStub(
            aliasName = aliasName,
            targetKeyId = targetKeyId,
            aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/${aliasName.removePrefix("alias/")}"
        )
    )

    return CreateAliasResponse {}
  }

  override suspend fun listAliases(input: ListAliasesRequest): ListAliasesResponse {
    val startIndex = input.marker?.toIntOrNull() ?: 0
    val limit = input.limit ?: 100
    val page = aliases.drop(startIndex).take(limit)

    val marker = if (startIndex + limit < aliases.size) {
      (startIndex + limit).toString()
    } else null

    return ListAliasesResponse {
      this.aliases = page.map { stub ->
        AliasListEntry {
          aliasName = stub.aliasName
          aliasArn = stub.aliasArn
          targetKeyId = stub.targetKeyId
        }
      }
      this.nextMarker = marker
      this.truncated = marker != null
    }
  }

  override suspend fun deleteAlias(input: DeleteAliasRequest): DeleteAliasResponse {
    val alias = findAlias(input.aliasName)
    aliases.remove(alias)
    return DeleteAliasResponse {}
  }

  override suspend fun createKey(input: CreateKeyRequest): CreateKeyResponse {
    val keyId = "key-${Random.nextInt(100000, 999999)}"
    val keyArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:key/$keyId"

    keys.add(
        KeyStub(
            keyId = keyId,
            keyArn = keyArn,
            keyManager = KeyManagerType.Customer,
            keyState = KeyState.Enabled,
            description = input.description
        )
    )

    return CreateKeyResponse {
      keyMetadata = KeyMetadata {
        this.keyId = keyId
        this.arn = keyArn
        this.keyState = KeyState.Enabled
        this.keyManager = KeyManagerType.Customer
        this.enabled = true
        this.creationDate = Instant.now()
      }
    }
  }

  override suspend fun listKeys(input: ListKeysRequest): ListKeysResponse {
    val startIndex = input.marker?.toIntOrNull() ?: 0
    val limit = input.limit ?: 1000
    val page = keys.drop(startIndex).take(limit)

    val marker = if (startIndex + limit < keys.size) {
      (startIndex + limit).toString()
    } else null

    return ListKeysResponse {
      this.keys = page.map { stub ->
        KeyListEntry {
          keyId = stub.keyId
          keyArn = stub.keyArn
        }
      }
      this.nextMarker = marker
      this.truncated = marker != null
    }
  }

  override suspend fun describeKey(input: DescribeKeyRequest): DescribeKeyResponse {
    val key = findKey(input.keyId)

    return DescribeKeyResponse {
      keyMetadata = KeyMetadata {
        this.keyId = key.keyId
        this.arn = key.keyArn
        this.keyState = key.keyState
        this.keyManager = key.keyManager
        this.enabled = key.keyState == KeyState.Enabled
        this.creationDate = Instant.now()
        this.description = key.description
      }
    }
  }

  override suspend fun scheduleKeyDeletion(input: ScheduleKeyDeletionRequest): ScheduleKeyDeletionResponse {
    val key = findKey(input.keyId)
    if(key.keyState == KeyState.PendingDeletion || key.keyState == KeyState.PendingReplicaDeletion) {
      throw KmsInvalidStateException{
        message = "${key.keyArn} is pending deletion."
      }.apply {
        sdkErrorMetadata.attributes[ErrorCode] = "KMSInvalidStateException"

      }
    }
    val index = keys.indexOf(key)
    keys[index] = key.copy(keyState = KeyState.PendingDeletion)

    return ScheduleKeyDeletionResponse {
      keyId = key.keyId
      deletionDate = Instant.now()
      keyState = KeyState.fromValue("PendingDeletion")
    }
  }

  override fun close() {
    // No-op for stub
  }
}

