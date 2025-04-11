package cloudcleaner.aws

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.versionOption

class CloudCleanerAws : SuspendingCliktCommand() {
  init {
    versionOption(APP_VERSION)
  }

  override suspend fun run() = Unit
}
