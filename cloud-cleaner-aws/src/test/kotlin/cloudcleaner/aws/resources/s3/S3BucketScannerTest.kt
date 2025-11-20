package cloudcleaner.aws.resources.s3

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class S3BucketScannerTest {
  private val s3Client = S3ClientStub()
  private val underTest = S3BucketScanner(s3Client, "eu-central-1")

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
    actualBuckets.map { it.bucketName }.shouldContainExactlyInAnyOrder(
        (0..9).map { "bucket$it" }
    )
  }

  @Test
  fun `scan should return list of buckets in region`() = runTest {
    // given
    val underTest = S3BucketScanner(s3Client, "eu-central-1")
    s3Client.buckets.add(S3ClientStub.BucketStub(name = "bucketA", region = "us-east-1"))
    s3Client.buckets.add(S3ClientStub.BucketStub(name = "bucketB", region = "eu-central-1"))
    // when
    val actualFlow = underTest.scan()
    // then
    val actualBuckets = actualFlow.toList()
    actualBuckets.shouldHaveSize(1)
    actualBuckets.first().bucketName shouldBe "bucketB"
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

