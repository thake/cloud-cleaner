package cloudcleaner.aws.resources.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteBucket
import aws.sdk.kotlin.services.s3.getBucketVersioning
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.BucketLocationConstraint
import aws.sdk.kotlin.services.s3.model.CreateBucketConfiguration
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.content.ByteStream
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.shouldBeEquivalent
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class S3ClientBehaviorIntegrationTest {
  private val realClient = S3Client {
    endpointUrl = LocalStack.localstackUrl
    region = "eu-central-1"
  }

  @Test fun `headBucket response for non existing bucket should be the same`() = runTest {
    val stub = S3ClientStub()
    val bucketName = randomName()
    val expected = shouldThrow<ServiceException> { realClient.headBucket { bucket = bucketName } }
    val actual = shouldThrow<ServiceException> { stub.headBucket { bucket = bucketName } }
    actual.shouldBeEquivalent(expected)
  }

  @Test fun `getBucketVersioning response for non existing bucket should be the same`() = runTest {
    val stub = S3ClientStub()
    val bucketName = randomName()
    val actual = shouldThrow<ServiceException> { stub.getBucketVersioning { bucket = bucketName } }
    val expected = shouldThrow<ServiceException> { realClient.getBucketVersioning { bucket = bucketName } }
    actual.shouldBeEquivalent(expected)
  }
  @Test fun `deleteBucket response for non existing bucket should be the same`() = runTest {
    val stub = S3ClientStub()
    val bucketName = randomName()
    val expected = shouldThrow<ServiceException> { realClient.deleteBucket { bucket = bucketName} }
    val actual = shouldThrow<ServiceException> { stub.deleteBucket { bucket = bucketName } }
    actual.shouldBeEquivalent(expected)
  }

  @Test fun `deleteBucket response if bucket contains objects should be the same`() = runTest {
    val stub = S3ClientStub()
    val name = randomName()
    realClient.createBucket(CreateBucketRequest {
      bucket = name
      this.createBucketConfiguration = CreateBucketConfiguration { locationConstraint = BucketLocationConstraint.EuCentral1 }
    })
    realClient.putObject(PutObjectRequest { bucket = name; this.key = "file.txt"; this.body = ByteStream.fromString("test data") })
    val expected = shouldThrow<ServiceException> { realClient.deleteBucket { bucket = name } }
    stub.buckets.add(S3ClientStub.BucketStub(name, objects = mutableListOf(S3ClientStub.ObjectStub("file.txt"))))

    val actual = shouldThrow<ServiceException> { stub.deleteBucket { bucket = name } }

    actual.shouldBeEquivalent(expected)
  }

  @Test fun `listObjectsV2 response on non existing bucket should be the same`() = runTest {
    val stub = S3ClientStub()
    val name = randomName()
    val actual = shouldThrow<ServiceException> { stub.listObjectsV2 { bucket = name } }
    val expected = shouldThrow<ServiceException> { realClient.listObjectsV2 { bucket = name } }

    actual.shouldBeEquivalent(expected)
  }


  private fun randomName(): String = "test-bucket-" + Uuid.random().toString().lowercase()
}
