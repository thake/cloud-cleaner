package cloudcleaner.aws.resources.kms

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.kms.KmsClientStub.AliasStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KmsKeyAliasDeleterTest {
  private val kmsClient = KmsClientStub()
  private val underTest = KmsKeyAliasDeleter(kmsClient)

  @Test
  fun `delete should successfully delete an alias`() = runTest {
    // given
    val targetKeyId = "1234abcd-12ab-34cd-56ef-1234567890ab"
    val alias = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/my-key", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/my-key"),
        targetKeyId = KmsKeyId(targetKeyId, REGION)
    )
    kmsClient.aliases.add(
        AliasStub(
            aliasName = alias.aliasName.value,
            targetKeyId = targetKeyId
        )
    )

    // when
    underTest.delete(alias)

    // then
    kmsClient.aliases.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a KmsKeyAlias`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = StringId("invalid")
      override val name = "invalid"
      override val type = "NotAKmsKeyAlias"
      override val properties = emptyMap<String, String>()
    }

    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should ignore not existing alias`() = runTest {
    // given
    val alias = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/non-existent", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/non-existent"),
        targetKeyId = KmsKeyId("1234abcd-12ab-34cd-56ef-1234567890ab", REGION)
    )

    // when/then - should not throw
    underTest.delete(alias)
  }

  @Test
  fun `delete should delete multiple aliases`() = runTest {
    // given
    val keyId1 = "1234abcd-12ab-34cd-56ef-1234567890ab"
    val keyId2 = "5678efgh-56ef-78gh-90ij-5678901234cd"
    val alias1 = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/my-key-1", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/my-key-1"),
        targetKeyId = KmsKeyId(keyId1, REGION)
    )
    val alias2 = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/my-key-2", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/my-key-2"),
        targetKeyId = KmsKeyId(keyId2, REGION)
    )

    kmsClient.aliases.add(AliasStub(alias1.aliasName.value, keyId1))
    kmsClient.aliases.add(AliasStub(alias2.aliasName.value, keyId2))

    // when
    underTest.delete(alias1)
    underTest.delete(alias2)

    // then
    kmsClient.aliases.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified alias`() = runTest {
    // given
    val keyId1 = "1234abcd-12ab-34cd-56ef-1234567890ab"
    val keyId2 = "5678efgh-56ef-78gh-90ij-5678901234cd"
    val alias1 = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/my-key-1", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/my-key-1"),
        targetKeyId = KmsKeyId(keyId1, REGION)
    )
    val alias2 = KmsKeyAlias(
        aliasName = KmsKeyAliasName("alias/my-key-2", REGION),
        aliasArn = Arn("arn:aws:kms:region:account-id:alias/my-key-2"),
        targetKeyId = KmsKeyId(keyId2, REGION)
    )

    kmsClient.aliases.add(AliasStub(alias1.aliasName.value, keyId1))
    kmsClient.aliases.add(AliasStub(alias2.aliasName.value, keyId2))

    // when
    underTest.delete(alias1)

    // then
    kmsClient.aliases.shouldHaveSize(1)
    kmsClient.aliases[0].aliasName shouldBe alias2.aliasName.value
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}

