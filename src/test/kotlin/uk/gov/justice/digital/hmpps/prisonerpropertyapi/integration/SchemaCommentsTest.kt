package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Guards the data dictionary published to GitHub Pages (see db/migration/V15__schema_comments.sql).
 *
 * Descriptions live in the database as COMMENT ON statements so SchemaSpy, the CSV exports and any Glue
 * crawl share one source of truth. Each carries three tags - a sensitivity classification, an example
 * value, and whether the column is disclosed in a subject access request - which between them are what
 * the SAR Data Requirements extract is built from. Nothing else would notice a new column arriving
 * undocumented, so this fails the build instead.
 */
class SchemaCommentsTest : IntegrationTestBase() {

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Test
  fun `every table has a description`() {
    val undocumented = jdbcTemplate.queryForList(
      """
      SELECT c.relname
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relkind = 'r'
        AND c.relname <> 'flyway_schema_history'
        AND obj_description(c.oid) IS NULL
      ORDER BY c.relname
      """.trimIndent(),
      String::class.java,
    )

    assertThat(undocumented)
      .describedAs("tables with no COMMENT ON - add one in a new migration")
      .isEmpty()
  }

  @Test
  fun `every column has a description`() {
    assertThat(columnComments().filter { it.comment == null }.map { it.name })
      .describedAs("columns with no COMMENT ON - add one in a new migration")
      .isEmpty()
  }

  @Test
  fun `every column description carries a sensitivity classification`() {
    val misclassified = columnComments()
      .filter { it.comment != null && !SENSITIVITY.containsMatchIn(it.comment) }
      .map { it.name }

    assertThat(misclassified)
      .describedAs("column comments must end with one of $SENSITIVITY - see V15__schema_comments.sql")
      .isEmpty()
  }

  /**
   * The answer matters more than the tag. A new column defaults to nothing, so whoever adds one has to
   * decide whether its value reaches a prisoner's report - which is the same decision SarJpaEntitiesTest
   * forces from the entity side, asked here of the schema.
   */
  @Test
  fun `every column description says whether it is disclosed in a subject access request`() {
    val unclassified = columnComments()
      .filter { it.comment != null && !SAR_IMPACT.containsMatchIn(it.comment) }
      .map { it.name }

    assertThat(unclassified)
      .describedAs("column comments need a [SAR: Y] or [SAR: N] tag - see V20__sar_impact.sql")
      .isEmpty()
  }

  @Test
  fun `every column description carries an example value`() {
    val missing = columnComments()
      .filter { it.comment != null && !EXAMPLE.containsMatchIn(it.comment) }
      .map { it.name }

    assertThat(missing)
      .describedAs("column comments need an [Example: ...] tag - see V19__example_values.sql")
      .isEmpty()
  }

  private data class ColumnComment(val name: String, val comment: String?)

  private fun columnComments(): List<ColumnComment> = jdbcTemplate.query(
    """
    SELECT c.table_name || '.' || c.column_name       AS name,
           col_description(pc.oid, c.ordinal_position) AS comment
    FROM information_schema.columns c
    JOIN pg_class pc
      ON pc.relname = c.table_name
     AND pc.relnamespace = 'public'::regnamespace
     AND pc.relkind = 'r'
    WHERE c.table_schema = 'public'
      AND c.table_name <> 'flyway_schema_history'
    ORDER BY c.table_name, c.ordinal_position
    """.trimIndent(),
  ) { rs, _ -> ColumnComment(rs.getString("name"), rs.getString("comment")) }

  private companion object {
    val SENSITIVITY = Regex("""\[Sensitivity: (NONE|PERSONAL|STAFF|SPECIAL-CATEGORY|OFFICIAL-SENSITIVE)]$""")

    // Both of these sit before the sensitivity tag, which SENSITIVITY anchors to the end of the comment,
    // but neither is matched against its neighbours - a fourth tag would otherwise break them silently.
    // The SAR Data Requirements extract the Offender SAR Team review needs both for every element.
    val EXAMPLE = Regex("""\[Example: [^]]+]""")

    val SAR_IMPACT = Regex("""\[SAR: [YN]]""")
  }
}
