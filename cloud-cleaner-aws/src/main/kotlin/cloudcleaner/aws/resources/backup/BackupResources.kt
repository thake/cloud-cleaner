package cloudcleaner.aws.resources.backup

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun backupResources(): List<AwsResourceDefinitionFactory<*>> = listOf(
    BackupVaultResourceDefinitionFactory(),
    RecoveryPointResourceDefinitionFactory()
)

