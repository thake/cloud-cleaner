package cloudcleaner.aws.resources.cloudwatch

import cloudcleaner.aws.resources.cloudwatch.CloudWatchLogsClientStub.LogGroupStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CloudWatchLogGroupDeleterTest {
  private val cloudWatchLogsClient = CloudWatchLogsClientStub()
  private val underTest = CloudWatchLogGroupDeleter(cloudWatchLogsClient)

  @Test
  fun `delete should successfully delete a log group`() = runTest {
    // given
    val logGroup = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/my-function"),
        logGroupArn = null
    )
    cloudWatchLogsClient.logGroups.add(
        LogGroupStub(
            logGroupName = logGroup.logGroupName.value
        )
    )

    // when
    underTest.delete(logGroup)

    // then
    cloudWatchLogsClient.logGroups.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a CloudWatchLogGroup`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = StringId("invalid")
      override val name = "invalid"
      override val type = "NotACloudWatchLogGroup"
      override val properties = emptyMap<String, String>()
    }

    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should ignore not existing log group`() = runTest {
    // given
    val logGroup = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/non-existent"),
        logGroupArn = null
    )

    // when/then - should not throw
    underTest.delete(logGroup)
  }

  @Test
  fun `delete should delete multiple log groups`() = runTest {
    // given
    val logGroup1 = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/function-1"),
        logGroupArn = null
    )
    val logGroup2 = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/function-2"),
        logGroupArn = null
    )

    cloudWatchLogsClient.logGroups.add(LogGroupStub(logGroup1.logGroupName.value))
    cloudWatchLogsClient.logGroups.add(LogGroupStub(logGroup2.logGroupName.value))

    // when
    underTest.delete(logGroup1)
    underTest.delete(logGroup2)

    // then
    cloudWatchLogsClient.logGroups.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified log group`() = runTest {
    // given
    val logGroup1 = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/function-1"),
        logGroupArn = null
    )
    val logGroup2 = CloudWatchLogGroup(
        logGroupName = LogGroupName("/aws/lambda/function-2"),
        logGroupArn = null
    )

    cloudWatchLogsClient.logGroups.add(LogGroupStub(logGroup1.logGroupName.value))
    cloudWatchLogsClient.logGroups.add(LogGroupStub(logGroup2.logGroupName.value))

    // when
    underTest.delete(logGroup1)

    // then
    cloudWatchLogsClient.logGroups.shouldHaveSize(1)
    cloudWatchLogsClient.logGroups[0].logGroupName shouldBe logGroup2.logGroupName.value
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}

