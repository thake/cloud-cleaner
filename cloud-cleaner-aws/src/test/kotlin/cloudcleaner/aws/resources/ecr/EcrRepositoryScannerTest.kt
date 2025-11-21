package cloudcleaner.aws.resources.ecr

import cloudcleaner.aws.resources.REGION
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class EcrRepositoryScannerTest {
  private val ecrClient = EcrClientStub()
  private val underTest = EcrRepositoryScanner(ecrClient, REGION)

  @Test
  fun `scan should return empty list when no repositories are present`() = runTest {
    // when
    val repositories = underTest.scan()

    // then
    repositories.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of repositories`() = runTest {
    // given
    repeat(10) {
      ecrClient.repositories.add(
          EcrClientStub.RepositoryStub(
              name = "repo$it",
              arn = "arn:aws:ecr:eu-central-1:123456789012:repository/repo$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualRepositories = actualFlow.toList()
    actualRepositories.shouldHaveSize(10)
    actualRepositories.map { it.name }.shouldContainExactlyInAnyOrder(
        (0..9).map { "repo$it" }
    )
  }
}

