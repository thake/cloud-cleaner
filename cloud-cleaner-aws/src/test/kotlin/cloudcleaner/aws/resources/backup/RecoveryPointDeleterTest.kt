package cloudcleaner.aws.resources.backup

import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.backup.BackupClientStub.VaultStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RecoveryPointDeleterTest {
  private val backupClient = BackupClientStub()
  private val underTest = RecoveryPointDeleter(backupClient)

  @Test
  fun `delete should successfully delete a recovery point`() = runTest {
    // given
    val vault = VaultStub("my-vault")
    val rp = vault.addRecoveryPoint("my-rp")
    backupClient.vaults.add(vault)

    val point = RecoveryPoint(
        recoveryPointId = RecoveryPointId(rp.recoveryPointArn, "my-vault", REGION),
        backupVaultName = BackupVaultName("my-vault", REGION)
    )

    // when
    underTest.delete(point)

    // then
    vault.recoveryPoints.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a RecoveryPoint`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = StringId("invalid")
      override val name = "invalid"
      override val type = "NotARecoveryPoint"
      override val properties = emptyMap<String, String>()
    }

    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should ignore not existing recovery point`() = runTest {
    // given
    backupClient.vaults.add(VaultStub("my-vault"))
    val rpArn = "arn:aws:backup:$REGION:123456789012:recovery-point:non-existent"
    val point = RecoveryPoint(
        recoveryPointId = RecoveryPointId(rpArn, "my-vault", REGION),
        backupVaultName = BackupVaultName("my-vault", REGION)
    )

    // when/then - should not throw
    underTest.delete(point)
  }

  @Test
  fun `delete should delete multiple recovery points`() = runTest {
    // given
    val vault =VaultStub("my-vault")
    val rp1 = vault.addRecoveryPoint("rp-1")
    val rp2 = vault.addRecoveryPoint("rp-2")
    backupClient.vaults.add(vault)
    val rpArn1 = rp1.recoveryPointArn
    val rpArn2 = rp2.recoveryPointArn

    val point1 = RecoveryPoint(
        recoveryPointId = RecoveryPointId(rpArn1, "my-vault", REGION),
        backupVaultName = BackupVaultName("my-vault", REGION)
    )
    val point2 = RecoveryPoint(
        recoveryPointId = RecoveryPointId(rpArn2, "my-vault", REGION),
        backupVaultName = BackupVaultName("my-vault", REGION)
    )

    // when
    underTest.delete(point1)
    underTest.delete(point2)

    // then
    vault.recoveryPoints.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified recovery point`() = runTest {
    // given
    val vault =VaultStub("my-vault")
    val rp1 = vault.addRecoveryPoint("rp-1")
    val rp2 = vault.addRecoveryPoint("rp-2")
    backupClient.vaults.add(vault)
    val rpArn1 = rp1.recoveryPointArn
    val rpArn2 = rp2.recoveryPointArn

    val point1 = RecoveryPoint(
        recoveryPointId = RecoveryPointId(rpArn1, "my-vault", REGION),
        backupVaultName = BackupVaultName("my-vault", REGION)
    )

    // when
    underTest.delete(point1)

    // then
    vault.recoveryPoints.shouldHaveSize(1)
    vault.recoveryPoints[0].recoveryPointArn shouldBe rpArn2
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}

