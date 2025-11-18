package cloudcleaner.aws.resources.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.deleteTable
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.model.UpdateTableRequest
import aws.sdk.kotlin.services.dynamodb.paginators.listTablesPaginated
import aws.sdk.kotlin.services.dynamodb.waiters.waitUntilTableExists
import aws.sdk.kotlin.services.dynamodb.waiters.waitUntilTableNotExists
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import cloudcleaner.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

typealias TableName = StringId

private const val TYPE = "DynamoDbTable"

class DynamoDbTableResourceDefinitionFactory : AwsResourceDefinitionFactory<DynamoDbTable> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<DynamoDbTable> {
    val client = DynamoDbClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = DynamoDbTableDeleter(client),
        resourceScanner = DynamoDbTableScanner(client),
        close = { client.close() },
    )
  }
}

class DynamoDbTableScanner(private val dynamoDbClient: DynamoDbClient) : ResourceScanner<DynamoDbTable> {
  override fun scan(): Flow<DynamoDbTable> = flow {
    dynamoDbClient.listTablesPaginated().collect { response ->
      response.tableNames?.forEach { tableName ->
        val tableNameId = TableName(tableName)
        try {
          val tableDescription = dynamoDbClient.describeTable(DescribeTableRequest { this.tableName = tableName })

          val table = tableDescription.table
          if (table != null && table.tableStatus != TableStatus.Deleting) {
            val tableArn = table.tableArn?.let { Arn(it) }
            emit(
                DynamoDbTable(
                    tableName = tableNameId,
                    tableArn = tableArn,
                    deletionProtectionEnabled = table.deletionProtectionEnabled ?: false,
                ))
          }
        } catch (e: Exception) {
          logger.warn(e) { "Failed to describe DynamoDB table $tableName: ${e.message}" }
        }
      }
    }
  }
}

class DynamoDbTableDeleter(private val dynamoDbClient: DynamoDbClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val table = resource as? DynamoDbTable ?: throw IllegalArgumentException("Resource not a DynamoDbTable")

    try {
      // Check current table status
      val tableDescription =
          try {
            dynamoDbClient.describeTable(DescribeTableRequest { tableName = table.tableName.value })
          } catch (_: ResourceNotFoundException) {
            logger.info { "DynamoDB table ${table.tableName.value} has already been deleted. Ignoring." }
            return
          }

      val currentTable = tableDescription.table
      if (currentTable?.tableStatus == TableStatus.Deleting) {
        logger.info { "DynamoDB table ${table.tableName.value} is already being deleted, waiting for completion" }
        dynamoDbClient.waitUntilTableNotExists { tableName = table.tableName.value }
        return
      }

      // Disable deletion protection if enabled
      if (currentTable?.deletionProtectionEnabled == true) {
        logger.info { "Disabling deletion protection for DynamoDB table ${table.tableName.value}" }
        dynamoDbClient.updateTable(
            UpdateTableRequest {
              tableName = table.tableName.value
              deletionProtectionEnabled = false
            })

        // Wait for the update to complete
        dynamoDbClient.waitUntilTableExists { tableName = table.tableName.value }
      }

      // Delete the table
      logger.info { "Deleting DynamoDB table ${table.tableName.value}" }
      dynamoDbClient.deleteTable { tableName = table.tableName.value }

      // Wait for deletion to complete
      dynamoDbClient.waitUntilTableNotExists { tableName = table.tableName.value }
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete DynamoDB table ${table.tableName.value}: ${e.message}" }
      throw e
    }
  }
}

data class DynamoDbTable(
    val tableName: TableName,
    val tableArn: Arn?,
    val deletionProtectionEnabled: Boolean,
) : Resource {
  override val id: Id
    get() = tableName

  override val contains: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = emptySet()
  override val name: String = tableName.value
  override val type: String = TYPE
  override val properties: Map<String, String> =
      mapOf("deletionProtectionEnabled" to deletionProtectionEnabled.toString(), "tableArn" to (tableArn?.value ?: ""))

  override fun toString() = tableName.value
}
