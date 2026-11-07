package voice.core.bookorbit

import kotlinx.serialization.Serializable

@Serializable
data class CloudflareAccess(
  val clientId: String = "",
  val clientSecret: String = "",
) {
  override fun toString(): String = "CloudflareAccess(redacted)"
}
