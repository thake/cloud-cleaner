package cloudcleaner.aws.resources

import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import io.kotest.matchers.equality.shouldBeEqualToUsingFields
import io.kotest.matchers.shouldBe

fun <T : ServiceException> T.shouldBeEquivalentTo(other: T) {
  this::class.shouldBe(other::class)
  this.sdkErrorMetadata.shouldBeEqualToUsingFields(other.sdkErrorMetadata, ServiceErrorMetadata::errorCode)
}
