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

  @Bean
  fun cacheManager(): CacheManager = ConcurrentMapCacheManager(PRISON_NAMES_CACHE_NAME, PROPERTY_LOCATIONS_CACHE_NAME)

  @CacheEvict(value = [PRISON_NAMES_CACHE_NAME], allEntries = true)
  @Scheduled(fixedDelay = TTL_PRISON_NAMES, timeUnit = TimeUnit.HOURS)
  fun cacheEvictPrisonNames() {
    log.info("Evicting cache: {} after {} hours", PRISON_NAMES_CACHE_NAME, TTL_PRISON_NAMES)
  }

  // Property locations are evicted on write (see PropertyLocationAdminService), but that only clears the
  // writing pod's local map. This scheduled evict is the cross-pod safety net so an admin's add/remove
  // propagates to every pod's container-add read paths within a few minutes (the admin screens themselves
  // read live and so never wait on this).
  @CacheEvict(value = [PROPERTY_LOCATIONS_CACHE_NAME], allEntries = true)
  @Scheduled(fixedDelay = TTL_PROPERTY_LOCATIONS, timeUnit = TimeUnit.MINUTES)
  fun cacheEvictPropertyLocations() {
    log.info("Evicting cache: {} after {} minutes", PROPERTY_LOCATIONS_CACHE_NAME, TTL_PROPERTY_LOCATIONS)
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
    const val PRISON_NAMES_CACHE_NAME: String = "prisonNames"
    const val TTL_PRISON_NAMES: Long = 24
    const val PROPERTY_LOCATIONS_CACHE_NAME: String = "propertyLocations"
    const val TTL_PROPERTY_LOCATIONS: Long = 10
  }
}
