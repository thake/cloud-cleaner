package awspurge.config

import awspurge.TestResource
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RegexFilterTest {

    @Test
    fun `matches should return true for matching regex`() {
        val filter = RegexFilter(".*test.*")
        val resource = TestResource(id = "id", name = "testResource")

        val result = filter.matches(resource)

        result.shouldBe(true)
    }

    @Test
    fun `matches should return true if we only have a partial match`() {
        val filter = RegexFilter("^test")
        val resource = TestResource(id = "id", name = "test some more")

        val result = filter.matches(resource)

        result.shouldBe(true)
    }

    @Test
    fun `matches should honor starting anchors`() {
        val filter = RegexFilter("^test")
        val resource = TestResource(id = "id", name = "some test")

        val result = filter.matches(resource)

        result.shouldBe(false)
    }
}