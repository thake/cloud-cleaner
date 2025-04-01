package cloudcleaner.aws

import com.github.ajalt.clikt.command.main

suspend fun main(args: Array<String>) = Clean().main(args)
