@file:OptIn(InternalApi::class)

package cloudcleaner.aws.resources.backup

import aws.sdk.kotlin.services.backup.BackupClient
import aws.sdk.kotlin.services.backup.model.BackupException
import aws.sdk.kotlin.services.backup.model.BackupVaultListMember
import aws.sdk.kotlin.services.backup.model.CreateBackupVaultRequest
import aws.sdk.kotlin.services.backup.model.CreateBackupVaultResponse
import aws.sdk.kotlin.services.backup.model.DeleteBackupVaultRequest
import aws.sdk.kotlin.services.backup.model.DeleteBackupVaultResponse
import aws.sdk.kotlin.services.backup.model.DeleteRecoveryPointRequest
import aws.sdk.kotlin.services.backup.model.DeleteRecoveryPointResponse
import aws.sdk.kotlin.services.backup.model.ListBackupVaultsRequest
import aws.sdk.kotlin.services.backup.model.ListBackupVaultsResponse
import aws.sdk.kotlin.services.backup.model.ListRecoveryPointsByBackupVaultRequest
import aws.sdk.kotlin.services.backup.model.ListRecoveryPointsByBackupVaultResponse
import aws.sdk.kotlin.services.backup.model.RecoveryPointByBackupVault
import aws.sdk.kotlin.services.backup.model.ResourceNotFoundException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata.Companion.ErrorCode
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk

class BackupClientStub(val delegate: BackupClient = mockk<BackupClient>()) : BackupClient by delegate {
  val vaults = mutableListOf<VaultStub>()

  data class VaultStub(
      val vaultName: String,
      val vaultArn: String = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:$vaultName",
      val recoveryPoints: MutableList<RecoveryPointStub> = mutableListOf()
  ) {

    fun addRecoveryPoint(recoveryPointId: String): RecoveryPointStub {
      val rp = RecoveryPointStub(recoveryPointId = recoveryPointId, backupVaultName = vaultName)
      this.recoveryPoints.add(rp)
      return rp
    }
  }

  data class RecoveryPointStub(
      val recoveryPointId: String,
      val recoveryPointArn: String = "arn:aws:backup:$REGION:$ACCOUNT_ID:recovery-point:$recoveryPointId",
      val backupVaultName: String
  )

  private fun findVault(vaultName: String?) =
      vaults.find { it.vaultName == vaultName }
          ?: throw ResourceNotFoundException { message = "Vault $vaultName not found" }
              .apply { sdkErrorMetadata.attributes[ErrorCode] = "ResourceNotFoundException" }

  override suspend fun createBackupVault(input: CreateBackupVaultRequest): CreateBackupVaultResponse {
    val vaultName = input.backupVaultName ?: throw IllegalArgumentException("Vault name is required")

    vaults.add(VaultStub(vaultName = vaultName, vaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:$vaultName"))

    return CreateBackupVaultResponse {
      backupVaultName = vaultName
      backupVaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:$vaultName"
    }
  }

  override suspend fun listBackupVaults(input: ListBackupVaultsRequest): ListBackupVaultsResponse {
    val startIndex = input.nextToken?.toIntOrNull() ?: 0
    val limit = input.maxResults ?: 50
    val page = vaults.drop(startIndex).take(limit)

    val nextToken =
        if (startIndex + limit < vaults.size) {
          (startIndex + limit).toString()
        } else null

    return ListBackupVaultsResponse {
      backupVaultList =
          page.map { stub ->
            BackupVaultListMember {
              backupVaultName = stub.vaultName
              backupVaultArn = stub.vaultArn
            }
          }
      this.nextToken = nextToken
    }
  }

  override suspend fun deleteBackupVault(input: DeleteBackupVaultRequest): DeleteBackupVaultResponse {
    val vault = findVault(input.backupVaultName)
    if(vault.recoveryPoints.isNotEmpty()) {
      throw BackupException("Cannot delete backup vault that contains recovery points.").apply {
        sdkErrorMetadata.attributes[ErrorCode] = "InvalidRequestException"
      }
    }
    vaults.remove(vault)
    return DeleteBackupVaultResponse {}
  }

  override suspend fun listRecoveryPointsByBackupVault(
      input: ListRecoveryPointsByBackupVaultRequest
  ): ListRecoveryPointsByBackupVaultResponse {
    val vaultName = input.backupVaultName ?: throw IllegalArgumentException("Vault name is required")
    // Verify vault exists
    val vault = findVault(vaultName)

    val points = vault.recoveryPoints
    val startIndex = input.nextToken?.toIntOrNull() ?: 0
    val limit = input.maxResults ?: 50
    val page = points.drop(startIndex).take(limit)

    val nextToken =
        if (startIndex + limit < points.size) {
          (startIndex + limit).toString()
        } else null

    return ListRecoveryPointsByBackupVaultResponse {
      recoveryPoints =
          page.map { stub ->
            RecoveryPointByBackupVault {
              recoveryPointArn = stub.recoveryPointArn
              backupVaultName = stub.backupVaultName
            }
          }
      this.nextToken = nextToken
    }
  }

  override suspend fun deleteRecoveryPoint(input: DeleteRecoveryPointRequest): DeleteRecoveryPointResponse {
    val vaultName = input.backupVaultName ?: throw IllegalArgumentException("Vault name is required")
    val recoveryPointArn = input.recoveryPointArn ?: throw IllegalArgumentException("Recovery point ARN is required")
    val vault = findVault(vaultName)
    val point = vault.recoveryPoints.find { it.backupVaultName == vaultName && it.recoveryPointArn == recoveryPointArn }
            ?: throw ResourceNotFoundException { message = "Recovery point $recoveryPointArn not found" }
                .apply { sdkErrorMetadata.attributes[ErrorCode] = "ResourceNotFoundException" }

    vault.recoveryPoints.remove(point)
    return DeleteRecoveryPointResponse {}
  }

  override fun close() {
    // No-op for stub
  }
}
