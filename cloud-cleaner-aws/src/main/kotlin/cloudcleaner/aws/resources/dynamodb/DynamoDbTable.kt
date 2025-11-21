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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

data class DynamoDbTableName(val name: String, val region: String) : Id {
  override fun toString() = "$name ($region)"
}

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
        resourceScanner = DynamoDbTableScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class DynamoDbTableScanner(private val dynamoDbClient: DynamoDbClient, val region: String) : ResourceScanner<DynamoDbTable> {
  override fun scan(): Flow<DynamoDbTable> = flow {
    dynamoDbClient.listTablesPaginated().collect { response ->
      response.tableNames?.forEach { tableName ->
        val tableNameId = DynamoDbTableName(tableName, region)
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
            dynamoDbClient.describeTable(DescribeTableRequest { tableName = table.name })
          } catch (_: ResourceNotFoundException) {
            return
          }

      val currentTable = tableDescription.table
      if (currentTable?.tableStatus == TableStatus.Deleting) {
        dynamoDbClient.waitUntilTableNotExists { tableName = table.name }
        return
      }

      if (currentTable?.deletionProtectionEnabled == true) {
        dynamoDbClient.updateTable(
            UpdateTableRequest {
              tableName = table.name
              deletionProtectionEnabled = false
            })
        dynamoDbClient.waitUntilTableExists { tableName = table.name }
      }

      dynamoDbClient.deleteTable { tableName = table.name }
      dynamoDbClient.waitUntilTableNotExists { tableName = table.name }
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete DynamoDB table ${table.name}: ${e.message}" }
      throw e
    }
  }
}

data class DynamoDbTable(
  val tableName: DynamoDbTableName,
  val tableArn: Arn?,
  val deletionProtectionEnabled: Boolean,
) : Resource {
  override val id: Id = tableName
  override val containedResources: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = emptySet()
  override val name: String = tableName.name
  override val type: String = TYPE
  override val properties: Map<String, String> =
      mapOf("deletionProtectionEnabled" to deletionProtectionEnabled.toString(), "tableArn" to (tableArn?.value ?: ""))

  override fun toString() = tableName.toString()
}
