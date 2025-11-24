package cloudcleaner.aws.resources.backup

import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.backup.BackupClientStub.VaultStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BackupVaultScannerTest {
  private val backupClient = BackupClientStub()
  private val underTest = BackupVaultScanner(backupClient, REGION)

  @Test
  fun `scan should return empty list when no vaults are present`() = runTest {
    // when
    val vaults = underTest.scan()

    // then
    vaults.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated vaults`() = runTest {
    // given
    repeat(100) {
      backupClient.vaults.add(
          VaultStub(
              vaultName = "vault-$it",
              vaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:vault-$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualVaults = actualFlow.toList()
    actualVaults.shouldHaveSize(100)
  }

  @Test
  fun `scan should return vault details correctly`() = runTest {
    // given
    val vault = VaultStub(
        vaultName = "my-backup-vault",
        vaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:my-backup-vault"
    )
    backupClient.vaults.add(vault)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualVaults = actualFlow.toList()
    actualVaults.shouldHaveSize(1)
    val actualVault = actualVaults.first()
    actualVault.vaultName shouldBe BackupVaultName("my-backup-vault", REGION)
    actualVault.vaultArn.value shouldBe "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:my-backup-vault"
    actualVault.dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle multiple vaults`() = runTest {
    // given
    backupClient.vaults.add(
        VaultStub(
            vaultName = "vault-1",
            vaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:vault-1"
        )
    )
    backupClient.vaults.add(
        VaultStub(
            vaultName = "vault-2",
            vaultArn = "arn:aws:backup:$REGION:$ACCOUNT_ID:backup-vault:vault-2"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualVaults = actualFlow.toList()
    actualVaults.shouldHaveSize(2)
  }

  @Test
  fun `scan should include recovery points as containedResources`() = runTest {
    // given
    val vaultStub = VaultStub("my-vault")
    val rp1 = vaultStub.addRecoveryPoint("rp-1")
    val rp2 = vaultStub.addRecoveryPoint("rp-2")
    backupClient.vaults.add(vaultStub)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualVaults = actualFlow.toList()
    actualVaults.shouldHaveSize(1)
    val actualVault = actualVaults.first()
    actualVault.containedResources shouldBe setOf(
        RecoveryPointId(rp1.recoveryPointArn, "my-vault", REGION),
        RecoveryPointId(rp2.recoveryPointArn, "my-vault", REGION)
    )
  }

  @Test
  fun `scan should return empty containedResources when vault has no recovery points`() = runTest {
    // given
    backupClient.vaults.add(VaultStub("empty-vault"))

    // when
    val actualFlow = underTest.scan()

    // then
    val actualVaults = actualFlow.toList()
    actualVaults.shouldHaveSize(1)
    actualVaults.first().containedResources.shouldBeEmpty()
  }
}

