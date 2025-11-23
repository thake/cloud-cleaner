package cloudcleaner.aws.resources.kms

import aws.sdk.kotlin.services.kms.model.KeyManagerType
import aws.sdk.kotlin.services.kms.model.KeyState
import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.kms.KmsClientStub.KeyStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KmsKeyScannerTest {
  private val kmsClient = KmsClientStub()
  private val underTest = KmsKeyScanner(kmsClient, REGION)

  @Test
  fun `scan should return empty list when no keys are present`() = runTest {
    // when
    val keys = underTest.scan()

    // then
    keys.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated keys`() = runTest {
    // given
    repeat(150) { kmsClient.keys.add(KeyStub(keyId = "key-$it")) }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualKeys = actualFlow.toList()
    actualKeys.shouldHaveSize(150)
  }

  @Test
  fun `scan should return key details correctly`() = runTest {
    // given
    val keyId = "1234abcd-12ab-34cd-56ef-1234567890ab"
    val key = KeyStub(keyId = keyId, description = "Test key")
    kmsClient.keys.add(key)
    // when
    val actualFlow = underTest.scan()

    // then
    val actualKeys = actualFlow.toList()
    actualKeys.shouldHaveSize(1)
    val actualKey = actualKeys.first()
    actualKey.keyId shouldBe KmsKeyId(keyId, REGION)
    actualKey.keyArn.value shouldBe "arn:aws:kms:$REGION:$ACCOUNT_ID:key/$keyId"
    actualKey.keyManager shouldBe "CUSTOMER"
    actualKey.keyState shouldBe "Enabled"
    actualKey.dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle multiple keys`() = runTest {
    // given
    kmsClient.keys.add(
        KeyStub(
            keyId = "key-1",
        ))
    kmsClient.keys.add(
        KeyStub(
            keyId = "key-2",
        ))

    val actualFlow = underTest.scan()

    // then
    val actualKeys = actualFlow.toList()
    actualKeys.shouldHaveSize(2)
  }

  @Test
  fun `scan should skip AWS managed keys`() = runTest {
    // given
    kmsClient.keys.add(
        KeyStub(
            keyId = "aws-managed-key",
            keyManager = KeyManagerType.Aws,
        ))
    kmsClient.keys.add(KeyStub(keyId = "customer-key"))
    val actualFlow = underTest.scan()

    // then
    val actualKeys = actualFlow.toList()
    actualKeys.shouldHaveSize(1)
    actualKeys.first().keyId.value shouldBe "customer-key"
  }

  @Test
  fun `scan should skip keys pending deletion`() = runTest {
    // given
    kmsClient.keys.add(KeyStub(keyId = "pending-deletion-key", keyState = KeyState.PendingDeletion))
    kmsClient.keys.add(KeyStub(keyId = "active-key"))
    val actualFlow = underTest.scan()

    // then
    val actualKeys = actualFlow.toList()
    actualKeys.shouldHaveSize(1)
    actualKeys.first().keyId.value shouldBe "active-key"
  }
}
