package awspurge

import aws.sdk.kotlin.runtime.auth.credentials.AssumeRoleParameters
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StsAssumeRoleCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StsWebIdentityCredentialsProvider
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.getSessionToken
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import awspurge.config.Config
import awspurge.config.Config.AccountConfig
import awspurge.config.ConfigReader
import awspurge.resources.Resource
import awspurge.resources.aws.AwsConnectionInformation
import awspurge.resources.aws.loadAwsResources
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.withLoggingContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.slf4j.MDC
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

class Purge : SuspendingCliktCommand(name = "purge") {
    val configFile by option("-c", "--config", help = "purge configuration file").convert {
        val path = Path(it)
        if (!SystemFileSystem.exists(path)) {
            fail("Could not find configuration file: $path")
        }
        path
    }.required()
    val mfaOptions by MfaOptions().cooccurring()
    val dryRun by option("--no-dry-run", help = "Execute purging.").flag(default = false).convert { !it }
    override suspend fun run() = coroutineScope {
        val dryRunInfo = if (dryRun) "DRY RUN:" else ""
        logger.info { "$dryRunInfo Starting AWS purge" }
        val config = ConfigReader().readConfig(configFile)

        val bootstrapCredentialsProvider = mfaOptions?.let { mfaRootSession(it) } ?: DefaultChainCredentialsProvider()

        config.accounts.forEach { account ->
            withLoggingContext("accountId" to account.accountId) {
                launch(MDCContext()) {
                    purgeAwsAccount(config, bootstrapCredentialsProvider, account)
                }
            }
        }
    }

    private suspend fun purgeAwsAccount(
        config: Config,
        bootstrapCredentialsProvider: CredentialsProvider,
        account: AccountConfig
    ) {
        config.regions.forEach { region ->
            withLoggingContext("region" to region) {
                withContext(MDCContext()) {
                    purgeRegion(bootstrapCredentialsProvider, account, region, config)
                }
            }
        }
    }

    private suspend fun purgeRegion(
        bootstrapCredentialsProvider: CredentialsProvider,
        account: AccountConfig,
        region: String,
        config: Config
    ) {
        val credentials = StsAssumeRoleCredentialsProvider(
            bootstrapCredentialsProvider = bootstrapCredentialsProvider,
            assumeRoleParameters = AssumeRoleParameters(
                roleArn = "arn:aws:iam::${account.accountId}:role/${account.assumeRole}",
                duration = 1.hours,
            ),
            region = region.takeUnless { it == "global" },
        )
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
        val purger = Purger(
            dryRun = dryRun,
            resourceRegistry = registry,
            excludeFilter = account.excludeFilters
        )
        try {
            purger.purge()
        } finally {
            registry.close()
        }
    }
}

suspend fun mfaRootSession(mfaOptions: MfaOptions): StaticCredentialsProvider {
    StsClient {
        region = "eu-central-1"
        credentialsProvider = ProfileCredentialsProvider(profileName = "default")
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

class MfaOptions : OptionGroup() {
    val serialNumber by option("--auth-serial-number", "-s").required()
    val token by option("--auth-token", "-t").prompt("Enter your MFA token")
}