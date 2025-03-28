package awspurge.config

import awspurge.resources.Resource

interface Filter {
    fun matches(resource: Resource): Boolean
}

data class TypeFilter(
    val type: String,
    val filters: List<Filter>,
): Filter {
    override fun matches(resource: Resource): Boolean {
        if (resource.type != type) {
            return false
        }
        return filters.any { it.matches(resource) }
    }
}

data class RegexFilter(
    val regex: String,
    val property: String? = null
) : Filter {
    private val regexObject: Regex = Regex(regex)
    private val propertyExtractor = PropertyExtractor(property)
    override fun matches(resource: Resource) =
        propertyExtractor.matchProperty(resource) { regexObject.containsMatchIn(it) }
}

data class ContainsFilter(
    val contains: String,
    val property: String? = null
) : Filter {
    private val propertyExtractor = PropertyExtractor(property)

    override fun matches(resource: Resource) =
        propertyExtractor.matchProperty(resource){ it.contains(contains) }
}

data class ValueFilter(
    val value: String,
    val property: String? = null
) : Filter {
    private val propertyExtractor = PropertyExtractor(property)

    override fun matches(resource: Resource) =
        propertyExtractor.matchProperty(resource) { it == value }
}

class PropertyExtractor(
    private val property: String?
) {
    fun matchProperty(resource: Resource, matcher: (String) -> Boolean): Boolean {
        return getPropertyValue(resource)?.let(matcher) == true
    }
    private fun getPropertyValue(resource: Resource): String? {
        return if (property != null) {
            resource.properties[property]
        } else {
            resource.name
        }
    }
}