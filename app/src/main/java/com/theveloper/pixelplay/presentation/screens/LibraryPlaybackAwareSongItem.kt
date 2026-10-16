package com.theveloper.pixelplay.presentation.screens

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem

/**
 * Lightweight playback projection shared by every song item in a list. Parent
 * tabs collect this once from PlayerViewModel and pass the same instance down
 * to every item, so a list of 100 items causes one upstream subscription
 * instead of 100 N×N collectors.
 */
@Immutable
internal data class LibraryPlaybackHints(
    val currentSongId: String? = null,
    val isPlaying: Boolean = false
)

@OptIn(UnstableApi::class)
@Composable
internal fun LibraryPlaybackAwareSongItem(
    song: Song,
    playbackHints: LibraryPlaybackHints,
    albumArtSize: Dp = 50.dp,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    val playbackUiState by remember(song.id, playerViewModel) {
        playerViewModel.stablePlayerState
            .map { state ->
                val isCurrentSong = state.currentSong?.id == song.id
                LibrarySongPlaybackUiState(
                    isCurrentSong = isCurrentSong,
                    isPlaying = isCurrentSong && state.isPlaying
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = LibrarySongPlaybackUiState())

    val playerConfig by playerViewModel.playerConfigSlice.collectAsStateWithLifecycle()
    val reduceAnimations = playerConfig.reduceAnimations

    val isCurrentSong = playbackHints.currentSongId == song.id
    EnhancedSongListItem(
        song = song,
        isPlaying = isCurrentSong && playbackHints.isPlaying,
        isCurrentSong = isCurrentSong,
        isLoading = false,
        albumArtSize = albumArtSize,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onMoreOptionsClick = onMoreOptionsClick,
        onClick = onClick,
        reduceAnimations = reduceAnimations
    )
}
