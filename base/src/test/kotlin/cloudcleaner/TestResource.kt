package cloudcleaner

import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.StringId

data class TestResource(
    override val id: Id,
    override val name: String = id.toString(),
    override val properties: Map<String, String> = emptyMap(),
    override val type: String = "Test",
    override val dependsOn: Set<StringId> = emptySet(),
    override val containedResources: Set<StringId> = emptySet(),

    ) : Resource {

    constructor(id: String,
                name: String = id,
                dependsOn: Set<String> = emptySet(),
                contains: Set<String> = emptySet()) : this(
        id = StringId(id),
        name = name,
        dependsOn = dependsOn.map { StringId(it) }.toSet(),
        containedResources = contains.map { StringId(it) }.toSet()
    )
}
