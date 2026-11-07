package voice.core.bookorbit

internal fun CloudflareAccess.isValid(): Boolean =
  listOf(clientId, clientSecret).all { value ->
    value.isNotEmpty() && value.all { it in '!'..'~' }
  }
