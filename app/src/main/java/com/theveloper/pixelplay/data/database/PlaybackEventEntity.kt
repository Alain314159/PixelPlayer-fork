/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Room entity for persisted playback history events.
 *
 * Replaces the previous JSON-file-based storage in PlaybackStatsRepository which was:
 *  - not transactional (race conditions between read-modify-write cycles)
 *  - forced loading the entire 2-year history into memory for every summary computation
 *  - rewrote the entire JSON file on every recordPlayback call (~5MB JSON + fd.sync())
 *
 * Now events live in Room with proper indexing on (timestamp, songId), enabling:
 *  - range queries (e.g. "all events in October 2026") without loading all events
 *  - batched writes via Room transactions
 *  - automatic cleanup via DELETE WHERE timestamp < ? (instead of in-memory filter)
 *
 * Migration v42→v43 imports existing JSON file contents into this table.
 */
package com.theveloper.pixelplay.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["songId"]),
        Index(value = ["timestamp", "songId"])
    ]
)
data class PlaybackEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val songId: String,
    val timestamp: Long,
    val durationMs: Long,
    val startTimestamp: Long?,
    val endTimestamp: Long?
)
