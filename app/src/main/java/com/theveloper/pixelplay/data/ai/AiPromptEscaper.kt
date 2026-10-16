/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Standalone escape utilities for AI prompt construction.
 * Extracted from AiPlaylistGenerator so they can be unit-tested without
 * instantiating the full AiPlaylistGenerator (which requires 6 Hilt-injected
 * dependencies).
 */
package com.theveloper.pixelplay.data.ai

/**
 * Escape utilities for AI prompt construction.
 *
 * Used by AiPlaylistGenerator to safely interpolate user-supplied and
 * library-supplied strings into JSON string literals and XML element content.
 */
object AiPromptEscaper {

    /**
     * Escape a string for safe interpolation inside a JSON string literal.
     * Handles: " \ / \b \f \n \r \t and U+0000..U+001F control chars.
     *
     * Without this, song titles containing backslash or newline would produce
     * invalid JSON that the LLM would misparse.
     */
    fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length + 8)
        for (c in input) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append("%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Escape a string for safe interpolation inside XML element text content.
     * Prevents prompt injection via '</query>' or similar XML metacharacters.
     * Handles: & < > " '
     */
    fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length + 8)
        for (c in input) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
