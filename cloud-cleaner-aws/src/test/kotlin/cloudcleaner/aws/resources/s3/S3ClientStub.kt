@file:OptIn(InternalApi::class)
@file:Suppress("TestFunctionName")

package cloudcleaner.aws.resources.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.Bucket
import aws.sdk.kotlin.services.s3.model.BucketVersioningStatus
import aws.sdk.kotlin.services.s3.model.DeleteBucketRequest
import aws.sdk.kotlin.services.s3.model.DeleteBucketResponse
import aws.sdk.kotlin.services.s3.model.DeleteMarkerEntry
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsResponse
import aws.sdk.kotlin.services.s3.model.GetBucketVersioningRequest
import aws.sdk.kotlin.services.s3.model.GetBucketVersioningResponse
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.HeadBucketResponse
import aws.sdk.kotlin.services.s3.model.ListBucketsRequest
import aws.sdk.kotlin.services.s3.model.ListBucketsResponse
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.Object
import aws.sdk.kotlin.services.s3.model.ObjectVersion
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import io.mockk.mockk

class S3ClientStub(
    val delegate: S3Client = mockk<S3Client>(),
) : S3Client by delegate {
  val buckets = mutableListOf<BucketStub>()

  data class BucketStub(
      val name: String,
      val versioningEnabled: Boolean = false,
      val objects: MutableList<ObjectStub> = mutableListOf(),
      val versions: MutableList<ObjectVersionStub> = mutableListOf(),
      val deleteMarkers: MutableList<DeleteMarkerStub> = mutableListOf(),
  )

  data class ObjectStub(val key: String)

  data class ObjectVersionStub(val key: String, val versionId: String)

  data class DeleteMarkerStub(val key: String, val versionId: String)

  override suspend fun listBuckets(input: ListBucketsRequest): ListBucketsResponse = ListBucketsResponse {
    buckets = this@S3ClientStub.buckets.map { Bucket { name = it.name } }
  }

  override suspend fun getBucketVersioning(input: GetBucketVersioningRequest): GetBucketVersioningResponse {
    val bucket = buckets.find { it.name == input.bucket } ?: throw S3Exception(code = "NoSuchBucket")
    return GetBucketVersioningResponse {
      status = if (bucket.versioningEnabled) BucketVersioningStatus.Enabled else BucketVersioningStatus.Suspended
    }
  }

  override suspend fun headBucket(input: HeadBucketRequest): HeadBucketResponse {
    buckets.find { it.name == input.bucket } ?: throw NotFound()
    return HeadBucketResponse {}
  }

  override suspend fun listObjectsV2(input: ListObjectsV2Request): ListObjectsV2Response {
    val bucket = buckets.find { it.name == input.bucket } ?: throw NoSuchBucket()
    return ListObjectsV2Response {
      contents = bucket.objects.map { Object { key = it.key } }
      isTruncated = false
    }
  }

  override suspend fun listObjectVersions(input: ListObjectVersionsRequest): ListObjectVersionsResponse {
    val bucket = buckets.find { it.name == input.bucket } ?: throw S3Exception(code = "NoSuchBucket")
    return ListObjectVersionsResponse {
      versions =
          bucket.versions.map {
            ObjectVersion {
              key = it.key
              versionId = it.versionId
            }
          }
      deleteMarkers =
          bucket.deleteMarkers.map {
            DeleteMarkerEntry {
              key = it.key
              versionId = it.versionId
            }
          }
      isTruncated = false
    }
  }

  override suspend fun deleteObjects(input: DeleteObjectsRequest): DeleteObjectsResponse {
    val bucket = buckets.find { it.name == input.bucket } ?: throw S3Exception(code = "NoSuchBucket")
    input.delete?.objects?.forEach { ident ->
      if (ident.versionId != null) {
        bucket.versions.removeIf { it.key == ident.key && it.versionId == ident.versionId }
        bucket.deleteMarkers.removeIf { it.key == ident.key && it.versionId == ident.versionId }
      } else {
        bucket.objects.removeIf { it.key == ident.key }
      }
    }
    return DeleteObjectsResponse {}
  }

  override suspend fun deleteBucket(input: DeleteBucketRequest): DeleteBucketResponse {
    val bucket = buckets.find { it.name == input.bucket } ?: throw S3Exception(code = "NoSuchBucket")
    if (bucket.objects.isNotEmpty() || bucket.versions.isNotEmpty() || bucket.deleteMarkers.isNotEmpty()) {
      throw S3Exception(code = "BucketNotEmpty")
    }
    buckets.remove(bucket)
    return DeleteBucketResponse {}
  }

}

fun NotFound() = NotFound {}.apply { sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "NotFound" }

fun NoSuchBucket() = NoSuchBucket {}.apply { sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "NoSuchBucket" }

fun S3Exception(code: String, message: String = "S3 exception") =
    S3Exception(message).apply { sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = code }
