package voice.core.bookorbit

import kotlinx.serialization.json.Json

internal val bookOrbitJson: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}
