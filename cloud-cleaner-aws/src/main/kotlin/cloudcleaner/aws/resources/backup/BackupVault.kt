package cloudcleaner.aws.resources.backup

import aws.sdk.kotlin.services.backup.BackupClient
import aws.sdk.kotlin.services.backup.deleteBackupVault
import aws.sdk.kotlin.services.backup.model.ResourceNotFoundException
import aws.sdk.kotlin.services.backup.paginators.listBackupVaultsPaginated
import aws.sdk.kotlin.services.backup.paginators.listRecoveryPointsByBackupVaultPaginated
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

private val logger = KotlinLogging.logger {}

data class BackupVaultName(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}

private const val TYPE = "BackupVault"

data class BackupVault(
    val vaultName: BackupVaultName,
    val vaultArn: Arn,
    val recoveryPoints: Set<RecoveryPointId> = emptySet(),
) : Resource {
  override val id: Id = vaultName
  override val name: String = vaultName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()
  override val containedResources: Set<Id> = recoveryPoints

  override fun toString() = name
}

class BackupVaultResourceDefinitionFactory : AwsResourceDefinitionFactory<BackupVault> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<BackupVault> {
    val client = BackupClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = BackupVaultDeleter(client),
        resourceScanner = BackupVaultScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class BackupVaultScanner(private val backupClient: BackupClient, val region: String) : ResourceScanner<BackupVault> {
  override fun scan(): Flow<BackupVault> = flow {
    backupClient.listBackupVaultsPaginated().collect { response ->
      response.backupVaultList?.forEach { vault ->
        val vaultName = vault.backupVaultName ?: return@forEach
        val vaultArn = vault.backupVaultArn ?: return@forEach

        // Scan recovery points in this vault
        val recoveryPoints = mutableSetOf<RecoveryPointId>()
        try {
          backupClient
              .listRecoveryPointsByBackupVaultPaginated { backupVaultName = vaultName }
              .collect { rpResponse ->
                rpResponse.recoveryPoints?.forEach { point ->
                  point.recoveryPointArn?.let { arn -> recoveryPoints.add(RecoveryPointId(arn, vaultName, region)) }
                }
              }
        } catch (e: Exception) {
          logger.warn(e) { "Failed to list recovery points for vault $vaultName: ${e.message}" }
        }

        emit(
            BackupVault(vaultName = BackupVaultName(vaultName, region), vaultArn = Arn(vaultArn), recoveryPoints = recoveryPoints),
        )
      }
    }
  }
}

class BackupVaultDeleter(private val backupClient: BackupClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val vault = resource as? BackupVault ?: throw IllegalArgumentException("Resource not a BackupVault")

    try {
      backupClient.purgeBackupVault(vault.name)
      backupClient.deleteBackupVault { backupVaultName = vault.name }
    } catch (_: ResourceNotFoundException) {
      logger.debug { "Deletion failed because backup vault ${vault.name} already has been deleted." }
    }
  }
}
