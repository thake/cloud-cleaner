package cloudcleaner.aws.resources.ecr

import aws.sdk.kotlin.services.ecr.EcrClient
import aws.sdk.kotlin.services.ecr.batchDeleteImage
import aws.sdk.kotlin.services.ecr.deleteRepository
import aws.sdk.kotlin.services.ecr.model.ImageIdentifier
import aws.sdk.kotlin.services.ecr.paginators.describeRepositoriesPaginated
import aws.sdk.kotlin.services.ecr.paginators.listImagesPaginated
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

private const val TYPE = "EcrRepository"

class EcrRepositoryResourceDefinitionFactory : AwsResourceDefinitionFactory<EcrRepository> {
  override val type: String = TYPE

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<EcrRepository> {
    val client = EcrClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = EcrRepositoryDeleter(client),
        resourceScanner = EcrRepositoryScanner(client, awsConnectionInformation.region),
        close = { client.close() },
    )
  }
}

class EcrRepositoryScanner(private val ecrClient: EcrClient, val region: String) : ResourceScanner<EcrRepository> {
  override fun scan(): Flow<EcrRepository> = flow {
    ecrClient.describeRepositoriesPaginated()
        .collect { response ->
          response.repositories?.forEach { repository ->
            val repositoryName = repository.repositoryName ?: return@forEach
            val repositoryArn = repository.repositoryArn ?: return@forEach
            emit(EcrRepository(repositoryName = EcrRepositoryName(repositoryName, region), arn = repositoryArn))          }
        }
  }
}

private const val MAX_DELETES_IN_BATCH = 100

class EcrRepositoryDeleter(private val ecrClient: EcrClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val repository = resource as? EcrRepository ?: throw IllegalArgumentException("Resource not an EcrRepository")
    val repositoryName = repository.name

    try {
      // Delete all images in the repository first
      deleteAllImages(repositoryName)

      // Delete the repository
      ecrClient.deleteRepository {
        this.repositoryName = repositoryName
        this.force = true
      }
    } catch (e: ServiceException) {
      if (e.sdkErrorMetadata.errorCode == "RepositoryNotFoundException") {
        logger.debug { "Repository $repositoryName already deleted. Ignoring." }
      } else {
        logger.error(e) { "Failed deleting repository $repositoryName: ${e.message}" }
        throw e
      }
    }
  }

  private suspend fun deleteAllImages(repositoryName: String) {
    try {
      ecrClient.listImagesPaginated {
        this.repositoryName = repositoryName
      }.collect { response ->
        val imageIds = response.imageIds?.mapNotNull { imageId ->
          if (imageId.imageDigest != null || imageId.imageTag != null) {
            ImageIdentifier {
              imageDigest = imageId.imageDigest
              imageTag = imageId.imageTag
            }
          } else null
        } ?: emptyList()

        if (imageIds.isNotEmpty()) {
          // ECR allows up to 100 images per batch delete
          imageIds.chunked(MAX_DELETES_IN_BATCH).forEach { chunk ->
            ecrClient.batchDeleteImage {
              this.repositoryName = repositoryName
              this.imageIds = chunk
            }
          }
        }
      }
    } catch (e: ServiceException) {
      if (e.sdkErrorMetadata.errorCode == "RepositoryNotFoundException") {
        logger.debug { "Repository $repositoryName not found during image deletion. Ignoring." }
      } else {
        logger.warn(e) { "Failed to delete images from repository $repositoryName: ${e.message}" }
        // Continue with repository deletion even if image deletion fails
      }
    }
  }
}
data class EcrRepositoryName(val name: String, val region: String): Id {
  override fun toString(): String = "$name ($region)"
}
data class EcrRepository(
    val repositoryName: EcrRepositoryName,
    val arn: String,
) : Resource {
  override val id: EcrRepositoryName = repositoryName
  override val containedResources: Set<Id> = emptySet()
  override val dependsOn: Set<Id> = emptySet()
  override val name: String = repositoryName.name
  override val type: String = TYPE
  override val properties: Map<String, String> = mapOf("arn" to arn)

  override fun toString(): String = repositoryName.name
}

