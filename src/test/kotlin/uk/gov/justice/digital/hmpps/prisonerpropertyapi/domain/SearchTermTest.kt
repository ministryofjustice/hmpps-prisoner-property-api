package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchTermTest {

  @Test
  fun `a plain term matches anywhere in the value, ignoring case`() {
    val matcher = SearchTerm.toWildcardRegex("5638")

    assertThat(matcher.containsMatchIn("PB5638")).isTrue()
    assertThat(matcher.containsMatchIn("PROP-PB5638-A")).isTrue()
    assertThat(matcher.containsMatchIn("PB0200")).isFalse()

    assertThat(SearchTerm.toWildcardRegex("reception").containsMatchIn("Reception Box A")).isTrue()
  }

  @Test
  fun `wildcards are honoured and regex metacharacters are not`() {
    assertThat(SearchTerm.toWildcardRegex("re*store").containsMatchIn("Reception Property Store")).isTrue()
    assertThat(SearchTerm.toWildcardRegex("PB?638").containsMatchIn("PB5638")).isTrue()
    assertThat(SearchTerm.toWildcardRegex("PB?638").containsMatchIn("PB5X638")).isFalse()

    // A term of regex punctuation must match those characters, not act as a pattern.
    assertThat(SearchTerm.toWildcardRegex("A.C").containsMatchIn("A.C")).isTrue()
    assertThat(SearchTerm.toWildcardRegex("A.C").containsMatchIn("ABC")).isFalse()
  }

  @Test
  fun `a like pattern matches anywhere in the value`() {
    assertThat(SearchTerm.toLikePattern("seal")).isEqualTo("%seal%")
  }

  @Test
  fun `like metacharacters in the term are escaped so they match literally`() {
    // Without escaping, a seal containing % or _ would turn the rest of the term into a wildcard.
    assertThat(SearchTerm.toLikePattern("100%")).isEqualTo("%100\\%%")
    assertThat(SearchTerm.toLikePattern("a_b")).isEqualTo("%a\\_b%")
    assertThat(SearchTerm.toLikePattern("a\\b")).isEqualTo("%a\\\\b%")
  }

  @Test
  fun `the wildcards staff can use become their like equivalents`() {
    assertThat(SearchTerm.toLikePattern("se*01")).isEqualTo("%se%01%")
    assertThat(SearchTerm.toLikePattern("se?01")).isEqualTo("%se_01%")
  }
}
