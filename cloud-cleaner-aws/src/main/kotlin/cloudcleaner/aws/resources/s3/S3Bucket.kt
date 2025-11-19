package cloudcleaner.aws.resources.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteBucket
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.getBucketVersioning
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.BucketVersioningStatus
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.paginators.listBucketsPaginated
import aws.sdk.kotlin.services.s3.paginators.listObjectsV2Paginated
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import cloudcleaner.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

typealias BucketName = StringId

private const val TYPE = "S3Bucket"

class S3BucketResourceDefinitionFactory : AwsResourceDefinitionFactory<S3Bucket> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<S3Bucket> {
    val client = S3Client {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = S3BucketDeleter(client),
        resourceScanner = S3BucketScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class S3BucketScanner(private val s3Client: S3Client, val region: String) : ResourceScanner<S3Bucket> {
  override fun scan(): Flow<S3Bucket> = flow {
    try {
      s3Client.listBucketsPaginated{
        bucketRegion = region
      }.collect { response ->
        response.buckets?.forEach { bucket ->
          val name = bucket.name ?: return@forEach
          val bucketName = BucketName(name)
          emit(S3Bucket(bucketName = bucketName))
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to list S3 buckets: ${e.message}" }
      throw e
    }
  }
}

class S3BucketDeleter(private val s3Client: S3Client) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val bucket = resource as? S3Bucket ?: throw IllegalArgumentException("Resource not an S3Bucket")
    val bucketName = bucket.bucketName.value

    try {
      if (!bucketExists(bucketName)) {
        logger.info { "S3 bucket $bucketName already deleted. Ignoring." }
        return
      }
      val versioningEnabled = isVersioningEnabled(bucketName)
      if (versioningEnabled) {
        deleteAllVersions(bucketName)
      } else {
        deleteAllObjects(bucketName)
      }
      s3Client.deleteBucket { this.bucket = bucketName }
    } catch (e: ServiceException) {
      if (e.sdkErrorMetadata.errorCode == "NoSuchBucket") {
        logger.info { "Bucket $bucketName disappeared during deletion." }
      } else {
        logger.error(e) { "Failed deleting bucket $bucketName: ${e.message}" }
        throw e
      }
    }
  }

  private suspend fun isVersioningEnabled(bucketName: String): Boolean =
      try {
        val versioning = s3Client.getBucketVersioning { this.bucket = bucketName }
        versioning.status == BucketVersioningStatus.Enabled
      } catch (_: Exception) {
        false
      }

  private suspend fun bucketExists(bucketName: String): Boolean =
      try {
        s3Client.headBucket { this.bucket = bucketName }
        true
      } catch (e: ServiceException) {
        when (e.sdkErrorMetadata.errorCode) {
            "NoSuchBucket", "NotFound" -> false
            "301: Moved Permanently" -> false
            else -> throw e
        }
      }

  private suspend fun deleteAllObjects(bucketName: String) {
    s3Client
        .listObjectsV2Paginated { bucket = bucketName }
        .collect { response ->
          val identifiers = response.contents?.mapNotNull { obj -> obj.key?.let { k -> ObjectIdentifier { key = k } } } ?: emptyList()
          batchDelete(bucketName, identifiers)
        }
  }

  private suspend fun deleteAllVersions(bucketName: String) {
    s3Client
        .listObjectVersionsPaginated { bucket = bucketName }
        .collect { response ->
          val identifiers = mutableListOf<ObjectIdentifier>()
          response.versions?.forEach { v ->
            val key = v.key ?: return@forEach
            identifiers.add(
                ObjectIdentifier {
                  this.key = key
                  this.versionId = v.versionId
                })
          }
          response.deleteMarkers?.forEach { dm ->
            val key = dm.key ?: return@forEach
            identifiers.add(
                ObjectIdentifier {
                  this.key = key
                  this.versionId = dm.versionId
                })
          }
          batchDelete(bucketName, identifiers)
        }
  }

  private suspend fun batchDelete(bucketName: String, identifiers: List<ObjectIdentifier>) {
    if (identifiers.isEmpty()) return
    identifiers.chunked(1000).forEach { chunk ->
      s3Client.deleteObjects {
        bucket = bucketName
        delete = Delete { objects = chunk }
      }
    }
  }
}

data class S3Bucket(
    val bucketName: BucketName,
) : Resource {
  override val id: Arn
    get() = Arn("arn:aws:s3:::$bucketName")

  override val contains: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = emptySet()
  override val name: String = bucketName.value
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()

  override fun toString(): String = bucketName.value
}
