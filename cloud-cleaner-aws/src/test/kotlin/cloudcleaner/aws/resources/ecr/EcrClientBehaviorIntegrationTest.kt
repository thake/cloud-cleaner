package cloudcleaner.aws.resources.ecr

import aws.sdk.kotlin.services.ecr.EcrClient
import aws.sdk.kotlin.services.ecr.batchDeleteImage
import aws.sdk.kotlin.services.ecr.deleteRepository
import aws.sdk.kotlin.services.ecr.listImages
import aws.sdk.kotlin.services.ecr.model.ImageIdentifier
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Disabled("localstack throws internal errors")
@OptIn(ExperimentalUuidApi::class)
class EcrClientBehaviorIntegrationTest {
  private val realClient = EcrClient {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }

  @Test
  fun `listImages response for non existing repository should be the same`() = runTest {
    val stub = EcrClientStub()
    val repositoryName = randomName()
    val expected = shouldThrow<ServiceException> {
      realClient.listImages { this.repositoryName = repositoryName }
    }
    val actual = shouldThrow<ServiceException> {
      stub.listImages { this.repositoryName = repositoryName }
    }
    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `deleteRepository response for non existing repository should be the same`() = runTest {
    val stub = EcrClientStub()
    val repositoryName = randomName()
    val expected = shouldThrow<ServiceException> {
      realClient.deleteRepository { this.repositoryName = repositoryName }
    }
    val actual = shouldThrow<ServiceException> {
      stub.deleteRepository { this.repositoryName = repositoryName }
    }
    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `batchDeleteImage response for non existing repository should be the same`() = runTest {
    val stub = EcrClientStub()
    val repositoryName = randomName()
    val imageIds = listOf(
        ImageIdentifier {
          imageDigest = "sha256:abc123"
          imageTag = "latest"
        }
    )
    val expected = shouldThrow<ServiceException> {
      realClient.batchDeleteImage {
        this.repositoryName = repositoryName
        this.imageIds = imageIds
      }
    }
    val actual = shouldThrow<ServiceException> {
      stub.batchDeleteImage {
        this.repositoryName = repositoryName
        this.imageIds = imageIds
      }
    }
    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `deleteRepository with force flag should succeed even with images in stub`() = runTest {
    val stub = EcrClientStub()
    val name = randomName()

    // Set up stub with repository containing images
    stub.repositories.add(
        EcrClientStub.RepositoryStub(
            name = name,
            arn = "arn:aws:ecr:eu-central-1:123456789012:repository/$name",
            images = mutableListOf(
                EcrClientStub.ImageStub(
                    imageDigest = "sha256:abcd1234",
                    imageTag = "test"
                )
            )
        )
    )

    // With force flag, deletion should succeed regardless of images
    stub.deleteRepository {
      repositoryName = name
      force = true
    }
  }

  private fun randomName(): String = "test-repo-" + Uuid.random().toString().lowercase().take(20)
}

