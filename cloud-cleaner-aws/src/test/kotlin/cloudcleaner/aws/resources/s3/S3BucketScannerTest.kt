package cloudcleaner.aws.resources.s3

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class S3BucketScannerTest {
  private val s3Client = S3ClientStub()
  private val underTest = S3BucketScanner(s3Client)

  @Test
  fun `scan should return empty list when no buckets are present`() = runTest {
    val buckets = underTest.scan()
    // then
    buckets.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of buckets`() = runTest {
    // given
    repeat(10) {
      s3Client.buckets.add(S3ClientStub.BucketStub(name = "bucket$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualBuckets = actualFlow.toList()
    actualBuckets.shouldHaveSize(10)
    actualBuckets.map { it.bucketName.value }.shouldContainExactlyInAnyOrder(
        (0..9).map { "bucket$it" }
    )
  }

  @Test
  fun `scan should handle large number of buckets`() = runTest {
    // given
    repeat(250) {
      s3Client.buckets.add(S3ClientStub.BucketStub(name = "bucket$it"))
    }
    // when
    val actualFlow = underTest.scan()
    // then
    val actualBuckets = actualFlow.toList()
    actualBuckets.shouldHaveSize(250)
  }
}

