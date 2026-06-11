package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.io.IOException
import java.net.ServerSocket

object LocalStackContainer {
  private val log = LoggerFactory.getLogger(this::class.java)
  val instance by lazy { startLocalstackIfNotRunning() }

  fun setLocalStackProperties(localStackContainer: LocalStackContainer, registry: DynamicPropertyRegistry) {
    registry.add("hmpps.sqs.localstackUrl") { localStackContainer.endpoint }
    registry.add("hmpps.sqs.region") { localStackContainer.region }
  }

  private fun startLocalstackIfNotRunning(): LocalStackContainer? {
    if (localstackIsRunning()) return null
    val logConsumer = Slf4jLogConsumer(log).withPrefix("localstack")
    return LocalStackContainer(
      DockerImageName.parse("localstack/localstack").withTag("3"),
    ).apply {
      withServices("sqs", "sns")
      withEnv("DEFAULT_REGION", "eu-west-2")
      waitingFor(Wait.forLogMessage(".*Ready.*", 1))
      start()
      followOutput(logConsumer)
    }
  }

  private fun localstackIsRunning(): Boolean = try {
    ServerSocket(4566).use { it.localPort == 0 }
  } catch (e: IOException) {
    true
  }
}
