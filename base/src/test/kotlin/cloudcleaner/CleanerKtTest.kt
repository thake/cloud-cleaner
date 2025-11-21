@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package cloudcleaner

import cloudcleaner.resources.Resource
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.should
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CleanerKtTest {
    @Test
    fun `produceDeletableResources should return empty list if no resources are found`() = runTest {
        coroutineScope {
            val deletableResources = produceDeletableResources(emptyMap())

            deletableResources.shouldBeEmpty()
        }
    }

    @Test
    fun `produceDeletableResources should return resources that no one depends on by dependsOn`() = runTest {
        coroutineScope {
            // A
            // B
            // C -> A
            // D -> B
            // E -> D -> B
            val a = TestResource("A")
            val b = TestResource("B")
            val c = TestResource("C", dependsOn = setOf("A"))
            val d = TestResource("D", dependsOn = setOf("B"))
            val e = TestResource("E", dependsOn = setOf("D"))
            val map = listOf(a, b, c, d, e).associateBy { it.id }

            val deletableResources = produceDeletableResources(map)

            val receivedElements = setOf(
                deletableResources.receive(),
                deletableResources.receive()
            )
            deletableResources.shouldBeEmpty()
            receivedElements shouldContainAll listOf(e, e)
        }
    }
    @Test
    fun `produceDeletableResources should return resources that no one depends on by dependsOn and contains`() = runTest {
        coroutineScope {
            // A
            // B
            // C -> A (contains 1)
            // D -> B
            // E -> D -> B
            // F -> 1
            val a = TestResource("A")
            val b = TestResource("B")
            val c = TestResource("C", dependsOn = setOf("A"), contains = setOf("1"))
            val d = TestResource("D", dependsOn = setOf("B"))
            val e = TestResource("E", dependsOn = setOf("D"))
            val f = TestResource("F", dependsOn = setOf("1"))
            val map = listOf(a, b, c, d, e, f).associateBy { it.id }

            val deletableResources = produceDeletableResources(map)

            val receivedElements = setOf(
                deletableResources.receive(),
                deletableResources.receive()
            )
            deletableResources.shouldBeEmpty()
            receivedElements shouldContainAll listOf(e, f)
        }
    }
  @Test
  fun `produceDeletableResources should return resources that are not contained in another resource`() = runTest {
    coroutineScope {
      val a = TestResource("A")
      val c = TestResource("C", contains = setOf("A"))
      val map = listOf(a, c).associateBy { it.id }

      val deletableResources = produceDeletableResources(map)

      val receivedElements = mutableListOf<Resource>()
      for(element in deletableResources) {
          receivedElements.add(element)
      }
      receivedElements shouldContainOnly listOf(c)
    }
  }
  @Test
  fun `produceDeletableResources should not consider contained resources when checking for dependencies`() = runTest {
    coroutineScope {
      val b = TestResource("role")
      val a = TestResource("stack", dependsOn = setOf("role"))
      val c = TestResource("roleStack", contains = setOf("role"))
      val map = listOf(a, b, c).associateBy { it.id }

      val deletableResources = produceDeletableResources(map)

      val receivedElements = mutableListOf<Resource>()
      for(element in deletableResources) {
        receivedElements.add(element)
      }
      receivedElements shouldContainOnly listOf(a)
    }
  }

  @Test
  fun `produceDeletableResources should delete container with interdependent contained resources`() = runTest {
    coroutineScope {
      val role = TestResource("role")
      val policy = TestResource("rolePolicy", dependsOn = setOf("role"))
      val stack = TestResource("stack", contains = setOf("role", "rolePolicy"))
      val map = listOf(stack, role, policy).associateBy { it.id }

      val deletableResources = produceDeletableResources(map)

      val receivedElements = mutableListOf<Resource>()
      for(element in deletableResources) {
        receivedElements.add(element)
      }
      receivedElements shouldContainOnly listOf(stack)
    }
  }
  @Test
  fun `produceDeletableResources should delete container with deep interdependent contained resources`() = runTest {
    val role = TestResource("role")
    val policy = TestResource("rolePolicy", dependsOn = setOf("role"))
    val stack1 = TestResource("stack1", contains = setOf("role", "nestedStack1"))
    val nestedStack1 = TestResource("nestedStack1", contains = setOf("nestedStack2"))
    val nestedStack2 = TestResource("nestedStack2", contains = setOf("rolePolicy"))
    val map = listOf(stack1, nestedStack1, nestedStack2, role, policy).associateBy { it.id }
    // when
    val deletableResources = produceDeletableResources(map)

    // then
    val receivedElements = mutableListOf<Resource>()
    for(element in deletableResources) {
      receivedElements.add(element)
    }
    receivedElements shouldContainOnly listOf(stack1)

  }

  }

fun <T> ReceiveChannel<T>.shouldBeEmpty() = this should beEmpty()
fun <T> beEmpty() = object : Matcher<ReceiveChannel<T>> {
    override fun test(value: ReceiveChannel<T>) = MatcherResult(
        value.isEmpty,
        { "ReceiveChannel should be empty" },
        { "ReceiveChannel should not be empty" }
    )
}
