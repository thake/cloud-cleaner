package cloudcleaner.aws.resources

import aws.smithy.kotlin.runtime.net.url.Url
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.images.PullPolicy
import org.testcontainers.utility.DockerImageName

private val logger = KotlinLogging.logger {}

object LocalStack {
  private val localStackContainer =
      LocalStackContainer(DockerImageName.parse("localstack/localstack:4").asCompatibleSubstituteFor("localstack/localstack"))
          .withImagePullPolicy(PullPolicy.alwaysPull())
          .withExposedPorts(4566)
          .withReuse(true)

  val localstackUrl: Url by lazy {
    if (!localStackContainer.isRunning) startLocalstack()
    Url.parse(localStackContainer.endpoint.toString())
  }

  private fun startLocalstack() {
    logger.info { "Starting localstack..." }
    localStackContainer.start()
    val url = localStackContainer.endpoint.toString()
    logger.info { "Localstack started with url: $url" }
  }
}
