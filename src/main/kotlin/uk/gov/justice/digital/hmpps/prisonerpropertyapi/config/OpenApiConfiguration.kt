package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.tags.Tag
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration(buildProperties: BuildProperties) {
  private val version: String = buildProperties.version!!

  @Bean
  fun customOpenAPI(): OpenAPI = OpenAPI()
    .servers(
      listOf(
        Server().url("/").description("Current url"),
      ),
    )
    .info(
      Info().title("HMPPS Prisoner Property API")
        .version(version)
        .description("API for viewing and managing prisoner's properties and its locations")
        .contact(Contact().name("Move a prisoner Team").email("Moveaprisoner-gg@justice.gov.uk")),
    )
    .tags(
      listOf(
        Tag().name("hmpps-queue-resource-async")
          .description("""Endpoints that are to be used by administrators only for managing SQS queues. All endpoints require the <b>QUEUE_ADMIN</b> role further information can be found in the <a href="https://github.com/ministryofjustice/hmpps-spring-boot-sqs">hmpps-spring-boot-sqs</a> project"""),
      ),
    )
    .components(
      Components().addSecuritySchemes(
        "bearer-jwt",
        SecurityScheme()
          .type(SecurityScheme.Type.HTTP)
          .scheme("bearer")
          .bearerFormat("JWT")
          .`in`(SecurityScheme.In.HEADER)
          .name("Authorization").description("An HMPPS Auth access token."),
      ),
    )
    .addSecurityItem(SecurityRequirement().addList("bearer-jwt", listOf("read", "write")))

  /**
   * The subject access request endpoints come from hmpps-kotlin-spring-boot-starter. They are secured there
   * with @PreAuthorize, but the library does not add the @SecurityRequirement annotation our own resources
   * carry, so without this they would appear in the OpenAPI document as though they needed no token.
   *
   * Declaring it here rather than relaxing OpenApiDocsTest is deliberate: that test exists to catch an
   * endpoint of ours shipped without an auth requirement, and it can only keep doing that if it stays strict.
   */
  @Bean
  fun subjectAccessRequestSecurityCustomiser(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
    openApi.paths
      ?.filterKeys { it.startsWith("/subject-access-request") }
      ?.values
      ?.flatMap { it.readOperations() }
      ?.forEach { it.addSecurityItem(SecurityRequirement().addList("bearer-jwt")) }
  }
}
