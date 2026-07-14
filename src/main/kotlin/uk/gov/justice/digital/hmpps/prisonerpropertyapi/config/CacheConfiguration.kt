package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

@Configuration
@EnableCaching
@EnableScheduling
class CacheConfiguration {

  // Only prison names are cached. They are a large, rarely-changing reference lookup where a stale *name* is
  // merely cosmetic, so a per-pod cache is acceptable. Property locations are deliberately NOT cached - they
  // drive capacity/validation decisions that must stay consistent across pods, so they are read live (see
  // LocationsClient.getPropertyLocations).
  @Bean
  fun cacheManager(): CacheManager = ConcurrentMapCacheManager(PRISON_NAMES_CACHE_NAME)

  @CacheEvict(value = [PRISON_NAMES_CACHE_NAME], allEntries = true)
  @Scheduled(fixedDelay = TTL_PRISON_NAMES, timeUnit = TimeUnit.HOURS)
  fun cacheEvictPrisonNames() {
    log.info("Evicting cache: {} after {} hours", PRISON_NAMES_CACHE_NAME, TTL_PRISON_NAMES)
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
    const val PRISON_NAMES_CACHE_NAME: String = "prisonNames"
    const val TTL_PRISON_NAMES: Long = 24
  }
}
