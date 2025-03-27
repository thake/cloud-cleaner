package awspurge

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import awspurge.resources.Resource
import awspurge.resources.aws.AwsConnectionInformation
import awspurge.resources.aws.loadAwsResources
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.boolean
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class Purge : SuspendingCliktCommand(name = "purge") {
    val profile by option("-p", "--profile", help = "AWS profile to use").required()
    val config by option("-c", "--config", help = "purge configuration file")
    val dryRun by option("--no-dry-run", help = "Execute purging.").boolean().convert { !it }.default(true)
    override suspend fun run() {
        val dryRunInfo = if (dryRun) "DRY RUN:" else ""
        logger.info { "$dryRunInfo Starting AWS purge for with profile $profile" }
        val credentials = ProfileCredentialsProvider(profileName = profile)
        val registry = loadAwsResources(AwsConnectionInformation(credentials, "eu-central-1"))
        val purger = Purger(dryRun = dryRun, registry)
        try {
            purger.purge()
        } finally {
            registry.close()
        }
    }
}