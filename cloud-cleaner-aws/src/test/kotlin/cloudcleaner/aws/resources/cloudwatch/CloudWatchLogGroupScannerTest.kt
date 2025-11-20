package cloudcleaner.aws.resources.cloudwatch

import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.cloudwatch.CloudWatchLogsClientStub.LogGroupStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CloudWatchLogGroupScannerTest {
  private val cloudWatchLogsClient = CloudWatchLogsClientStub()
  private val underTest = CloudWatchLogGroupScanner(cloudWatchLogsClient)

  @Test
  fun `scan should return empty list when no log groups are present`() = runTest {
    // when
    val logGroups = underTest.scan()

    // then
    logGroups.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated log groups`() = runTest {
    // given
    repeat(100) {
      cloudWatchLogsClient.logGroups.add(
          LogGroupStub(
              logGroupName = "/aws/lambda/function-$it",
              logGroupArn = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:/aws/lambda/function-$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualLogGroups = actualFlow.toList()
    actualLogGroups.shouldHaveSize(100)
  }

  @Test
  fun `scan should return log group details correctly`() = runTest {
    // given
    val logGroup = LogGroupStub(
        logGroupName = "/aws/lambda/my-function",
        logGroupArn = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:/aws/lambda/my-function"
    )
    cloudWatchLogsClient.logGroups.add(logGroup)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualLogGroups = actualFlow.toList()
    actualLogGroups.shouldHaveSize(1)
    val actualLogGroup = actualLogGroups.first()
    actualLogGroup.logGroupName shouldBe LogGroupName("/aws/lambda/my-function")
    actualLogGroup.logGroupArn.value shouldBe "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:/aws/lambda/my-function"
    actualLogGroup.dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle multiple log groups`() = runTest {
    // given
    cloudWatchLogsClient.logGroups.add(
        LogGroupStub(
            logGroupName = "/aws/lambda/function-1",
            logGroupArn = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:/aws/lambda/function-1"
        )
    )
    cloudWatchLogsClient.logGroups.add(
        LogGroupStub(
            logGroupName = "/aws/lambda/function-2",
            logGroupArn = "arn:aws:logs:$REGION:$ACCOUNT_ID:log-group:/aws/lambda/function-2"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualLogGroups = actualFlow.toList()
    actualLogGroups.shouldHaveSize(2)
  }
}

