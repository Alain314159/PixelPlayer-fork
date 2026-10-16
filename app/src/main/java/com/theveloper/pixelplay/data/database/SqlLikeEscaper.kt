/*
 * Copyright (C) 2026 PixelPlayer
 *
 * SQL LIKE pattern escaping helpers.
 *
 * SQLite LIKE treats `%` (any sequence) and `_` (any single char) as wildcards.
 * When user-supplied search input is interpolated into a LIKE pattern, the
 * user's literal `%` and `_` characters become wildcards, producing wrong
 * results — e.g. searching for "100%" matches every row containing "100"
 * followed by anything. This utility escapes those metacharacters so the
 * user input is treated literally.
 *
 * Usage:
 *   @Query("""
 *       SELECT * FROM songs
 *       WHERE title LIKE '%' || :query || '%' ESCAPE '\'
 *   """)
 *   fun searchByTitle(query: String): Flow<List<SongEntity>>
 *
 *   val safeQuery = SqlLikeEscaper.escapeForLike(userInput)
 *
 * Note: the SQL must include `ESCAPE '\'` for the escape character to take effect.
 */
package com.theveloper.pixelplay.data.database

object SqlLikeEscaper {
    /**
     * Escape `%`, `_` and the escape character itself (`\`) in the user-supplied
     * search query so it can be used safely inside a SQLite LIKE pattern with
     * `ESCAPE '\'`.
     */
    fun escapeForLike(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length * 2)
        for (c in input) {
            when (c) {
                '\\', '%', '_' -> {
                    sb.append('\\')
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
