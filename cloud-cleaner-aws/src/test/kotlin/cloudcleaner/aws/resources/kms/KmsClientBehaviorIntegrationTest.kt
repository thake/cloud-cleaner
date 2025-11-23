package cloudcleaner.aws.resources.kms

import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.kms.createKey
import aws.sdk.kotlin.services.kms.deleteAlias
import aws.sdk.kotlin.services.kms.model.KeySpec
import aws.sdk.kotlin.services.kms.model.KeyState
import aws.sdk.kotlin.services.kms.model.KeyUsageType
import aws.sdk.kotlin.services.kms.scheduleKeyDeletion
import aws.smithy.kotlin.runtime.ServiceException
import cloudcleaner.aws.resources.LocalStack
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.kms.KmsClientStub.KeyStub
import cloudcleaner.aws.resources.shouldBeEquivalentTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KmsClientBehaviorIntegrationTest {
  val realClient = KmsClient {
    endpointUrl = LocalStack.localstackUrl
    region = REGION
  }
  val stub = KmsClientStub()

  @Test
  fun `deleteAlias should throw exception when alias not found`() = runTest {
    val actual = shouldThrow<ServiceException> { stub.deleteAlias { aliasName = "alias/non-existent" } }
    val expected = shouldThrow<ServiceException> { realClient.deleteAlias { aliasName = "alias/non-existent" } }

    actual.shouldBeEquivalentTo(expected)
  }

  @Test
  fun `scheduleKeyDeletion should throw exception when key not found`() = runTest {
    val actual = shouldThrow<ServiceException> { stub.scheduleKeyDeletion { keyId = "not-found" } }
    val expected = shouldThrow<ServiceException> { realClient.scheduleKeyDeletion { keyId = "not-found" } }

    actual.shouldBeEquivalentTo(expected)
  }
  @Test
  fun `scheduleKeyDeletion should throw exception when key in incorrect state`() = runTest {
    // given
    val keyCreationResponse = realClient.createKey {
      keySpec = KeySpec.SymmetricDefault
      keyUsage = KeyUsageType.EncryptDecrypt
      description = "Key scheduled for deletion"
    }
    val keyIdToBeDeleted = keyCreationResponse.keyMetadata!!.keyId
    realClient.scheduleKeyDeletion { keyId = keyIdToBeDeleted }
    stub.keys.add(
        KeyStub(
            keyId = keyIdToBeDeleted,
            keyState = KeyState.PendingDeletion
        )
    )

    // when
    val actual = shouldThrow<ServiceException> { stub.scheduleKeyDeletion { keyId = keyIdToBeDeleted } }
    val expected = shouldThrow<ServiceException> { realClient.scheduleKeyDeletion { keyId = keyIdToBeDeleted } }

    // then
    actual.shouldBeEquivalentTo(expected)
    actual.message.shouldBe(expected.message)
  }
}
