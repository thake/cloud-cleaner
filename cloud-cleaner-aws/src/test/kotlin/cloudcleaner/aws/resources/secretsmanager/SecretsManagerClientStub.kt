@file:OptIn(InternalApi::class)

package cloudcleaner.aws.resources.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.model.CreateSecretRequest
import aws.sdk.kotlin.services.secretsmanager.model.CreateSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.DeleteSecretRequest
import aws.sdk.kotlin.services.secretsmanager.model.DeleteSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.DescribeSecretRequest
import aws.sdk.kotlin.services.secretsmanager.model.DescribeSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsRequest
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsResponse
import aws.sdk.kotlin.services.secretsmanager.model.ResourceNotFoundException
import aws.sdk.kotlin.services.secretsmanager.model.SecretListEntry
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata.Companion.ErrorCode
import aws.smithy.kotlin.runtime.time.Instant
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk
import kotlin.random.Random

class SecretsManagerClientStub(
    val delegate: SecretsManagerClient = mockk<SecretsManagerClient>()
) : SecretsManagerClient by delegate {
  val secrets = mutableListOf<SecretStub>()

  data class SecretStub(
      val name: String,
      val arn: String = "arn:aws:secretsmanager:$REGION:$ACCOUNT_ID:secret:$name-${Random.nextInt(100000, 999999)}",
      val secretString: String? = null,
      val deletionDate: Long? = null
  )

  private fun findSecret(nameOrArn: String?) =
      secrets.find { it.name == nameOrArn || it.arn == nameOrArn }
          ?: throw ResourceNotFoundException { message = "Secret $nameOrArn not found" }.apply {
            sdkErrorMetadata.attributes[ErrorCode] = "ResourceNotFoundException"
          }

  override suspend fun createSecret(input: CreateSecretRequest): CreateSecretResponse {
    val name = input.name ?: throw IllegalArgumentException("Secret name is required")

    if (secrets.any { it.name == name }) {
      throw IllegalArgumentException("Secret $name already exists")
    }

    val arn = "arn:aws:secretsmanager:$REGION:$ACCOUNT_ID:secret:$name-${Random.nextInt(100000, 999999)}"
    secrets.add(
        SecretStub(
            name = name,
            arn = arn,
            secretString = input.secretString
        )
    )

    return CreateSecretResponse {
      this.arn = arn
      this.name = name
    }
  }

  override suspend fun listSecrets(input: ListSecretsRequest): ListSecretsResponse {
    val startIndex = input.nextToken?.toIntOrNull() ?: 0
    val limit = input.maxResults ?: 100
    val page = secrets.filter { it.deletionDate == null }.drop(startIndex).take(limit)

    val nextToken = if (startIndex + limit < secrets.size) {
      (startIndex + limit).toString()
    } else null

    return ListSecretsResponse {
      this.secretList = page.map { stub ->
        SecretListEntry {
          name = stub.name
          arn = stub.arn
        }
      }
      this.nextToken = nextToken
    }
  }

  override suspend fun describeSecret(input: DescribeSecretRequest): DescribeSecretResponse {
    val secret = findSecret(input.secretId)

    return DescribeSecretResponse {
      this.arn = secret.arn
      this.name = secret.name
      this.deletedDate = secret.deletionDate?.let { Instant.fromEpochSeconds(it) }
    }
  }

  override suspend fun deleteSecret(input: DeleteSecretRequest): DeleteSecretResponse {
    val secret = findSecret(input.secretId)

    if (input.forceDeleteWithoutRecovery == true) {
      secrets.remove(secret)
    } else {
      // Schedule for deletion (mark with deletion date)
      val index = secrets.indexOf(secret)
      secrets[index] = secret.copy(deletionDate = System.currentTimeMillis() / 1000)
    }

    return DeleteSecretResponse {
      this.arn = secret.arn
      this.name = secret.name
      this.deletionDate = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000)
    }
  }

  override fun close() {
    // No-op for stub
  }
}

