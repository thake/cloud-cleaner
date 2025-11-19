package cloudcleaner.aws.resources.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun S3Client.listObjectVersionsPaginated(block: ListObjectVersionsRequest.Builder.() -> Unit): Flow<ListObjectVersionsResponse> =
    listObjectVersionsPaginated(ListObjectVersionsRequest { apply(block) })
fun S3Client.listObjectVersionsPaginated(initialRequest: ListObjectVersionsRequest) =
    flow {
      data class Cursor(val keyMarker: String?, val versionIdMarker: String?) {
        fun isEmpty(): Boolean = keyMarker == null && versionIdMarker == null
      }
      var cursor = Cursor(initialRequest.keyMarker, initialRequest.versionIdMarker)
      var hasNextPage = true

      while (hasNextPage) {
        val req = initialRequest.copy {
          this.keyMarker = cursor.keyMarker
          this.versionIdMarker = cursor.versionIdMarker
        }
        val result = this@listObjectVersionsPaginated.listObjectVersions(req)
        cursor = Cursor(result.nextKeyMarker, result.nextVersionIdMarker)
        hasNextPage = !cursor.isEmpty()
        emit(result)
      }
    }
