package cloudcleaner.aws.resources.dynamodb

import aws.sdk.kotlin.services.dynamodb.model.DynamoDbException
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import cloudcleaner.aws.resources.dynamodb.DynamoDbClientStub.TableStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test

class DynamoDbTableDeleterTest {
  private val dynamoDbClient = DynamoDbClientStub()
  private val underTest = DynamoDbTableDeleter(dynamoDbClient)

  @Test
  fun `delete should successfully delete a table`() = runTest {
    // given
    val table = DynamoDbTable(
        tableName = TableName("test-table"),
        tableArn = null,
        deletionProtectionEnabled = false,
    )
    dynamoDbClient.tables.add(TableStub(table.tableName.value))

    // when
    underTest.delete(table)

    // then
    dynamoDbClient.getActiveTableOrNull("test-table").shouldBeNull()
  }

  @Test
  fun `delete should disable deletion protection before deleting`() = runTest {
    // given
    val table = DynamoDbTable(
        tableName = TableName("protected-table"),
        tableArn = null,
        deletionProtectionEnabled = true,
    )
    val tableStub = TableStub("protected-table", deletionProtectionEnabled = true)
    dynamoDbClient.tables.add(tableStub)

    // when
    underTest.delete(table)

    // then
    dynamoDbClient.getActiveTableOrNull("protected-table").shouldBeNull()
  }

  @Test
  fun `delete should wait for completion if table is already being deleted`() = runTest {
    withContext(Dispatchers.Default) {
      // given
      val table = DynamoDbTable(
          tableName = TableName("deleting-table"),
          tableArn = null,
          deletionProtectionEnabled = false,
      )
      dynamoDbClient.tables.add(TableStub("deleting-table", tableStatus = TableStatus.Deleting))
      launch {
        // Simulate table deletion completing after some time
        delay(100)
        dynamoDbClient.tables.removeIf { it.tableName == "deleting-table" }
      }

      // when
      underTest.delete(table)

      // then - should complete without error and table should be gone
      dynamoDbClient.getActiveTableOrNull("deleting-table").shouldBeNull()
    }
  }

  @Test
  fun `delete should throw exception for non-DynamoDbTable resource`() = runTest {
    // given
    val nonDynamoDbResource = object : cloudcleaner.resources.Resource {
      override val id = cloudcleaner.resources.StringId("test")
      override val name = "test"
      override val type = "NotDynamoDb"
      override val properties = emptyMap<String, String>()
      override val contains = emptySet<cloudcleaner.resources.Id>()
      override val dependsOn = emptySet<cloudcleaner.resources.Id>()
    }

    // when & then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(nonDynamoDbResource)
    }
  }

  @Test
  fun `delete should propagate exceptions from DynamoDB client`() = runTest {
    // given
    val table = DynamoDbTable(
        tableName = TableName("test-table"),
        tableArn = null,
        deletionProtectionEnabled = false,
    )
    dynamoDbClient.tables.add(TableStub(table.tableName.value))
    dynamoDbClient.deleteFailsWithError = true

    // when & then
    shouldThrow<DynamoDbException> {
      underTest.delete(table)
    }
  }

  @Test
  fun `delete should handle update failure when disabling protection`() = runTest {
    // given
    val table = DynamoDbTable(
        tableName = TableName("protected-table"),
        tableArn = null,
        deletionProtectionEnabled = true,
    )
    dynamoDbClient.tables.add(TableStub("protected-table", deletionProtectionEnabled = true))
    dynamoDbClient.updateFailsWithError = true

    // when & then
    shouldThrow<DynamoDbException> {
      underTest.delete(table)
    }
  }

  @Test
  fun `delete should handle table in updating state`() = runTest {
    // given
    val table = DynamoDbTable(
        tableName = TableName("updating-table"),
        tableArn = null,
        deletionProtectionEnabled = false,
    )
    dynamoDbClient.tables.add(TableStub(table.tableName.value, tableStatus = TableStatus.Updating))

    // when
    underTest.delete(table)

    // then - should complete without error and table should be gone
    dynamoDbClient.getActiveTableOrNull("updating-table").shouldBeNull()
  }
}
