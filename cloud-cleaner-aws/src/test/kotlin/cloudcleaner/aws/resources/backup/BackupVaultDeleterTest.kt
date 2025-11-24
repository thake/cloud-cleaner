package cloudcleaner.aws.resources.backup

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.backup.BackupClientStub.VaultStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BackupVaultDeleterTest {
  private val backupClient = BackupClientStub()
  private val underTest = BackupVaultDeleter(backupClient)

  @Test
  fun `delete should successfully delete a vault`() = runTest {
    // given
    val vault =
        BackupVault(
            vaultName = BackupVaultName("my-backup-vault", REGION),
            vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:my-backup-vault"))
    backupClient.vaults.add(VaultStub(vaultName = vault.vaultName.value))

    // when
    underTest.delete(vault)

    // then
    backupClient.vaults.shouldHaveSize(0)
  }

  @Test
  fun `delete should successfully delete a vault with recovery points`() = runTest {
    // given
    val vault =
        BackupVault(
            vaultName = BackupVaultName("my-backup-vault", REGION),
            vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:my-backup-vault"))
    val vaultStub = VaultStub(vaultName = vault.vaultName.value)
    vaultStub.addRecoveryPoint("rp-1")

    backupClient.vaults.add(vaultStub)

    // when
    underTest.delete(vault)

    // then
    backupClient.vaults.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a BackupVault`() = runTest {
    // given
    val invalidResource =
        object : cloudcleaner.resources.Resource {
          override val id = StringId("invalid")
          override val name = "invalid"
          override val type = "NotABackupVault"
          override val properties = emptyMap<String, String>()
        }

    // when/then
    shouldThrow<IllegalArgumentException> { underTest.delete(invalidResource) }
  }

  @Test
  fun `delete should ignore not existing vault`() = runTest {
    // given
    val vault =
        BackupVault(
            vaultName = BackupVaultName("non-existent-vault", REGION),
            vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:non-existent-vault"))

    // when/then - should not throw
    underTest.delete(vault)
  }

  @Test
  fun `delete should delete multiple vaults`() = runTest {
    // given
    val vault1 =
        BackupVault(vaultName = BackupVaultName("vault-1", REGION), vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:vault-1"))
    val vault2 =
        BackupVault(vaultName = BackupVaultName("vault-2", REGION), vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:vault-2"))

    backupClient.vaults.add(VaultStub(vault1.vaultName.value))
    backupClient.vaults.add(VaultStub(vault2.vaultName.value))

    // when
    underTest.delete(vault1)
    underTest.delete(vault2)

    // then
    backupClient.vaults.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified vault`() = runTest {
    // given
    val vault1 =
        BackupVault(vaultName = BackupVaultName("vault-1", REGION), vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:vault-1"))
    val vault2 =
        BackupVault(vaultName = BackupVaultName("vault-2", REGION), vaultArn = Arn("arn:aws:backup:region:account-id:backup-vault:vault-2"))

    backupClient.vaults.add(VaultStub(vault1.vaultName.value))
    backupClient.vaults.add(VaultStub(vault2.vaultName.value))

    // when
    underTest.delete(vault1)

    // then
    backupClient.vaults.shouldHaveSize(1)
    backupClient.vaults[0].vaultName shouldBe vault2.vaultName.value
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}
