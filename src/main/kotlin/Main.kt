import com.github.ajalt.clikt.command.main
import cloudcleaner.Clean

suspend fun main(args: Array<String>) = Clean().main(args)
