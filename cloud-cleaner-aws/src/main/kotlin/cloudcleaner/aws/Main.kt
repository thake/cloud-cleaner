package cloudcleaner.aws

import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.subcommands

suspend fun main(args: Array<String>) = CloudCleanerAws().subcommands(Clean()).main(args)
