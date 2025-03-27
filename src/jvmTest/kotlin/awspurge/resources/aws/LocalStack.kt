package awspurge.resources.aws

import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.images.PullPolicy
import org.testcontainers.utility.DockerImageName
private val logger = KotlinLogging.logger { }
object LocalStack {
    private val localStackContainer = LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3").asCompatibleSubstituteFor("localstack/localstack")
    )
        .withImagePullPolicy(PullPolicy.alwaysPull())
        .withExposedPorts(4566)

    val localstackUrl: String by lazy {
        if (!localStackContainer.isRunning) startLocalstack()
        localStackContainer.endpoint.toString()
    }
    private fun startLocalstack() {
        logger.info("Starting localstack...")
        localStackContainer.start()
        val url = localStackContainer.endpoint.toString()
        logger.info("Localstack started with url: $url")
    }

}