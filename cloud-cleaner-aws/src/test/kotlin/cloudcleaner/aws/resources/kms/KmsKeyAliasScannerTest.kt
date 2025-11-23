package cloudcleaner.aws.resources.kms

import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.kms.KmsClientStub.AliasStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KmsKeyAliasScannerTest {
  private val kmsClient = KmsClientStub()
  private val underTest = KmsKeyAliasScanner(kmsClient, REGION)

  @Test
  fun `scan should return empty list when no aliases are present`() = runTest {
    // when
    val aliases = underTest.scan()

    // then
    aliases.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated aliases`() = runTest {
    // given
    repeat(150) {
      kmsClient.aliases.add(
          AliasStub(
              aliasName = "alias/my-key-$it",
              targetKeyId = "1234abcd-12ab-34cd-56ef-1234567890ab",
              aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-key-$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualAliases = actualFlow.toList()
    actualAliases.shouldHaveSize(150)
  }

  @Test
  fun `scan should return alias details correctly`() = runTest {
    // given
    val alias = AliasStub(
        aliasName = "alias/my-database-key",
        targetKeyId = "1234abcd-12ab-34cd-56ef-1234567890ab",
        aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-database-key"
    )
    kmsClient.aliases.add(alias)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualAliases = actualFlow.toList()
    actualAliases.shouldHaveSize(1)
    val actualAlias = actualAliases.first()
    actualAlias.aliasName shouldBe KmsKeyAliasName("alias/my-database-key", REGION)
    actualAlias.aliasArn.value shouldBe "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-database-key"
    actualAlias.targetKeyId shouldBe KmsKeyId("1234abcd-12ab-34cd-56ef-1234567890ab", REGION)
    actualAlias.dependsOn shouldBe setOf(KmsKeyId("1234abcd-12ab-34cd-56ef-1234567890ab", REGION))
  }

  @Test
  fun `scan should handle multiple aliases`() = runTest {
    // given
    kmsClient.aliases.add(
        AliasStub(
            aliasName = "alias/my-key-1",
            targetKeyId = "1234abcd-12ab-34cd-56ef-1234567890ab",
            aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-key-1"
        )
    )
    kmsClient.aliases.add(
        AliasStub(
            aliasName = "alias/my-key-2",
            targetKeyId = "5678efgh-56ef-78gh-90ij-5678901234cd",
            aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-key-2"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualAliases = actualFlow.toList()
    actualAliases.shouldHaveSize(2)
  }

  @Test
  fun `scan should skip AWS managed aliases`() = runTest {
    // given
    kmsClient.aliases.add(
        AliasStub(
            aliasName = "alias/aws/s3",
            targetKeyId = "aws-managed-key-id",
            aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/aws/s3"
        )
    )
    kmsClient.aliases.add(
        AliasStub(
            aliasName = "alias/my-key",
            targetKeyId = "1234abcd-12ab-34cd-56ef-1234567890ab",
            aliasArn = "arn:aws:kms:$REGION:$ACCOUNT_ID:alias/my-key"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualAliases = actualFlow.toList()
    actualAliases.shouldHaveSize(1)
    actualAliases.first().aliasName.value shouldBe "alias/my-key"
  }
}

