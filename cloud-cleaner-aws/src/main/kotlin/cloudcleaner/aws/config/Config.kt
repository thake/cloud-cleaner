package cloudcleaner.aws.config

import cloudcleaner.config.Filter

data class Config(
    val regions: List<String>,
    val accounts: List<AccountConfig>,
    val resourceTypes: ResourceTypes,
) {
  data class AccountConfig(
      val accountId: String,
      val assumeRole: String?,
      val profile: String?,
      val excludeFilters: List<Filter>,
      val includeFilters: List<Filter>,
  )

  data class ResourceTypes(val includes: List<String>, val excludes: List<String>) {
    fun isIncluded(type: String): Boolean {
      if (includes.isEmpty() && excludes.isEmpty()) {
        return true
      }
      return type in includes && type !in excludes
    }
  }
}
