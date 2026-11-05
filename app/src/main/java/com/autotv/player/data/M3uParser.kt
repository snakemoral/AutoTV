package com.autotv.player.data

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    val tvgId: String?
)

/** Parser de playlists M3U/M3U8 (#EXTINF). Sem dependências do Android. */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        var tvgId: String? = null
        var extGrp: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = firstCommaOutsideQuotes(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val attrs = attrRegex.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = title.ifBlank { attrs["tvg-name"].orEmpty() }
                    group = attrs["group-title"]
                    logo = attrs["tvg-logo"]
                    tvgId = attrs["tvg-id"]
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGrp = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                line.contains("://") -> {
                    val finalName = name?.takeIf { it.isNotBlank() } ?: fallbackName(line)
                    out += M3uEntry(
                        name = finalName,
                        url = line,
                        group = (group ?: extGrp)?.takeIf { it.isNotBlank() },
                        logo = logo?.takeIf { it.isNotBlank() },
                        tvgId = tvgId?.takeIf { it.isNotBlank() }
                    )
                    name = null; group = null; logo = null; tvgId = null
                }
            }
        }
        return out
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return i
        }
        return -1
    }

    private fun fallbackName(url: String): String =
        url.substringAfter("://").substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
}
