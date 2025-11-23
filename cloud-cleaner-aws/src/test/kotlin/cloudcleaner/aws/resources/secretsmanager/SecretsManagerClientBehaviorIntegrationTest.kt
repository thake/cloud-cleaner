package cloudcleaner.aws.resources.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.deleteSecret
import aws.sdk.kotlin.services.secretsmanager.describeSecret
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SecretsManagerClientBehaviorIntegrationTest {
  private val underTest = SecretsManagerClientStub()
  private val secretsManagerClient = SecretsManagerClient {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }

  @Test
  fun `describeSecret should throw exception when secret not found`() = runTest {
    // when/then
    val actual = shouldThrow<ServiceException> {
      underTest.describeSecret { secretId = "non-existent" }
    }
    val expected = shouldThrow<ServiceException> {
      secretsManagerClient.describeSecret { secretId = "non-existent" }
    }
    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `deleteSecret should throw exception when secret not found`() = runTest {
    // when/then
    val actual = shouldThrow<ServiceException> {
      underTest.deleteSecret { secretId = "non-existent" }
    }
    val expected = shouldThrow<ServiceException> {
      secretsManagerClient.deleteSecret{ secretId = "non-existent" }
    }
    actual.shouldBeEquivalentTo(expected)

  }
}

