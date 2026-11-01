package dev.anime.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonElement?.int(): Int? = str()?.toIntOrNull()
fun JsonElement?.dbl(): Double? = str()?.toDoubleOrNull()
