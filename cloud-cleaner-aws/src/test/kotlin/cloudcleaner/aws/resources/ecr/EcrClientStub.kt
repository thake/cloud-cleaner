@file:OptIn(InternalApi::class)
@file:Suppress("TestFunctionName")

package cloudcleaner.aws.resources.ecr

import aws.sdk.kotlin.services.ecr.EcrClient
import aws.sdk.kotlin.services.ecr.model.BatchDeleteImageRequest
import aws.sdk.kotlin.services.ecr.model.BatchDeleteImageResponse
import aws.sdk.kotlin.services.ecr.model.DeleteRepositoryRequest
import aws.sdk.kotlin.services.ecr.model.DeleteRepositoryResponse
import aws.sdk.kotlin.services.ecr.model.DescribeRepositoriesRequest
import aws.sdk.kotlin.services.ecr.model.DescribeRepositoriesResponse
import aws.sdk.kotlin.services.ecr.model.ImageIdentifier
import aws.sdk.kotlin.services.ecr.model.ListImagesRequest
import aws.sdk.kotlin.services.ecr.model.ListImagesResponse
import aws.sdk.kotlin.services.ecr.model.Repository
import aws.sdk.kotlin.services.ecr.model.RepositoryNotFoundException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import io.mockk.mockk

class EcrClientStub(
    val delegate: EcrClient = mockk<EcrClient>(),
) : EcrClient by delegate {
  val repositories = mutableListOf<RepositoryStub>()

  data class RepositoryStub(
      val name: String,
      val arn: String,
      val images: MutableList<ImageStub> = mutableListOf()
  )

  data class ImageStub(
      val imageDigest: String,
      val imageTag: String? = null
  )

  override suspend fun describeRepositories(input: DescribeRepositoriesRequest): DescribeRepositoriesResponse {
    return DescribeRepositoriesResponse {
      repositories = this@EcrClientStub.repositories.map {
        Repository {
          repositoryName = it.name
          repositoryArn = it.arn
        }
      }
      nextToken = null
    }
  }

  override suspend fun listImages(input: ListImagesRequest): ListImagesResponse {
    val repository = repositories.find { it.name == input.repositoryName }
        ?: throw RepositoryNotFoundError()

    return ListImagesResponse {
      imageIds = repository.images.map {
        ImageIdentifier {
          imageDigest = it.imageDigest
          imageTag = it.imageTag
        }
      }
      nextToken = null
    }
  }

  override suspend fun batchDeleteImage(input: BatchDeleteImageRequest): BatchDeleteImageResponse {
    val repository = repositories.find { it.name == input.repositoryName }
        ?: throw RepositoryNotFoundError()

    input.imageIds?.forEach { imageId ->
      repository.images.removeIf {
        it.imageDigest == imageId.imageDigest || it.imageTag == imageId.imageTag
      }
    }

    return BatchDeleteImageResponse { }
  }

  override suspend fun deleteRepository(input: DeleteRepositoryRequest): DeleteRepositoryResponse {
    val removed = repositories.removeIf { it.name == input.repositoryName }
    if (!removed) {
      throw RepositoryNotFoundError()
    }
    return DeleteRepositoryResponse { }
  }
}

fun RepositoryNotFoundError() = RepositoryNotFoundException{}.apply {
  sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "RepositoryNotFoundException"
}

