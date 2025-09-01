package cloudcleaner.aws.resources.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.DeleteTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteTableResponse
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableResponse
import aws.sdk.kotlin.services.dynamodb.model.DynamoDbException
import aws.sdk.kotlin.services.dynamodb.model.ListTablesRequest
import aws.sdk.kotlin.services.dynamodb.model.ListTablesResponse
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.TableDescription
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.model.UpdateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateTableResponse
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DynamoDbClientStub(
  val delegate: DynamoDbClient = mockk<DynamoDbClient>(),
) : DynamoDbClient by delegate {
  val tables = mutableListOf<TableStub>()
  var deleteFailsWithError = false
  var updateFailsWithError = false

  fun getTableOrNull(tableName: String): TableStub? = tables.find { it.tableName == tableName }
  fun getActiveTableOrNull(tableName: String) =
      getTableOrNull(tableName)?.takeIf { it.tableStatus != TableStatus.Deleting }

  fun getActiveTable(tableName: String): TableStub =
      getActiveTableOrNull(tableName) ?: throw resourceNotFoundException("Table $tableName not found")

  data class TableStub(
    val tableName: String,
    var tableStatus: TableStatus = TableStatus.Active,
    var deletionProtectionEnabled: Boolean = false,
    val tableArn: String = "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/$tableName",
  )

  override suspend fun listTables(input: ListTablesRequest): ListTablesResponse {
    val activeTableNames = tables
        .map { it.tableName }

    val startIndex = input.exclusiveStartTableName?.let { startTableName ->
      activeTableNames.indexOfFirst { it == startTableName } + 1
    } ?: 0

    val limit = input.limit ?: 100
    val page = activeTableNames.drop(startIndex).take(limit)
    val lastEvaluatedTableName = if (page.size == limit && startIndex + limit < activeTableNames.size) {
      page.lastOrNull()
    } else null

    return ListTablesResponse {
      tableNames = page
      this.lastEvaluatedTableName = lastEvaluatedTableName
    }
  }

  @OptIn(InternalApi::class)
  override suspend fun describeTable(input: DescribeTableRequest): DescribeTableResponse {
    val table = getTableOrNull(input.tableName!!)
      ?: throw resourceNotFoundException(input.tableName)

    return DescribeTableResponse {
      this.table = TableDescription {
        tableName = table.tableName
        tableStatus = table.tableStatus
        deletionProtectionEnabled = table.deletionProtectionEnabled
        tableArn = table.tableArn
      }
    }
  }

  @OptIn(InternalApi::class)
  private fun resourceNotFoundException(tableName: String?): ResourceNotFoundException = ResourceNotFoundException {
    message = "Table $tableName does not exist"
  }.apply {
    sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "ResourceNotFoundException"
  }

  override suspend fun updateTable(input: UpdateTableRequest): UpdateTableResponse {
    if (updateFailsWithError) {
      throw DynamoDbException("Update table failed")
    }

    val tableName = input.tableName!!
    val table = getActiveTable(tableName)

    input.deletionProtectionEnabled?.let { enabled ->
      table.deletionProtectionEnabled = enabled
    }

    // Simulate the updating state
    transitionTableStatus(tableName, TableStatus.Updating, TableStatus.Active, 2.seconds)

    return UpdateTableResponse {
      tableDescription = TableDescription {
        this.tableName = table.tableName
        tableStatus = TableStatus.Updating
        deletionProtectionEnabled = table.deletionProtectionEnabled
        tableArn = table.tableArn
      }
    }
  }

  override suspend fun deleteTable(input: DeleteTableRequest): DeleteTableResponse {
    if (deleteFailsWithError) {
      throw DynamoDbException("Delete table failed")
    }

    val tableName = input.tableName!!
    val table = getActiveTable(tableName)

    // Simulate the deleting state and eventual removal
    transitionTableStatus(tableName, TableStatus.Deleting, null, 5.seconds)

    return DeleteTableResponse {
      tableDescription = TableDescription {
        this.tableName = table.tableName
        tableStatus = TableStatus.Deleting
        deletionProtectionEnabled = table.deletionProtectionEnabled
        tableArn = table.tableArn
      }
    }
  }

  private suspend fun transitionTableStatus(
    tableName: String,
    stateDuringTransition: TableStatus,
    transitTo: TableStatus?,
    duration: Duration = 5.seconds
  ) {
    val table = getTableOrNull(tableName) ?: return
    table.tableStatus = stateDuringTransition
    val scope =
        (currentCoroutineContext().job as? TestScope)?.backgroundScope ?: CoroutineScope(Dispatchers.Default)
    scope.launch {
      delay(duration)
      if (transitTo != null) {
        table.tableStatus = transitTo
      } else {
        // Remove the table (simulating complete deletion)
        tables.removeIf { it.tableName == tableName }
      }
    }
  }
}
