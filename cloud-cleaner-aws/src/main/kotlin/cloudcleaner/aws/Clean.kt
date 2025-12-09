package cloudcleaner.aws

import aws.sdk.kotlin.runtime.auth.credentials.AssumeRoleParameters
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StsAssumeRoleCredentialsProvider
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.getSessionToken
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import cloudcleaner.Cleaner
import cloudcleaner.aws.config.Config
import cloudcleaner.aws.config.ConfigReader
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.addAwsResources
import cloudcleaner.resources.ResourceRegistry
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
import java.io.Closeable
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

class Clean : SuspendingCliktCommand(name = "clean") {
  val configFile by
      option("-c", "--config", help = "cleaner configuration file")
          .convert {
            val path = Path(it)
            echo(SystemFileSystem.resolve(path))
            if (!SystemFileSystem.exists(path)) {
              fail("Could not find configuration file: $path")
            }
            path
          }
          .required()
  val mfaOptions by MfaOptions().cooccurring()
  val dryRun by option("--no-dry-run", help = "Execute cleaning.").flag(default = false).convert { !it }

  override suspend fun run(): Unit = coroutineScope {
    val dryRunInfo = if (dryRun) "DRY RUN:" else ""
    logger.info { "$dryRunInfo Starting AWS clean" }
    val config = ConfigReader().readConfig(configFile)

    val bootstrapCredentialsProvider = mfaOptions?.let { mfaRootSession(it) }

    config.accounts.forEach { account ->
      withLoggingContext("accountId" to account.accountId) {
        launch(MDCContext()) { cleanAwsAccount(config, bootstrapCredentialsProvider, account) }
      }
    }
    (bootstrapCredentialsProvider as? Closeable)?.close()
  }

  private suspend fun cleanAwsAccount(config: Config, bootstrapCredentialsProvider: CredentialsProvider?, account: Config.AccountConfig) {
    withContext(MDCContext()) {
      val registry = ResourceRegistry()
      logger.info { "Purging account ${account.accountId}." }
      config.regions.forEach { region ->
        val credentials = createCredentialsProvider(account, region, bootstrapCredentialsProvider)

        registry.addAwsResources(
            awsConnectionInformation =
                AwsConnectionInformation(accountId = account.accountId, credentialsProvider = credentials, region = region),
            resourceTypes = config.resourceTypes)
      }
      val cleaner =
          Cleaner(
              dryRun = this@Clean.dryRun,
              resourceRegistry = registry,
              excludeFilters = account.excludeFilters,
              includeFilters = account.includeFilters)
      try {
        cleaner.clean()
      } finally {
        registry.close()
      }
    }
  }

  suspend fun mfaRootSession(mfaOptions: MfaOptions): StaticCredentialsProvider {
    StsClient {
          region = "eu-central-1"
        }
        .use { stsClient ->
          val rootSession =
              stsClient.getSessionToken {
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

private fun createCredentialsProvider(
  account: Config.AccountConfig,
  region: String,
  bootstrapCredentialsProvider: CredentialsProvider?
): CredentialsProvider {
  val bootstrapCredentialsProvider = when {
    account.profile != null -> ProfileCredentialsProvider(profileName = account.profile, region = region)
    bootstrapCredentialsProvider != null -> bootstrapCredentialsProvider
    else -> {
      logger.info { "No mfa option or profile set. Falling back to default credentials provider to assume into roles." }
      DefaultChainCredentialsProvider()
    }
  }
  val credentialsProvider = when {
    account.assumeRole != null -> {
      StsAssumeRoleCredentialsProvider(
          bootstrapCredentialsProvider = bootstrapCredentialsProvider,
          assumeRoleParameters =
              AssumeRoleParameters(
                  roleArn = "arn:aws:iam::${account.accountId}:role/${account.assumeRole}",
                  duration = 1.hours,
              ),
          region = region.takeUnless { it == "global" },
      )
    }
    else -> bootstrapCredentialsProvider
  }
  return credentialsProvider
}

class MfaOptions : OptionGroup() {
  val serialNumber by option("--auth-serial-number", "-s").required()
  val token by option("--auth-token", "-t").prompt("Enter your MFA token")
}
