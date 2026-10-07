package org.adm.intellij.ide

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Null-safe readers over the JSON `adm --json` commands print. Gson's own
 * `getAsJsonObject`/`getAsJsonArray` throw on a `null` value, which the CLI
 * emits for an absent table or list.
 */
internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

internal fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** The array's string members, skipping anything that is not a primitive. */
internal fun JsonArray?.strings(): List<String> = this?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

/** The array's object members. */
internal fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this?.takeIf { it.isJsonArray }?.asJsonArray
