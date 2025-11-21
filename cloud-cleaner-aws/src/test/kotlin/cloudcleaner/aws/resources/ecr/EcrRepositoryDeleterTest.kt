package cloudcleaner.aws.resources.ecr

import cloudcleaner.aws.resources.REGION
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class EcrRepositoryDeleterTest {
  private val ecrClient = EcrClientStub()
  private val underTest = EcrRepositoryDeleter(ecrClient)

  @Test
  fun `delete should delete repository with no images`() = runTest {
    // given
    ecrClient.repositories.add(
        EcrClientStub.RepositoryStub(
            name = "test-repo",
            arn = "arn:aws:ecr:eu-central-1:123456789012:repository/test-repo"
        )
    )
    val resource = EcrRepository(
        EcrRepositoryName(
            name = "test-repo",
            region = REGION
        ),
        arn = "arn:aws:ecr:eu-central-1:123456789012:repository/test-repo"
    )

    // when
    underTest.delete(resource)

    // then
    ecrClient.repositories.shouldBeEmpty()
  }

  @Test
  fun `delete should delete all images before deleting repository`() = runTest {
    // given
    ecrClient.repositories.add(
        EcrClientStub.RepositoryStub(
            name = "test-repo",
            arn = "arn:aws:ecr:eu-central-1:123456789012:repository/test-repo",
            images = mutableListOf(
                EcrClientStub.ImageStub(
                    imageDigest = "sha256:abc123",
                    imageTag = "latest"
                ),
                EcrClientStub.ImageStub(
                    imageDigest = "sha256:def456",
                    imageTag = "v1.0"
                )
            )
        )
    )
    val resource = EcrRepository(
        EcrRepositoryName(
            name = "test-repo",
            region = REGION
        ),
        arn = "arn:aws:ecr:eu-central-1:123456789012:repository/test-repo"
    )

    // when
    underTest.delete(resource)

    // then
    ecrClient.repositories.shouldBeEmpty()
  }

  @Test
  fun `delete should handle already deleted repository gracefully`() = runTest {
    // given
    val resource = EcrRepository(
        EcrRepositoryName(
            name = "non-existent-repo",
            region = REGION
        ),
        arn = "arn:aws:ecr:eu-central-1:123456789012:repository/non-existent-repo"
    )

    // when/then
    shouldNotThrowAny {
      underTest.delete(resource)
    }
  }

  @Test
  fun `delete should chunk images into batches of 100`() = runTest {
    // given
    val images = (0 until 250).map { index ->
      EcrClientStub.ImageStub(
          imageDigest = "sha256:abc$index",
          imageTag = "tag$index"
      )
    }
    ecrClient.repositories.add(
        EcrClientStub.RepositoryStub(
            name = "large-repo",
            arn = "arn:aws:ecr:eu-central-1:123456789012:repository/large-repo",
            images = images.toMutableList()
        )
    )
    val resource = EcrRepository(
        EcrRepositoryName(
            name = "large-repo",
            region = REGION
        ),
        arn = "arn:aws:ecr:eu-central-1:123456789012:repository/large-repo"
    )

    // when
    underTest.delete(resource)

    // then
    ecrClient.repositories.shouldBeEmpty()
  }

  @Test
  fun `delete should delete repository even with many images`() = runTest {
    // given
    val images = (0 until 10).map { index ->
      EcrClientStub.ImageStub(
          imageDigest = "sha256:abc$index",
          imageTag = "tag$index"
      )
    }
    ecrClient.repositories.add(
        EcrClientStub.RepositoryStub(
            name = "multi-image-repo",
            arn = "arn:aws:ecr:eu-central-1:123456789012:repository/multi-image-repo",
            images = images.toMutableList()
        )
    )
    val resource = EcrRepository(
        EcrRepositoryName(
            name = "multi-image-repo",
            region = REGION
        ),
        arn = "arn:aws:ecr:eu-central-1:123456789012:repository/multi-image-repo"
    )

    // when
    underTest.delete(resource)

    // then
    ecrClient.repositories.shouldBeEmpty()
  }
}

