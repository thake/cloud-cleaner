package cloudcleaner.aws.resources.backup

import aws.sdk.kotlin.services.backup.BackupClient
import aws.sdk.kotlin.services.backup.deleteRecoveryPoint
import aws.sdk.kotlin.services.backup.model.ResourceNotFoundException
import aws.sdk.kotlin.services.backup.paginators.listBackupVaultsPaginated
import aws.sdk.kotlin.services.backup.paginators.listRecoveryPointsByBackupVaultPaginated
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

data class RecoveryPointId(val recoveryPointArn: String, val backupVaultName: String, val region: String) : Id {
  override fun toString() = "$recoveryPointArn ($region)"
}

private const val TYPE = "RecoveryPoint"

data class RecoveryPoint(
    val recoveryPointId: RecoveryPointId,
    val backupVaultName: BackupVaultName,
) : Resource {
  override val id: Id = recoveryPointId
  override val name: String = recoveryPointId.recoveryPointArn
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()
  override val dependsOn: Set<Id> = setOf(backupVaultName)
  override val containedResources: Set<Id> = emptySet()

  override fun toString() = name
}

class RecoveryPointResourceDefinitionFactory : AwsResourceDefinitionFactory<RecoveryPoint> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<RecoveryPoint> {
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
        resourceDeleter = RecoveryPointDeleter(client),
        resourceScanner = RecoveryPointScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class RecoveryPointScanner(private val backupClient: BackupClient, val region: String) : ResourceScanner<RecoveryPoint> {
  override fun scan(): Flow<RecoveryPoint> = flow {
    // First, get all backup vaults
    val vaults = mutableListOf<String>()
    backupClient.listBackupVaultsPaginated().collect { response ->
      response.backupVaultList?.forEach { vault -> vault.backupVaultName?.let { vaults.add(it) } }
    }

    // Then, scan recovery points in each vault
    vaults.forEach { vaultName ->
      try {
        backupClient
            .listRecoveryPointsByBackupVaultPaginated { backupVaultName = vaultName }
            .collect { response ->
              response.recoveryPoints?.forEach { point ->
                val recoveryPointArn = point.recoveryPointArn ?: return@forEach

                emit(
                    RecoveryPoint(
                        recoveryPointId = RecoveryPointId(recoveryPointArn, vaultName, region),
                        backupVaultName = BackupVaultName(vaultName, region),
                    ),
                )
              }
            }
      } catch (_: ResourceNotFoundException) {
        logger.debug { "Vault $vaultName not found while scanning recovery points, possibly deleted during scan." }
      }
    }
  }
}

class RecoveryPointDeleter(private val backupClient: BackupClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val point = resource as? RecoveryPoint ?: throw IllegalArgumentException("Resource not a RecoveryPoint")

    try {
      backupClient.deleteRecoveryPoint {
        backupVaultName = point.recoveryPointId.backupVaultName
        recoveryPointArn = point.recoveryPointId.recoveryPointArn
      }
    } catch (_: ResourceNotFoundException) {
      logger.debug { "Deletion failed because recovery point ${point.recoveryPointId} already has been deleted." }
    }
  }
}
