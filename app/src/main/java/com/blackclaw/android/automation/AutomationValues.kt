package com.blackclaw.android.automation

/** Gson decodes untyped JSON numbers as Double; these helpers keep profile parsing stable. */
internal fun automationInt(value: Any?): Int? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
    else -> value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
}

internal fun automationLong(value: Any?): Long? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it == it.toLong().toDouble() }?.toLong()
    else -> value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it == it.toLong().toDouble() }?.toLong()
}

internal fun automationFloat(value: Any?): Float? = when (value) {
    is Number -> value.toFloat()
    else -> value?.toString()?.toFloatOrNull()
}
