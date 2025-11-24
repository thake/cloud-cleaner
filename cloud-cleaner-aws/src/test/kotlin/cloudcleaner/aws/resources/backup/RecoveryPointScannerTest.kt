package cloudcleaner.aws.resources.backup

import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.backup.BackupClientStub.VaultStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RecoveryPointScannerTest {
  private val backupClient = BackupClientStub()
  private val underTest = RecoveryPointScanner(backupClient, REGION)

  @Test
  fun `scan should return empty list when no recovery points are present`() = runTest {
    // given - no vaults or recovery points

    // when
    val points = underTest.scan()

    // then
    points.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return empty list when vaults exist but no recovery points`() = runTest {
    // given
    backupClient.vaults.add(VaultStub("vault-1"))

    // when
    val points = underTest.scan()

    // then
    points.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return recovery points from all vaults`() = runTest {
    // given
    repeat(200) {
      val vault1 = VaultStub("vault-$it")
      repeat(200) {
        vault1.addRecoveryPoint("rp-$it")
      }
      backupClient.vaults.add(vault1)
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualPoints = actualFlow.toList()
    actualPoints.shouldHaveSize(200*200)
  }

  @Test
  fun `scan should return recovery point details correctly`() = runTest {
    // given
    val vault = VaultStub("my-vault")
    val rp = vault.addRecoveryPoint("my-recovery-point")
    val rpArn = rp.recoveryPointArn
    backupClient.vaults.add(vault)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualPoints = actualFlow.toList()
    actualPoints.shouldHaveSize(1)
    val actualPoint = actualPoints.first()
    actualPoint.recoveryPointId.recoveryPointArn shouldBe rpArn
    actualPoint.recoveryPointId.backupVaultName shouldBe "my-vault"
    actualPoint.recoveryPointId.region shouldBe REGION
    actualPoint.backupVaultName shouldBe BackupVaultName("my-vault", REGION)
    actualPoint.dependsOn.shouldContainExactly(BackupVaultName("my-vault", REGION))
  }
}

