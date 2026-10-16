/*
 * Copyright (C) 2026 PixelPlayer
 *
 * DAO for [PlaybackEventEntity].
 *
 * Queries are designed to support the existing PlaybackStatsRepository use cases without
 * loading all events into memory:
 *  - range queries for summary computations
 *  - bounded fetches for timeline entries
 *  - periodic cleanup of events older than the 2-year retention window
 */
package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PlaybackEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<PlaybackEventEntity>): List<Long>

    /**
     * Insert a single event and return the row id. Convenience wrapper for
     * PlaybackStatsRepository.recordPlay() that does not require constructing the entity
     * at the call site.
     */
    @Query(
        """
        INSERT INTO playback_events (songId, timestamp, durationMs, startTimestamp, endTimestamp)
        VALUES (:songId, :timestamp, :durationMs, :startTimestamp, :endTimestamp)
        """
    )
    suspend fun recordPlay(
        songId: String,
        timestamp: Long,
        durationMs: Long,
        startTimestamp: Long?,
        endTimestamp: Long?
    ): Long

    @Query("SELECT * FROM playback_events WHERE timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC")
    suspend fun getEventsInRange(fromMs: Long, toMs: Long): List<PlaybackEventEntity>

    @Query("SELECT * FROM playback_events ORDER BY timestamp ASC")
    fun observeAllEvents(): Flow<List<PlaybackEventEntity>>

    @Query("SELECT * FROM playback_events ORDER BY timestamp ASC")
    suspend fun getAllEvents(): List<PlaybackEventEntity>

    @Query("SELECT COUNT(*) FROM playback_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM playback_events WHERE timestamp < :cutoffMs")
    suspend fun deleteEventsOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM playback_events")
    suspend fun deleteAll(): Int

    /**
     * Aggregated summary by song for a given time range.
     */
    @Query(
        """
        SELECT songId, SUM(durationMs) AS totalDurationMs, COUNT(*) AS playCount
        FROM playback_events
        WHERE timestamp >= :fromMs AND timestamp <= :toMs
        GROUP BY songId
        ORDER BY totalDurationMs DESC
        LIMIT :limit
        """
    )
    suspend fun getTopSongsByDuration(
        fromMs: Long,
        toMs: Long,
        limit: Int
    ): List<SongStatsRow>

    /**
     * Aggregated summary by artist for a given time range. Joins with the songs table
     * to resolve the artist_name for each played song.
     */
    @Query(
        """
        SELECT s.artist_name AS artist, SUM(p.durationMs) AS totalDurationMs, COUNT(*) AS playCount,
               COUNT(DISTINCT p.songId) AS uniqueSongs
        FROM playback_events p
        INNER JOIN songs s ON CAST(s.id AS TEXT) = p.songId
        WHERE p.timestamp >= :fromMs AND p.timestamp <= :toMs
        GROUP BY s.artist_name
        ORDER BY totalDurationMs DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtistsByDuration(
        fromMs: Long,
        toMs: Long,
        limit: Int
    ): List<ArtistStatsRow>

    /**
     * Timeline aggregation: total play duration per day.
     */
    @Query(
        """
        SELECT (timestamp / 86400000) AS dayBucket, SUM(durationMs) AS totalDurationMs, COUNT(*) AS playCount
        FROM playback_events
        WHERE timestamp >= :fromMs AND timestamp <= :toMs
        GROUP BY dayBucket
        ORDER BY dayBucket ASC
        """
    )
    suspend fun getDailyTimeline(
        fromMs: Long,
        toMs: Long
    ): List<TimelineRow>
}

data class SongStatsRow(
    val songId: String,
    val totalDurationMs: Long,
    val playCount: Int
)

data class ArtistStatsRow(
    val artist: String,
    val totalDurationMs: Long,
    val playCount: Int,
    val uniqueSongs: Int
)

data class TimelineRow(
    val dayBucket: Long,
    val totalDurationMs: Long,
    val playCount: Int
)
