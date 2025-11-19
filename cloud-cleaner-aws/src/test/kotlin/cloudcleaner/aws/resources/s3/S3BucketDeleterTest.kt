package cloudcleaner.aws.resources.s3

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class S3BucketDeleterTest {
  private val s3Client = S3ClientStub()
  private val underTest = S3BucketDeleter(s3Client)

  @Test
  fun `delete should remove empty bucket`() = runTest {
    // given
    val bucket = S3ClientStub.BucketStub("test-bucket")
    s3Client.buckets.add(bucket)
    val resource = S3Bucket(BucketName("test-bucket"))
    // when
    underTest.delete(resource)
    // then
    s3Client.buckets.shouldBeEmpty()
  }

  @Test
  fun `delete should remove bucket with objects`() = runTest {
    // given
    val bucket = S3ClientStub.BucketStub(
        "test-bucket",
        objects = mutableListOf(
            S3ClientStub.ObjectStub("file1.txt"),
            S3ClientStub.ObjectStub("file2.txt"),
            S3ClientStub.ObjectStub("folder/file3.txt"),
        ))
    s3Client.buckets.add(bucket)
    val resource = S3Bucket(BucketName("test-bucket"))
    // when
    underTest.delete(resource)
    // then
    s3Client.buckets.shouldBeEmpty()
  }

  @Test
  fun `delete should remove versioned bucket with versions and delete markers`() = runTest {
    // given
    val bucket = S3ClientStub.BucketStub(
        "versioned-bucket",
        versioningEnabled = true,
        versions = mutableListOf(
            S3ClientStub.ObjectVersionStub("file1.txt", "v1"),
            S3ClientStub.ObjectVersionStub("file1.txt", "v2"),
            S3ClientStub.ObjectVersionStub("file2.txt", "v1"),
        ),
        deleteMarkers = mutableListOf(
            S3ClientStub.DeleteMarkerStub("file3.txt", "dm1"),
        ))
    s3Client.buckets.add(bucket)
    val resource = S3Bucket(BucketName("versioned-bucket"))
    // when
    underTest.delete(resource)
    // then
    s3Client.buckets.shouldBeEmpty()
  }

  @Test
  fun `delete should handle non-existent bucket gracefully`() = runTest {
    // given
    val resource = S3Bucket(BucketName("non-existent-bucket"))
    // when/then - should not throw
    underTest.delete(resource)
  }

  @Test
  fun `delete should handle bucket with many objects in batches`() = runTest {
    // given
    val objects = mutableListOf<S3ClientStub.ObjectStub>()
    repeat(2500) { // More than 2 batches (1000 per batch)
      objects.add(S3ClientStub.ObjectStub("file$it.txt"))
    }
    val bucket = S3ClientStub.BucketStub("large-bucket", objects = objects)
    s3Client.buckets.add(bucket)
    val resource = S3Bucket(BucketName("large-bucket"))
    // when
    underTest.delete(resource)
    // then
    s3Client.buckets.shouldBeEmpty()
  }

  @Test
  fun `delete should throw exception for invalid resource type`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = cloudcleaner.resources.StringId("test")
      override val name = "test"
      override val type = "InvalidType"
      override val properties = emptyMap<String, String>()
      override val dependsOn = emptySet<cloudcleaner.resources.Id>()
      override val contains = emptySet<cloudcleaner.resources.Id>()
    }
    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should handle bucket that was already deleted during operation`() = runTest {
    // given
    val bucket = S3ClientStub.BucketStub("test-bucket")
    s3Client.buckets.add(bucket)
    val resource = S3Bucket(BucketName("test-bucket"))
    // Simulate bucket being deleted externally
    s3Client.buckets.clear()
    // when/then - should not throw
    underTest.delete(resource)
  }


  @Test
  fun `resource should have correct type`() = runTest {
    // given
    val bucket = S3Bucket(BucketName("test"))
    // then
    bucket.type.shouldBe("S3Bucket")
  }

  @Test
  fun `resource id should be bucket name`() = runTest {
    // given
    val bucketName = BucketName("my-bucket")
    val bucket = S3Bucket(bucketName)
    // then
    bucket.id.value.shouldBe("arn:aws:s3:::my-bucket")
    bucket.name.shouldBe("my-bucket")
  }
}

