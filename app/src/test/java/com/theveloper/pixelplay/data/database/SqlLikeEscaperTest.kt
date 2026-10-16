package com.theveloper.pixelplay.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SqlLikeEscaper].
 *
 * Verifies that user-supplied search queries are properly escaped before being
 * interpolated into SQLite LIKE patterns. Without escaping, the wildcard
 * characters `%` (any sequence) and `_` (any single char) in the user's query
 * would match unintended rows.
 */
class SqlLikeEscaperTest {

    @Test
    fun `empty string returns empty`() {
        assertEquals("", SqlLikeEscaper.escapeForLike(""))
    }

    @Test
    fun `plain text without wildcards is unchanged`() {
        assertEquals("hello world", SqlLikeEscaper.escapeForLike("hello world"))
    }

    @Test
    fun `percent sign is escaped`() {
        // User searches for "100%" — the % should be literal, not a wildcard
        val escaped = SqlLikeEscaper.escapeForLike("100%")
        assertEquals("100\\%", escaped)
    }

    @Test
    fun `underscore is escaped`() {
        // User searches for "file_name" — the _ should be literal, not a wildcard
        val escaped = SqlLikeEscaper.escapeForLike("file_name")
        assertEquals("file\\_name", escaped)
    }

    @Test
    fun `backslash is escaped`() {
        // User searches for a path with backslash — the \ itself must be escaped first
        val escaped = SqlLikeEscaper.escapeForLike("C:\\Music")
        assertEquals("C:\\\\Music", escaped)
    }

    @Test
    fun `multiple wildcards are all escaped`() {
        val escaped = SqlLikeEscaper.escapeForLike("100%_done\\")
        assertEquals("100\\%\\_done\\\\", escaped)
    }

    @Test
    fun `unicode text is preserved`() {
        val escaped = SqlLikeEscaper.escapeForLike("café ñ年")
        assertEquals("café ñ年", escaped)
    }

    @Test
    fun `mixed wildcards and normal text`() {
        val escaped = SqlLikeEscaper.escapeForLike("test_100%_case")
        assertEquals("test\\_100\\%\\_case", escaped)
    }

    @Test
    fun `only wildcards`() {
        val escaped = SqlLikeEscaper.escapeForLike("%_\\")
        assertEquals("\\%\\_\\\\", escaped)
    }

    @Test
    fun `escaped string does not contain unescaped wildcards`() {
        val inputs = listOf("100%", "file_name", "path\\to", "a%b_c\\d%")
        for (input in inputs) {
            val escaped = SqlLikeEscaper.escapeForLike(input)
            // After escaping, every %, _, \ should be preceded by \
            val unescapedWildcards = Regex("(?<!\\\\)[%_\\\\]").findAll(escaped).toList()
            assertTrue(
                "Input '$input' escaped to '$escaped' still has unescaped wildcards at positions: " +
                    unescapedWildcards.joinToString { it.range.first.toString() },
                unescapedWildcards.isEmpty()
            )
        }
    }
}
