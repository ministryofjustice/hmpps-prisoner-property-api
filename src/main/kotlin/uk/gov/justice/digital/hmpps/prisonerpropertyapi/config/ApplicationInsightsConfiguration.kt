package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TelemetryClient gets altered at runtime by the java agent and so is a no-op otherwise.
 *
 * The agent is attached in the deployed image (see the Dockerfile's `-javaagent:agent.jar`) and reads
 * `applicationinsights.json`; locally and in tests there is no agent and no connection string, so the
 * client constructs fine and every `trackEvent` silently does nothing. That is why this bean is
 * unconditional - there is nothing to guard against.
 *
 * Note this bean also switches on hmpps-sqs's own telemetry: `HmppsSqsConfiguration` takes a *nullable*
 * TelemetryClient, which is why the service ran without one, and now reports DLQ, purge and
 * error-visibility events too.
 */
@Configuration
class ApplicationInsightsConfiguration {
  @Bean
  fun telemetryClient(): TelemetryClient = TelemetryClient()
}
