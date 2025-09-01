package cloudcleaner.aws.resources.dynamodb

import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.dynamodb.DynamoDbClientStub.TableStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DynamoDbTableScannerTest {
  private val dynamoDbClient = DynamoDbClientStub()
  private val underTest = DynamoDbTableScanner(dynamoDbClient)

  @Test
  fun `scan should return empty list when no tables are present`() = runTest {
    val tables = underTest.scan()
    // then
    tables.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of active tables`() = runTest {
    // given
    repeat(10) {
      dynamoDbClient.tables.add(TableStub(tableName = "table$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(10)
    actualTables.map { it.tableName.value }.shouldContainExactlyInAnyOrder(
      (0..9).map { "table$it" }
    )
  }

  @Test
  fun `scan should not return deleting tables`() = runTest {
    // given
    val tables = listOf(
        TableStub("table1", tableStatus = TableStatus.Deleting),
        TableStub("table2", tableStatus = TableStatus.Active),
    )
    dynamoDbClient.tables.addAll(tables)
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(1)
    actualTables.first().tableName.value.shouldBe("table2")
  }

  @Test
  fun `scan should include deletion protection status`() = runTest {
    // given
    val tables = listOf(
        TableStub("table1", deletionProtectionEnabled = true),
        TableStub("table2", deletionProtectionEnabled = false),
    )
    dynamoDbClient.tables.addAll(tables)
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(2)

    val table1 = actualTables.first { it.tableName.value == "table1" }
    table1.deletionProtectionEnabled.shouldBe(true)

    val table2 = actualTables.first { it.tableName.value == "table2" }
    table2.deletionProtectionEnabled.shouldBe(false)
  }

  @Test
  fun `scan should include table ARN`() = runTest {
    // given
    dynamoDbClient.tables.add(TableStub("test-table"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(1)
    actualTables.first().tableArn?.value.shouldBe("arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/test-table")
  }

  @Test
  fun `scan should handle large number of tables with pagination`() = runTest {
    // given
    repeat(250) {
      dynamoDbClient.tables.add(TableStub(tableName = "table$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(250)
  }

  @Test
  fun `scan should set correct resource type`() = runTest {
    // given
    dynamoDbClient.tables.add(TableStub("test-table"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualTables = actualFlow.toList()
    actualTables.shouldHaveSize(1)
    actualTables.first().type.shouldBe("DynamoDbTable")
  }
}
