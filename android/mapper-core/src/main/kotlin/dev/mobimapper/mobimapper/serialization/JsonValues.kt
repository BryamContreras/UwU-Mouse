package dev.mobimapper.mobimapper.serialization

import org.json.JSONObject

/** Use the Object overload shared by Android and the JVM JSON implementation. */
internal fun JSONObject.putValue(key: String, value: Any?): JSONObject = put(key, value)
