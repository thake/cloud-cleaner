package cloudcleaner.aws.resources.backup

import aws.sdk.kotlin.services.backup.BackupClient
import aws.sdk.kotlin.services.backup.deleteRecoveryPoint
import aws.sdk.kotlin.services.backup.model.ResourceNotFoundException
import aws.sdk.kotlin.services.backup.paginators.listRecoveryPointsByBackupVaultPaginated
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}
suspend fun BackupClient.purgeBackupVault(vaultName: String) {
  listRecoveryPointsByBackupVaultPaginated { backupVaultName = vaultName }
      .collect { response ->
        val recoveryPointsArns = response.recoveryPoints?.mapNotNull { it.recoveryPointArn } ?: emptyList()
        recoveryPointsArns.forEach { recoveryPointArn ->
          try {
            deleteRecoveryPoint {
              this.backupVaultName = vaultName
              this.recoveryPointArn = recoveryPointArn
            }
          } catch (_: ResourceNotFoundException) {
            logger.debug { "Recovery point not found when trying to delete it. Ignoring." }
          }
        }
      }
}
