package cloudcleaner.aws.resources.lambda

import cloudcleaner.resources.Id

data class LambdaFunctionName(val value: String, val region: String) : Id {
  override fun toString() = "$value ($region)"
}
