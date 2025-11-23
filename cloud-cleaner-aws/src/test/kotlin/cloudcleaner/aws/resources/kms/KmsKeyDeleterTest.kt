package cloudcleaner.aws.resources.kms

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.kms.KmsClientStub.KeyStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KmsKeyDeleterTest {
  private val kmsClient = KmsClientStub()
  private val underTest = KmsKeyDeleter(kmsClient)

  @Test
  fun `delete should successfully schedule a key for deletion`() = runTest {
    // given
    val keyId = "1234abcd-12ab-34cd-56ef-1234567890ab"
    val key = KmsKey(
        keyId = KmsKeyId(keyId, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )
    kmsClient.keys.add(
        KeyStub(
            keyId = keyId
        )
    )

    // when
    underTest.delete(key)

    // then
    kmsClient.keys.shouldHaveSize(1)
    kmsClient.keys[0].keyState.value shouldBe "PendingDeletion"
  }

  @Test
  fun `delete should throw exception when resource is not a KmsKey`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = StringId("invalid")
      override val name = "invalid"
      override val type = "NotAKmsKey"
      override val properties = emptyMap<String, String>()
    }

    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should ignore not existing key`() = runTest {
    // given
    val keyId = "non-existent-key"
    val key = KmsKey(
        keyId = KmsKeyId(keyId, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )

    // when/then - should not throw
    underTest.delete(key)
  }

  @Test
  fun `delete should ignore already pending deletion`() = runTest {
    // given
    val keyId = "pending-deletion-key"
    kmsClient.keys.add(KeyStub(keyId = keyId))
    val key = KmsKey(
        keyId = KmsKeyId(keyId, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )

    // when/then - should not throw
    underTest.delete(key)
  }

  @Test
  fun `delete should schedule multiple keys for deletion`() = runTest {
    // given
    val keyId1 = "key-1"
    val keyId2 = "key-2"
    val key1 = KmsKey(
        keyId = KmsKeyId(keyId1, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId1"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )
    val key2 = KmsKey(
        keyId = KmsKeyId(keyId2, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId2"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )

    kmsClient.keys.add(KeyStub(keyId = keyId1))
    kmsClient.keys.add(KeyStub(keyId = keyId2))

    // when
    underTest.delete(key1)
    underTest.delete(key2)

    // then
    kmsClient.keys.shouldHaveSize(2)
    kmsClient.keys[0].keyState.value shouldBe "PendingDeletion"
    kmsClient.keys[1].keyState.value shouldBe "PendingDeletion"
  }

  @Test
  fun `delete should only schedule the specified key`() = runTest {
    // given
    val keyId1 = "key-1"
    val keyId2 = "key-2"
    val key1 = KmsKey(
        keyId = KmsKeyId(keyId1, REGION),
        keyArn = Arn("arn:aws:kms:$REGION:account-id:key/$keyId1"),
        keyManager = "CUSTOMER",
        keyState = "Enabled"
    )

    kmsClient.keys.add(KeyStub(keyId = keyId1))
    kmsClient.keys.add(KeyStub(keyId = keyId2))

    // when
    underTest.delete(key1)

    // then
    kmsClient.keys.shouldHaveSize(2)
    kmsClient.keys[0].keyState.value shouldBe "PendingDeletion"
    kmsClient.keys[1].keyState.value shouldBe "Enabled"
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}

