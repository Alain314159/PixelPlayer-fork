/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Centralized UI dimensions for the PixelPlayer Compose UI.
 *
 * Extracted to avoid magic numbers scattered across 47+ composable files.
 * Following DRY: every dimension that appears in more than one file lives here.
 */
package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard dimensions used across the app's Compose UI.
 *
 * Naming convention: [context]_[element]_[property].
 * Example: MINI_PLAYER_HEIGHT = the height of the mini player bar.
 */
object UiDimensions {

    // ─── Mini player ──────────────────────────────────────────────────────

    /** Height of the mini player bar at the bottom of the screen. */
    val MINI_PLAYER_HEIGHT = 64.dp

    /** Size of the skip/play/pause buttons in the mini player. */
    val MINI_PLAYER_BUTTON_SIZE = 36.dp

    /** Size of icons inside mini player buttons. */
    val MINI_PLAYER_ICON_SIZE = 22.dp

    /** Size of the album art thumbnail in the mini player. */
    val MINI_PLAYER_ART_SIZE = 44.dp

    /** Horizontal padding for the mini player row. */
    val MINI_PLAYER_HORIZONTAL_PADDING = 12.dp

    /** Spacing between elements in the mini player. */
    val MINI_PLAYER_SPACING = 8.dp

    // ─── Player sheet ─────────────────────────────────────────────────────

    /** Standard spacing between sections in the player sheet. */
    val PLAYER_SHEET_SECTION_SPACING = 16.dp

    /** Corner radius for cards/containers. */
    val CARD_CORNER_RADIUS = 16.dp

    /** Large corner radius for player sheet. */
    val SHEET_CORNER_RADIUS = 28.dp

    // ─── Song list items ──────────────────────────────────────────────────

    /** Standard height of a song list item row. */
    val SONG_ITEM_HEIGHT = 56.dp

    /** Album art thumbnail size in list items. */
    val SONG_ITEM_ART_SIZE = 48.dp

    /** Spacing between album art and text in list items. */
    val SONG_ITEM_TEXT_SPACING = 12.dp

    // ─── Buttons ─────────────────────────────────────────────────────────

    /** Standard touch target size (Material 3 minimum). */
    val BUTTON_TOUCH_TARGET = 48.dp

    /** Compact button size for tight spaces. */
    val BUTTON_COMPACT_SIZE = 36.dp

    /** Standard icon size for buttons. */
    val BUTTON_ICON_SIZE = 24.dp

    /** Small icon size (16dp). */
    val ICON_SMALL = 16.dp

    /** Medium icon size (22dp). */
    val ICON_MEDIUM = 22.dp

    /** Large icon size (28dp). */
    val ICON_LARGE = 28.dp

    // ─── Spacing ─────────────────────────────────────────────────────────

    /** Extra small spacing (4dp). */
    val SPACING_XS = 4.dp

    /** Small spacing (8dp). */
    val SPACING_S = 8.dp

    /** Medium spacing (12dp). */
    val SPACING_M = 12.dp

    /** Large spacing (16dp). */
    val SPACING_L = 16.dp

    /** Extra large spacing (24dp). */
    val SPACING_XL = 24.dp

    // ─── Album / Artist detail ───────────────────────────────────────────

    /** Album art size in detail screen header. */
    val ALBUM_DETAIL_ART_SIZE = 200.dp

    /** Artist image size in detail screen header. */
    val ARTIST_DETAIL_IMAGE_SIZE = 180.dp

    // ─── Lyrics ──────────────────────────────────────────────────────────

    /** Active (highlighted) lyric line font size. */
    val LYRICS_ACTIVE_FONT_SIZE = 20.sp

    /** Inactive lyric line font size. */
    val LYRICS_INACTIVE_FONT_SIZE = 16.sp

    /** Spacing between lyric lines. */
    val LYRICS_LINE_SPACING = 8.dp
}

// Re-export for backward compatibility — files that imported MiniPlayerHeight
// from this package will continue to work.
val MiniPlayerHeight = UiDimensions.MINI_PLAYER_HEIGHT
