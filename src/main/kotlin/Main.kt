import com.github.ajalt.clikt.command.main
import awspurge.Purge

suspend fun main(args: Array<String>) = Purge().main(args)
