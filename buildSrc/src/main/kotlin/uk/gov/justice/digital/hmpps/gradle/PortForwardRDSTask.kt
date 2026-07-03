package uk.gov.justice.digital.hmpps.gradle

import org.gradle.api.tasks.Input

/**
 * Connect a local port to RDS in Cloud Platform
 *
 * ```build.gradle.kts
 * tasks.register<PortForwardRDSTask>("portForwardRDS") {
 *     namespacePrefix = "hmpps-????"
 * }
 * ```
 *
 * ```shell
 * ./gradlew help --task portForwardRDS
 * ./gradlew portForwardRDS --environment dev --port 8432
 * ```
 */
open class PortForwardRDSTask : PortForwardTask() {
  init {
    description = "Connect a local port to RDS in Cloud Platform"
  }

  override var secretName: String? = "prisoner-property-rds-instance-output"
    @Input
    get

  override var remotePort: Int? = 5432
    @Input
    get

  override fun getRemoteHostFromSecret(secret: Map<String, String>): String {
    return secret["rds_instance_address"]!!
  }

  override fun showUsageInstructions(secret: Map<String, String>) {
    val database = secret["database_name"]!!
    val username = secret["database_username"]!!
  }
}
