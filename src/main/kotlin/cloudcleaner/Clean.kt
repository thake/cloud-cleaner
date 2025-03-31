package cloudcleaner

import aws.sdk.kotlin.runtime.auth.credentials.AssumeRoleParameters
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StsAssumeRoleCredentialsProvider
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.getSessionToken
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import cloudcleaner.config.Config
import cloudcleaner.config.Config.AccountConfig
import cloudcleaner.config.ConfigReader
import cloudcleaner.resources.aws.AwsConnectionInformation
import cloudcleaner.resources.aws.loadAwsResources
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.withLoggingContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

class Clean : SuspendingCliktCommand(name = "clean") {
    val configFile by option("-c", "--config", help = "cleaner configuration file").convert {
        val path = Path(it)
        if (!SystemFileSystem.exists(path)) {
            fail("Could not find configuration file: $path")
        }
        path
    }.required()
    val profile by option("-p", "--profile", help = "AWS profile to use for authentication")
    val mfaOptions by MfaOptions().cooccurring()
    val dryRun by option("--no-dry-run", help = "Execute cleaning.").flag(default = false).convert { !it }
    override suspend fun run() = coroutineScope {
        val dryRunInfo = if (dryRun) "DRY RUN:" else ""
        logger.info { "$dryRunInfo Starting AWS clean" }
        val config = ConfigReader().readConfig(configFile)

        val bootstrapCredentialsProvider = mfaOptions?.let { mfaRootSession(it) } ?: DefaultChainCredentialsProvider(profileName = profile)

        config.accounts.forEach { account ->
            withLoggingContext("accountId" to account.accountId) {
                launch(MDCContext()) {
                    cleanAwsAccount(config, bootstrapCredentialsProvider, account)
                }
            }
        }
    }

    private suspend fun cleanAwsAccount(
        config: Config,
        bootstrapCredentialsProvider: CredentialsProvider,
        account: AccountConfig
    ) {
        config.regions.forEach { region ->
            withLoggingContext("region" to region) {
                withContext(MDCContext()) {
                    cleanRegion(bootstrapCredentialsProvider, account, region, config)
                }
            }
        }
    }

    private suspend fun cleanRegion(
        bootstrapCredentialsProvider: CredentialsProvider,
        account: AccountConfig,
        region: String,
        config: Config
    ) {
        val credentials = if(account.assumeRole != null) {
            StsAssumeRoleCredentialsProvider(
                bootstrapCredentialsProvider = bootstrapCredentialsProvider,
                assumeRoleParameters = AssumeRoleParameters(
                    roleArn = "arn:aws:iam::${account.accountId}:role/${account.assumeRole}",
                    duration = 1.hours,
                ),
                region = region.takeUnless { it == "global" },
            )
        } else {
            bootstrapCredentialsProvider
        }
        logger.info { "Purging account ${account.accountId} in '${region}'." }
        val registry =
            loadAwsResources(
                awsConnectionInformation = AwsConnectionInformation(
                    accountId = account.accountId,
                    credentialsProvider = credentials,
                    region = region
                ),
                resourceTypes = config.resourceTypes
            )
        val cleaner = Cleaner(
            dryRun = dryRun,
            resourceRegistry = registry,
            excludeFilter = account.excludeFilters
        )
        try {
            cleaner.clean()
        } finally {
            registry.close()
        }
    }
    suspend fun mfaRootSession(mfaOptions: MfaOptions): StaticCredentialsProvider {
        StsClient {
            region = "eu-central-1"
            credentialsProvider = ProfileCredentialsProvider(profileName = profile)
        }.use { stsClient ->
            val rootSession = stsClient.getSessionToken {
                serialNumber = mfaOptions.serialNumber
                tokenCode = mfaOptions.token
                durationSeconds = 12.hours.inWholeSeconds.toInt()
            }
            val credentials = rootSession.credentials!!
            return StaticCredentialsProvider {
                accessKeyId = credentials.accessKeyId
                secretAccessKey = credentials.secretAccessKey
                sessionToken = credentials.sessionToken
            }
        }
    }
}



class MfaOptions : OptionGroup() {
    val serialNumber by option("--auth-serial-number", "-s").required()
    val token by option("--auth-token", "-t").prompt("Enter your MFA token")
}