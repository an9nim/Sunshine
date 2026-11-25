package com.sunshine.mesh.data.update

/**
 * Compares a GitHub tag like `v0.2.0` with `BuildConfig.VERSION_NAME`.
 * Returns null when either value is not a dotted numeric version.
 */
internal fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean? {
    val remote = parseVersion(remoteTag) ?: return null
    val current = parseVersion(currentVersion) ?: return null
    for (index in 0 until VERSION_PARTS) {
        if (remote[index] != current[index]) {
            return remote[index] > current[index]
        }
    }
    return false
}

private fun parseVersion(raw: String): IntArray? {
    val core = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
    val parts = core.split('.')
    if (parts.isEmpty() || parts.size > VERSION_PARTS) return null

    val numbers = IntArray(VERSION_PARTS)
    for ((index, part) in parts.withIndex()) {
        val value = part.toIntOrNull() ?: return null
        if (value < 0) return null
        numbers[index] = value
    }
    return numbers
}

private const val VERSION_PARTS = 3
