package com.theveloper.pixelplay.presentation.viewmodel

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Trace
import android.util.Log
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.data.model.LibraryTabId
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Timeline
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.FolderSource
import com.theveloper.pixelplay.data.model.Genre
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.toLibraryTabIdOrNull
import com.theveloper.pixelplay.data.provider.SharedArtworkContentProvider
import com.theveloper.pixelplay.data.preferences.CarouselStyle
import com.theveloper.pixelplay.data.preferences.LibraryNavigationMode
import com.theveloper.pixelplay.data.preferences.NavBarStyle
import com.theveloper.pixelplay.data.preferences.FullPlayerLoadingTweaks
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.preferences.AlbumArtQuality
import com.theveloper.pixelplay.data.preferences.ThemePreference
import com.theveloper.pixelplay.data.repository.LyricsSearchResult
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.MusicNotificationProvider
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.cast.CastRemotePlaybackState
import com.theveloper.pixelplay.data.service.player.CastPlayer
import com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.utils.ValidatedLyricsImport
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.StorageType
import com.theveloper.pixelplay.utils.StorageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil.imageLoader
import coil.memory.MemoryCache
import dagger.Lazy

private const val CAST_LOG_TAG = "PlayerCastTransfer"
private const val ENABLE_FOLDERS_SOURCE_SWITCHING = true
private const val HOME_MIX_PREVIEW_LIMIT = 48
private const val EXTERNAL_SONG_ID_PREFIX = "external:"

internal fun List<Song>.toPlaybackQueue(): ImmutableList<Song> = when (this) {
    is PersistentList<Song> -> this
    is ImmutableList<Song> -> this
    else -> this.toPersistentList()
}

internal fun ImmutableList<Song>.asPersistentPlaybackQueue(): PersistentList<Song> =
    this as? PersistentList<Song> ?: this.toPersistentList()

internal fun ImmutableList<Song>.replaceSong(updatedSong: Song): ImmutableList<Song> {
    val index = indexOfFirst { it.id == updatedSong.id }
    if (index == -1) return this
    return asPersistentPlaybackQueue().set(index, updatedSong)
}

private fun ImmutableList<Song>.removeSongById(songId: String): ImmutableList<Song> {
    val index = indexOfFirst { it.id == songId }
    if (index == -1) return this
    return asPersistentPlaybackQueue().removeAt(index)
}

private fun ImmutableList<Song>.moveSong(fromIndex: Int, toIndex: Int): ImmutableList<Song> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    val movedSong = this[fromIndex]
    return asPersistentPlaybackQueue()
        .removeAt(fromIndex)
        .add(toIndex, movedSong)
}

private fun moveQueueIndex(index: Int, fromIndex: Int, toIndex: Int): Int {
    if (index == C.INDEX_UNSET || fromIndex == toIndex) return index
    return when {
        index == fromIndex -> toIndex
        fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> index - 1
        toIndex < fromIndex && index in toIndex until fromIndex -> index + 1
        else -> index
    }
}

private data class AiUiSnapshot(
    val showAiPlaylistSheet: Boolean,
    val isGeneratingAiPlaylist: Boolean,
    val aiStatus: String?,
    val aiError: String?,
)

private data class SortOptionsSnapshot(
    val songSort: SortOption,
    val albumSort: SortOption,
    val artistSort: SortOption,
    val folderSort: SortOption,
    val favoriteSort: SortOption,
)

private data class PreparedPlaybackQueue(
    val mediaItems: List<MediaItem>,
    val startIndex: Int
)

private data class PendingMetadataEdit(
    val song: Song,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val genre: String,
    val lyrics: String,
    val trackNumber: Int,
    val discNumber: Int?,
    val replayGainTrackGainDb: String?,
    val replayGainAlbumGainDb: String?,
    val coverArtUpdate: CoverArtUpdate?
)

private data class PendingLyricsSave(
    val song: Song,
    val lyrics: Lyrics,
    val preferSynced: Boolean
)

private data class ResolvedAlbumSelection(
    val albums: List<Album>,
    val songs: List<Song>,
    val wasTrimmed: Boolean
)

@UnstableApi
@SuppressLint("LogNotTimber")
@OptIn(coil.annotation.ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val aiPreferencesRepository: AiPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    val syncManager: SyncManager, // Inyectar SyncManager

    private val dualPlayerEngine: DualPlayerEngine,
    private val telegramCacheManagerProvider: Lazy<com.theveloper.pixelplay.data.telegram.TelegramCacheManager>,
    private val listeningStatsTracker: ListeningStatsTracker,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val lyricsStateHolder: LyricsStateHolder,
    private val castStateHolder: CastStateHolder,
    private val castRouteStateHolder: CastRouteStateHolder,
    private val queueStateHolder: QueueStateHolder,
    private val queueUndoStateHolder: QueueUndoStateHolder,
    private val playlistDismissUndoStateHolder: PlaylistDismissUndoStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val sleepTimerStateHolder: SleepTimerStateHolder,
    private val searchStateHolder: SearchStateHolder,
    private val aiStateHolder: AiStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val folderNavigationStateHolder: FolderNavigationStateHolder,
    private val libraryTabsStateHolder: LibraryTabsStateHolder,
    private val castTransferStateHolder: CastTransferStateHolder,
    private val metadataEditStateHolder: MetadataEditStateHolder,
    private val songRemovalStateHolder: SongRemovalStateHolder,
    val themeStateHolder: ThemeStateHolder,
    val multiSelectionStateHolder: MultiSelectionStateHolder,
    val playlistSelectionStateHolder: PlaylistSelectionStateHolder,
    private val playbackDispatchStateHolder: PlaybackDispatchStateHolder,
    private val mediaControllerSyncStateHolder: MediaControllerSyncStateHolder,
    private val sessionToken: SessionToken,
    private val mediaControllerFactory: com.theveloper.pixelplay.data.media.MediaControllerFactory
) : ViewModel() {

    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()

    // Dedicated queue flow so the player sheet's MiniPlayer branch does not
    // recompose whenever the queue changes. Consumers that actually need the
    // queue (FullPlayer carousel, queue sheet) collect this narrower flow
    // directly, keeping the unrelated subtree stable.
    val queueFlow: StateFlow<ImmutableList<Song>> = _playerUiState
        .map { it.currentPlaybackQueue }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = persistentListOf()
        )

    private val _showNoInternetDialog = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val showNoInternetDialog: SharedFlow<Unit> = _showNoInternetDialog.asSharedFlow()

    val stablePlayerState: StateFlow<StablePlayerState> = playbackStateHolder.stablePlayerState
    val albumArtPaletteStyle: StateFlow<AlbumArtPaletteStyle> = themePreferencesRepository
        .albumArtPaletteStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AlbumArtPaletteStyle.default
        )
    /**
     * High-frequency playback position should not force global UI recomposition.
     * Keep a dedicated position flow for real-time UI elements (seek bars, lyrics timing).
     */
    val currentPlaybackPosition: StateFlow<Long> = playbackStateHolder.currentPosition
    val playbackHistory = listeningStatsTracker.playbackHistory

    // Removed: _masterAllSongs was a duplicate of libraryStateHolder.allSongs
    // All reads now delegate to libraryStateHolder.allSongs

    // Lyrics load callback for LyricsStateHolder
    private val lyricsLoadCallback = object : LyricsLoadCallback {
        override fun onLoadingStarted(songId: String) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else state.copy(isLoadingLyrics = true, lyrics = null)
            }
        }

        override fun onLyricsLoaded(songId: String, lyrics: Lyrics?) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else state.copy(isLoadingLyrics = false, lyrics = lyrics)
            }
        }
    }



    private val _playlistPickerStorageFilter = MutableStateFlow(com.theveloper.pixelplay.data.model.StorageFilter.OFFLINE)
    val playlistPickerStorageFilter: StateFlow<com.theveloper.pixelplay.data.model.StorageFilter> = _playlistPickerStorageFilter.asStateFlow()

    /**
     * Paginated songs for efficient display in LibraryScreen.
     * Uses Paging 3 for memory-efficient loading of large libraries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val paginatedSongs: Flow<PagingData<Song>> = libraryStateHolder.songsPagingFlow
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerFavoriteSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedFavoriteSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    private val offlinePlaybackObserverJob = viewModelScope.launch {
        connectivityStateHolder.offlinePlaybackBlocked.collect {
            Timber.w("Received offline blocked event. Showing dialog.")
            _showNoInternetDialog.emit(Unit)
        }
    }

    private var telegramPlaybackObserversStarted = false

    private fun ensureTelegramPlaybackObserversStarted() {
        if (telegramPlaybackObserversStarted) return
        telegramPlaybackObserversStarted = true

        val telegramCacheManager = telegramCacheManagerProvider.get()
        val telegramRepository = musicRepository.telegramRepository

        viewModelScope.launch {
            launch {
                telegramCacheManager.embeddedArtUpdated.collect { updatedArtUri ->
                    refreshArtwork(updatedArtUri)
                }
            }

            launch {
                telegramRepository.downloadCompleted.collect {
                    val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                    if (currentSong != null && currentSong.contentUriString.startsWith("telegram:")) {
                        val uri = Uri.parse(currentSong.contentUriString)
                        val chatId = uri.host?.toLongOrNull()
                        val messageId = uri.pathSegments.firstOrNull()?.toLongOrNull()

                        if (chatId != null && messageId != null) {
                            refreshArtwork("telegram_art://$chatId/$messageId")
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshArtwork(updatedArtUri: String) {
        val currentState = playbackStateHolder.stablePlayerState.value
        val currentSong = currentState.currentSong
        // Check if it matches, ignoring query params for comparison
        val currentUriClean = currentSong?.albumArtUriString?.substringBefore('?')
        val updatedUriClean = updatedArtUri.substringBefore('?')
        
        if (currentUriClean == updatedUriClean) {
            Timber.d("PlayerViewModel: Embedded art updated for current song, forcing refresh")
            
            // 1. Invalidate Coil cache for the BASE uri (without params)
            // This ensures next time we load it without params, it's fresh too.
            val baseUri = currentUriClean
            // Hoist the imageLoader lookup once. Snapshot keys to a Set first
            // — Coil's memoryCache.keys is mutated by remove() and historically
            // wasn't safe to mutate while iterating directly.
            val imageLoader = context.imageLoader
            val memoryCache = imageLoader.memoryCache
            val keySnapshot = memoryCache?.keys?.toSet().orEmpty()
            keySnapshot.forEach { key ->
                if (key.toString().contains(baseUri)) {
                    memoryCache?.remove(key)
                }
            }
            // Remove from Disk Cache
            imageLoader.diskCache?.remove(baseUri)

            // 2. Extract Colors (using base URI)
            themeStateHolder.extractAndGenerateColorScheme(updatedArtUri.toUri(), updatedArtUri, isPreload = false)
            
            // 3. FORCE UI REFRESH by updating the URI with a version timestamp
            // This forces SmartImage to see a "new" model and reload.
            // We keep the quality param if it exists, or add a version param.
            val newUri = if (updatedArtUri.contains("?")) {
                "$updatedArtUri&v=${System.currentTimeMillis()}"
            } else {
                "$updatedArtUri?v=${System.currentTimeMillis()}"
            }
            
            val updatedSong = currentSong.copy(albumArtUriString = newUri)
            
            // Update State
            playbackStateHolder.updateStablePlayerState { state ->
                state.copy(currentSong = updatedSong)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSongArtists: StateFlow<ImmutableList<Artist>> = stablePlayerState
        .map { it.currentSong?.id }
        .distinctUntilChanged()
        .flatMapLatest { songId ->
            val idLong = songId?.toLongOrNull()
            if (idLong == null) flowOf(persistentListOf())
            else musicRepository.getArtistsForSong(idLong).map { it.toImmutableList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    private val _sheetState = MutableStateFlow(PlayerSheetState.COLLAPSED)
    val sheetState: StateFlow<PlayerSheetState> = _sheetState.asStateFlow()
    private val _isSheetVisible = MutableStateFlow(false)
    private val _bottomBarHeight = MutableStateFlow(0)
    val bottomBarHeight: StateFlow<Int> = _bottomBarHeight.asStateFlow()
    private val _predictiveBackCollapseFraction = MutableStateFlow(0f)
    val predictiveBackCollapseFraction: StateFlow<Float> = _predictiveBackCollapseFraction.asStateFlow()
    private val _predictiveBackSwipeEdge = MutableStateFlow<Int?>(null)
    val predictiveBackSwipeEdge: StateFlow<Int?> = _predictiveBackSwipeEdge.asStateFlow()
    private val _isQueueSheetVisible = MutableStateFlow(false)
    val isQueueSheetVisible: StateFlow<Boolean> = _isQueueSheetVisible.asStateFlow()
    private val _isCastSheetVisible = MutableStateFlow(false)
    val isCastSheetVisible: StateFlow<Boolean> = _isCastSheetVisible.asStateFlow()

    val playerContentExpansionFraction = Animatable(0f)

    private val _isMiniPlayerDismissing = MutableStateFlow(false)
    val isMiniPlayerDismissing: StateFlow<Boolean> = _isMiniPlayerDismissing.asStateFlow()

    fun setMiniPlayerDismissing(dismissing: Boolean) {
        _isMiniPlayerDismissing.value = dismissing
    }

    // AI Ecosystem: States delegated to AiStateHolder for centralized management
    val showAiPlaylistSheet: StateFlow<Boolean> = aiStateHolder.showAiPlaylistSheet
    val isGeneratingAiPlaylist: StateFlow<Boolean> = aiStateHolder.isGeneratingAiPlaylist
    val aiSuccess: StateFlow<Boolean> = aiStateHolder.aiSuccess
    val aiStatus: StateFlow<String?> = aiStateHolder.aiStatus
    val aiError: StateFlow<String?> = aiStateHolder.aiError

    private val _selectedSongForInfo = MutableStateFlow<Song?>(null)
    val selectedSongForInfo: StateFlow<Song?> = _selectedSongForInfo.asStateFlow()

    // Theme & Colors - delegated to ThemeStateHolder
    val currentAlbumArtColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.currentAlbumArtColorSchemePair
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.activePlayerColorSchemePair
    val currentThemedAlbumArtUri: StateFlow<String?> = themeStateHolder.currentAlbumArtUri

    val playerThemePreference: StateFlow<String> = themePreferencesRepository.playerThemePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemePreference.ALBUM_ART
        )

    val navBarCornerRadius: StateFlow<Int> = userPreferencesRepository.navBarCornerRadiusFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 32)

    val navBarStyle: StateFlow<String> = userPreferencesRepository.navBarStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NavBarStyle.DEFAULT
        )

    val navBarCompactMode: StateFlow<Boolean> = userPreferencesRepository.navBarCompactModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val libraryNavigationMode: StateFlow<String> = userPreferencesRepository.libraryNavigationModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryNavigationMode.TAB_ROW
        )

    val carouselStyle: StateFlow<String> = userPreferencesRepository.carouselStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CarouselStyle.NO_PEEK
        )

    val hasActiveAiProviderApiKey: StateFlow<Boolean> = combine(
        aiPreferencesRepository.aiProvider,
        aiPreferencesRepository.geminiApiKey,
        aiPreferencesRepository.deepseekApiKey,
        aiPreferencesRepository.groqApiKey,
        aiPreferencesRepository.mistralApiKey,
        aiPreferencesRepository.nvidiaApiKey,
        aiPreferencesRepository.kimiApiKey,
        aiPreferencesRepository.glmApiKey,
        aiPreferencesRepository.openaiApiKey,
        aiPreferencesRepository.ollamaApiKey,
        aiPreferencesRepository.customApiKey,
        aiPreferencesRepository.openrouterApiKey
    ) { values ->
        val provider = values[0]
        val gemini = values[1]
        val deepseek = values[2]
        val groq = values[3]
        val mistral = values[4]
        val nvidia = values[5]
        val kimi = values[6]
        val glm = values[7]
        val openai = values[8]
        val ollama = values[9]
        val custom = values[10]
        val openrouter = values[11]
        when (provider) {
            "GEMINI" -> gemini.isNotBlank()
            "DEEPSEEK" -> deepseek.isNotBlank()
            "GROQ" -> groq.isNotBlank()
            "MISTRAL" -> mistral.isNotBlank()
            "NVIDIA" -> nvidia.isNotBlank()
            "KIMI" -> kimi.isNotBlank()
            "GLM" -> glm.isNotBlank()
            "OPENAI" -> openai.isNotBlank()
            "OPENROUTER" -> openrouter.isNotBlank()
            "OLLAMA" -> ollama.isNotBlank()
            "CUSTOM" -> custom.isNotBlank()
            else -> false
        }
    }.distinctUntilChanged()
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    // The 9-arg combine over per-provider API-key flows moved into
    // AiStateHolder.hasActiveAiProviderApiKey; this is a thin pass-through so
    // existing call sites in screens continue to work.
    val hasActiveAiProviderApiKey: StateFlow<Boolean> = aiStateHolder.hasActiveAiProviderApiKey

    // Moved to AiStateHolder.hasGeminiApiKey; thin pass-through.
    val hasGeminiApiKey: StateFlow<Boolean> = aiStateHolder.hasGeminiApiKey

    val fullPlayerLoadingTweaks: StateFlow<FullPlayerLoadingTweaks> = userPreferencesRepository.fullPlayerLoadingTweaksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FullPlayerLoadingTweaks()
        )

    val showPlayerFileInfo: StateFlow<Boolean> = userPreferencesRepository.showPlayerFileInfoFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /**
     * Whether tapping the background of the player sheet toggles its state.
     * When disabled, users must use gestures or buttons to expand/collapse.
     */
    val tapBackgroundClosesPlayer: StateFlow<Boolean> = userPreferencesRepository.tapBackgroundClosesPlayerFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val hapticsEnabled: StateFlow<Boolean> = userPreferencesRepository.hapticsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // Lyrics sync offset - now managed by LyricsStateHolder
    val currentSongLyricsSyncOffset: StateFlow<Int> = lyricsStateHolder.currentSongSyncOffset

    // Lyrics source preference (API_FIRST, EMBEDDED_FIRST, LOCAL_FIRST)
    val lyricsSourcePreference: StateFlow<LyricsSourcePreference> = userPreferencesRepository.lyricsSourcePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LyricsSourcePreference.EMBEDDED_FIRST
        )

    val immersiveLyricsEnabled: StateFlow<Boolean> = userPreferencesRepository.immersiveLyricsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val immersiveLyricsTimeout: StateFlow<Long> = userPreferencesRepository.immersiveLyricsTimeoutFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 4000L
        )

    private val _isImmersiveTemporarilyDisabled = MutableStateFlow(false)
    val isImmersiveTemporarilyDisabled: StateFlow<Boolean> = _isImmersiveTemporarilyDisabled.asStateFlow()

    fun setImmersiveTemporarilyDisabled(disabled: Boolean) {
        _isImmersiveTemporarilyDisabled.value = disabled
    }

    val albumArtQuality: StateFlow<AlbumArtQuality> = userPreferencesRepository.albumArtQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumArtQuality.MEDIUM)

    fun setLyricsSyncOffset(songId: String, offsetMs: Int) {
        lyricsStateHolder.setSyncOffset(songId, offsetMs)
    }

    val useSmoothCorners: StateFlow<Boolean> = userPreferencesRepository.useSmoothCornersFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val disableBlurAllOver: StateFlow<Boolean> = userPreferencesRepository.disableBlurAllOverFlow
    val reduceAnimations: StateFlow<Boolean> = userPreferencesRepository.reduceAnimationsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val animationSpeedScale: StateFlow<String> = userPreferencesRepository.animationSpeedScaleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "1.0"
        )

    val backgroundBlurEnabled: StateFlow<Boolean> = userPreferencesRepository.backgroundBlurEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )



    private val _isInitialThemePreloadComplete = MutableStateFlow(false)

    val isEndOfTrackTimerActive: StateFlow<Boolean> = sleepTimerStateHolder.isEndOfTrackTimerActive
    val activeTimerValueDisplay: StateFlow<String?> = sleepTimerStateHolder.activeTimerValueDisplay
    val activeTimerDurationMinutes: StateFlow<Int?> = sleepTimerStateHolder.activeTimerDurationMinutes
    val playCount: StateFlow<Float> = sleepTimerStateHolder.playCount

    // Lyrics search UI state - managed by LyricsStateHolder
    val lyricsSearchUiState: StateFlow<LyricsSearchUiState> = lyricsStateHolder.searchUiState




    // Toast Events
    private val _toastEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastEvents = _toastEvents.asSharedFlow()

    // MediaStore write-permission request (needed for metadata editing without MANAGE_EXTERNAL_STORAGE).
    // Owned by MetadataEditStateHolder (the only producer/consumer); re-exposed here for the UI.
    val writePermissionRequest: SharedFlow<android.content.IntentSender> = metadataEditStateHolder.writePermissionRequest

    // MediaStore delete-permission request (for deletion without MANAGE_EXTERNAL_STORAGE).
    // Owned by SongRemovalStateHolder (the only producer/consumer); re-exposed here for the UI.
    val deletePermissionRequest: SharedFlow<android.content.IntentSender> = songRemovalStateHolder.deletePermissionRequest

    private val _albumNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val albumNavigationRequests = _albumNavigationRequests.asSharedFlow()
    private val _artistNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val artistNavigationRequests = _artistNavigationRequests.asSharedFlow()
    private val _searchNavDoubleTapEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchNavDoubleTapEvents = _searchNavDoubleTapEvents.asSharedFlow()
    
    // New event for scrolling to a specific index in the songs list
    private val _scrollToIndexEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollToIndexEvent = _scrollToIndexEvent.asSharedFlow()
    
    private var albumNavigationJob: Job? = null
    private var artistNavigationJob: Job? = null

    fun requestLocateCurrentSong() {
        val currentSong = stablePlayerState.value.currentSong ?: return

        viewModelScope.launch {
            try {
                val sortOption = playerUiState.value.currentSongSortOption
                
                // Logic must match effectiveStorageFilter in LibraryStateHolder
                val baseFilter = playerUiState.value.currentStorageFilter
                val hideLocal = playerUiState.value.hideLocalMedia
                val storageFilter = if (hideLocal) {
                    com.theveloper.pixelplay.data.model.StorageFilter.ONLINE
                } else {
                    baseFilter
                }

                val sortedIds = musicRepository.getSongIdsSorted(sortOption, storageFilter)

                val unifiedId = currentSong.id.toLongOrNull()
                    ?: currentSong.contentUriString
                        .takeIf { it.isNotBlank() }
                        ?.let { musicRepository.getSongIdByContentUri(it) }

                val index = unifiedId?.let { sortedIds.indexOf(it) } ?: -1

                if (index != -1) {
                    _scrollToIndexEvent.emit(index)
                } else {
                    sendToast(context.getString(R.string.player_view_model_song_not_found_in_list))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to locate current song")
                sendToast(context.getString(R.string.player_view_model_could_not_locate_song))
            }
        }
    }

    fun showAndPlaySongFromLibrary(
        song: Song,
        queueName: String = "Library",
        isVoluntaryPlay: Boolean = true
    ) = playbackDispatchStateHolder.showAndPlaySongFromLibrary(song, queueName, isVoluntaryPlay)

    fun showAndPlaySongFromFavorites(
        song: Song,
        queueName: String = "Liked Songs",
        isVoluntaryPlay: Boolean = true
    ) = playbackDispatchStateHolder.showAndPlaySongFromFavorites(song, queueName, isVoluntaryPlay)

    suspend fun getSongsForCurrentLibrarySelection(): List<Song> =
        playbackDispatchStateHolder.getSongsForCurrentLibrarySelection()

    suspend fun getSongsForCurrentFavoriteSelection(): List<Song> =
        playbackDispatchStateHolder.getSongsForCurrentFavoriteSelection()

    val castRoutes: StateFlow<List<MediaRouter.RouteInfo>> = castStateHolder.castRoutes
    val selectedRoute: StateFlow<MediaRouter.RouteInfo?> = castStateHolder.selectedRoute
    /** Pre-mapped so UI composables don't create a new Flow on every recomposition. */
    val selectedRouteName: StateFlow<String?> = castStateHolder.selectedRoute
        .map { it?.name }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val routeVolume: StateFlow<Int> = castStateHolder.routeVolume
    val isRefreshingRoutes: StateFlow<Boolean> = castStateHolder.isRefreshingRoutes

    // Connectivity state delegated to ConnectivityStateHolder
    val isWifiEnabled: StateFlow<Boolean> = connectivityStateHolder.isWifiEnabled
    val isWifiRadioOn: StateFlow<Boolean> = connectivityStateHolder.isWifiRadioOn
    val wifiName: StateFlow<String?> = connectivityStateHolder.wifiName
    val isBluetoothEnabled: StateFlow<Boolean> = connectivityStateHolder.isBluetoothEnabled
    val bluetoothName: StateFlow<String?> = connectivityStateHolder.bluetoothName
    val bluetoothAudioDeviceStates: StateFlow<List<BluetoothAudioDeviceState>> = connectivityStateHolder.bluetoothAudioDeviceStates
    val bluetoothAudioDevices: StateFlow<List<String>> = connectivityStateHolder.bluetoothAudioDevices



    // Connectivity is now managed by ConnectivityStateHolder

    // Cast state is now managed by CastStateHolder
    private val sessionManager: SessionManager? get() = castStateHolder.sessionManager

    val isRemotePlaybackActive: StateFlow<Boolean> = castStateHolder.isRemotePlaybackActive
    val isCastConnecting: StateFlow<Boolean> = castStateHolder.isCastConnecting
    val remotePosition: StateFlow<Long> = castStateHolder.remotePosition

    private val _trackVolume = MutableStateFlow(1.0f)
    val trackVolume: StateFlow<Float> = _trackVolume.asStateFlow()

    init {
        // Initialize helper classes with our coroutine scope
        listeningStatsTracker.initialize(viewModelScope)
        dailyMixStateHolder.initialize(viewModelScope)
        lyricsStateHolder.initialize(viewModelScope, lyricsLoadCallback, playbackStateHolder.stablePlayerState)
        playbackStateHolder.initialize(
            coroutineScope = viewModelScope,
            onCastSeekBlocked = {
                sendToast(context.getString(R.string.cast_seek_unavailable_for_format))
            }
        )
        themeStateHolder.initialize(viewModelScope)
        playbackDispatchStateHolder.initialize(playbackDispatchCallbacks())
        mediaControllerSyncStateHolder.initialize(controllerSyncCallbacks())

        // On cold start, the MediaController connects asynchronously, leaving stablePlayerState.currentSong
        // null until that happens. Pre-load the palette from the persisted snapshot so the mini player
        // has the correct colors immediately on first render, before the controller is ready.
        viewModelScope.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val currentItem = if (snapshot.currentMediaId != null) {
                snapshot.items.find { it.mediaId == snapshot.currentMediaId }
            } else {
                snapshot.items.getOrNull(snapshot.currentIndex)
            } ?: return@launch

            val artworkUri = currentItem.artworkUri?.takeIf { it.isNotBlank() } ?: return@launch

            themeStateHolder.extractAndGenerateColorScheme(
                albumArtUriAsUri = artworkUri.toUri(),
                currentSongUriString = artworkUri,
                isPreload = false
            )
        }

        stablePlayerState
            .map { it.currentSong?.albumArtUriString?.takeIf { uri -> uri.isNotBlank() } }
            .distinctUntilChanged()
            // mapLatest cancels in-flight extraction for songs that are skipped over during a
            // rapid next/previous burst, so only the latest song's palette is computed. Combined
            // with the neighbor preloading below, the latest song is usually already a cache hit,
            // so the color resolves immediately instead of after a backlog of intermediate songs.
            .mapLatest { artworkUri ->
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = artworkUri?.toUri(),
                    currentSongUriString = artworkUri,
                    isPreload = false
                )
            }
            .launchIn(viewModelScope)

        // Preload neighbor album-art palettes so a skip lands on an already-cached color scheme
        // (instant memory-cache hit) and the color animation starts in step with the carousel
        // instead of trailing it. ensureAlbumColorScheme runs off-thread (IO -> Default) and
        // dedups in-flight work, so this adds no main-thread cost. Bounded to ±radius neighbors.
        combine(
            stablePlayerState.map { it.currentMediaItemIndex }.distinctUntilChanged(),
            queueFlow
        ) { index, queue -> index to queue }
            // Collapse rapid skip bursts: mapLatest cancels the pending delay whenever the index
            // changes again within the window, so we only quantize neighbor palettes once the user
            // settles on a song — never for every intermediate song flicked past. Keeps the heavy
            // Celebi work off the critical path during a burst.
            .mapLatest { pair ->
                kotlinx.coroutines.delay(220)
                pair
            }
            .onEach { (index, queue) ->
                if (index !in queue.indices) return@onEach
                val radius = 1
                for (offset in -radius..radius) {
                    if (offset == 0) continue
                    queue.getOrNull(index + offset)
                        ?.albumArtUriString
                        ?.takeIf { it.isNotBlank() }
                        ?.let { themeStateHolder.ensureAlbumColorScheme(it) }
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            lyricsStateHolder.songUpdates.collect { update: Pair<com.theveloper.pixelplay.data.model.Song, com.theveloper.pixelplay.data.model.Lyrics?> ->
                val song = update.first
                val lyrics = update.second
                // Check if this update is relevant to the currently playing song OR the selected song
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    // MERGE FIX: if song comes back empty (e.g. from reset), preserve current metadata
                    val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                    val safeSong = if (song.title.isEmpty() && currentSong != null) {
                        currentSong.copy(lyrics = "")
                    } else {
                        song
                    }
                    updateSongInStates(safeSong, lyrics)
                }
                if (_selectedSongForInfo.value?.id == song.id) {
                    val currentSelected = _selectedSongForInfo.value
                    if (song.title.isEmpty() && currentSelected != null) {
                        _selectedSongForInfo.value = currentSelected.copy(lyrics = "")
                    } else {
                        _selectedSongForInfo.value = song
                    }
                }
            }
        }

        lyricsStateHolder.messageEvents
            .onEach { msg: String -> _toastEvents.emit(msg) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId.isNullOrBlank()) flowOf(null)
                    else musicRepository.getSong(songId)
                }
                .collect { repositorySong ->
                    val currentState = playbackStateHolder.stablePlayerState.value
                    val currentSong = currentState.currentSong ?: return@collect
                    if (repositorySong == null || repositorySong.id != currentSong.id) {
                        return@collect
                    }

                    val hydratedSong = currentSong.withRepositoryHydration(repositorySong)
                    val persistedLyrics = parsePersistedLyrics(hydratedSong.lyrics)
                    val shouldApplyPersistedLyrics = currentState.lyrics == null && persistedLyrics != null
                    val shouldRefreshSong = hydratedSong != currentSong
                    val shouldReloadLyrics =
                        !shouldApplyPersistedLyrics &&
                            currentState.lyrics == null &&
                            hydratedSong.improvesLyricsLookupComparedTo(currentSong)

                    if (shouldApplyPersistedLyrics || shouldReloadLyrics) {
                        lyricsStateHolder.cancelLoading()
                    }

                    if (shouldRefreshSong || shouldApplyPersistedLyrics) {
                        updateSongInStates(
                            updatedSong = hydratedSong,
                            newLyrics = if (shouldApplyPersistedLyrics) persistedLyrics else null,
                            isLoadingLyrics = if (shouldApplyPersistedLyrics) false else null
                        )

                        if (_selectedSongForInfo.value?.id == hydratedSong.id) {
                            _selectedSongForInfo.value = hydratedSong
                        }
                    }

                    if (shouldReloadLyrics) {
                        lyricsStateHolder.loadLyricsForSong(hydratedSong, lyricsSourcePreference.value)
                    }
                }
        }
    }

    fun setTrackVolume(volume: Float) {
        mediaController?.let {
            val clampedVolume = volume.coerceIn(0f, 1f)
            it.volume = clampedVolume
            _trackVolume.value = clampedVolume
        }
    }

    fun sendToast(message: String) {
        viewModelScope.launch {
            _toastEvents.emit(message)
        }
    }

    /**
     * Bundles the ViewModel-owned state accessors that [MetadataEditStateHolder] needs to drive
     * UI updates for the metadata-edit cluster, without that holder depending on this ViewModel.
     */
    private fun metadataEditCallbacks() = MetadataEditCallbacks(
        scope = viewModelScope,
        getUiState = { _playerUiState.value },
        updateUiState = { mutation -> _playerUiState.update(mutation) },
        getSelectedSongForInfo = { _selectedSongForInfo.value },
        setSelectedSongForInfo = { _selectedSongForInfo.value = it },
        sendToast = ::sendToast,
        reloadLyricsForCurrentSong = ::loadLyricsForCurrentSong,
    )

    /**
     * Bundles the ViewModel-owned collaborators that [SongRemovalStateHolder]'s device-deletion
     * entry points need (toasts, media-controller queue cleanup, and the full library+player
     * removal routine), without that holder depending on this ViewModel.
     */
    private fun songRemovalCallbacks() = SongRemovalCallbacks(
        scope = viewModelScope,
        sendToast = ::sendToast,
        removeFromMediaControllerQueue = ::removeFromMediaControllerQueue,
        removeSong = ::removeSong,
    )

    /**
     * Bundles the ViewModel-owned collaborators that [QueueStateHolder]'s shuffle entry points
     * need (source resolution + shuffled-playback dispatch), without that holder depending on
     * this ViewModel.
     */
    private fun shufflePlaybackCallbacks() = ShufflePlaybackCallbacks(
        scope = viewModelScope,
        currentStorageFilter = { playerUiState.value.currentStorageFilter },
        albums = { libraryStateHolder.albums.value },
        artists = { libraryStateHolder.artists.value },
        playShuffled = { songs, queueName -> playSongsShuffled(songs, queueName, startAtZero = true) },
    )

    /**
     * Bundles the ViewModel collaborators that [QueueStateHolder]'s album/artist play entry
     * points need to dispatch sequential playback and reveal the player sheet.
     */
    private fun playbackSourceCallbacks() = PlaybackSourceCallbacks(
        scope = viewModelScope,
        playSongs = { songs, startSong, queueName, playlistId ->
            playSongs(songs, startSong, queueName, playlistId)
        },
        showSheet = { _isSheetVisible.value = true },
    )

    /**
     * Bundles the ViewModel-owned collaborators that [PlaybackDispatchStateHolder] needs
     * (media controller, UI state, player sheet, toasts/dialog events, the crossfade
     * transition job, listening stats, predictive back), without that holder depending on
     * this ViewModel. Supplied once via its initialize().
     */
    private fun playbackDispatchCallbacks() = PlaybackDispatchCallbacks(
        scope = viewModelScope,
        getController = { mediaController },
        getUiState = { _playerUiState.value },
        updateUiState = { mutation -> _playerUiState.update(mutation) },
        showSheet = { _isSheetVisible.value = true },
        collapseSheetState = { _sheetState.value = PlayerSheetState.COLLAPSED },
        showPlayer = ::showPlayer,
        sendToast = ::sendToast,
        emitToast = { _toastEvents.emit(it) },
        showNoInternetDialog = { _showNoInternetDialog.tryEmit(Unit) },
        ensureTelegramObservers = ::ensureTelegramPlaybackObserversStarted,
        cancelTransitionScheduler = { mediaControllerSyncStateHolder.cancelTransitionScheduler() },
        incrementSongScore = ::incrementSongScore,
        resetPredictiveBackState = ::resetPredictiveBackState,
    )

    /**
     * Bundles the ViewModel-owned collaborators that [MediaControllerSyncStateHolder] needs
     * (media controller, UI state, player sheet, track volume, toasts/dialog events, lyrics
     * loading, EOT sleep-timer cancel, manual shuffle), without that holder depending on
     * this ViewModel. Supplied once via its initialize().
     */
    private fun controllerSyncCallbacks() = ControllerSyncCallbacks(
        scope = viewModelScope,
        getController = { mediaController },
        getUiState = { _playerUiState.value },
        updateUiState = { mutation -> _playerUiState.update(mutation) },
        showSheet = { _isSheetVisible.value = true },
        setTrackVolume = { _trackVolume.value = it },
        emitToast = { _toastEvents.emit(it) },
        showNoInternetDialog = { _showNoInternetDialog.emit(Unit) },
        ensureTelegramObservers = ::ensureTelegramPlaybackObserversStarted,
        cancelSleepTimerForEot = { cancelSleepTimer(suppressDefaultToast = true) },
        resetLyricsSearchState = ::resetLyricsSearchState,
        loadLyricsForCurrentSong = ::loadLyricsForCurrentSong,
        toggleShuffle = { toggleShuffle() },
    )

    /**
     * Bundles the ViewModel-owned collaborators that [MultiSelectionStateHolder]'s batch
     * actions need (queue dispatch, player sheet, toasts, favorites snapshot), without that
     * holder depending on this ViewModel.
     */
    private fun selectionActionCallbacks() = SelectionActionCallbacks(
        scope = viewModelScope,
        playSongs = { songs, startSong, queueName -> playSongs(songs, startSong, queueName) },
        addSongToQueue = ::addSongToQueue,
        addSongNextToQueue = ::addSongNextToQueue,
        showSheet = { _isSheetVisible.value = true },
        emitToast = { _toastEvents.emit(it) },
        favoriteSongIds = { favoriteSongIds.value },
    )

    fun onSearchNavIconDoubleTapped() {
        _searchNavDoubleTapEvents.tryEmit(Unit)
    }


    // Last Library Tab Index
    val lastLibraryTabIndexFlow: StateFlow<Int> =
        userPreferencesRepository.lastLibraryTabIndexFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0 // Default to Songs tab
        )

    val libraryTabsFlow: StateFlow<List<String>> = userPreferencesRepository.libraryTabsOrderFlow
        .map { orderJson ->
            if (orderJson != null) {
                try {
                    Json.decodeFromString<List<String>>(orderJson)
                } catch (e: Exception) {
                    listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED")
                }
            } else {
                listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED"))

    private val _loadedTabs = MutableStateFlow(emptySet<String>())
    private var lastBlockedDirectories: Set<String>? = null

    private val _currentLibraryTabId = MutableStateFlow(LibraryTabId.SONGS)
    val currentLibraryTabId: StateFlow<LibraryTabId> = _currentLibraryTabId.asStateFlow()

    private val _isSortingSheetVisible = MutableStateFlow(false)
    val isSortingSheetVisible: StateFlow<Boolean> = _isSortingSheetVisible.asStateFlow()

    val availableSortOptions: StateFlow<List<SortOption>> =
        currentLibraryTabId.map { tabId ->
            Trace.beginSection("PlayerViewModel.availableSortOptionsMapping")
            try {
                when (tabId) {
                    LibraryTabId.SONGS -> SortOption.SONGS
                    LibraryTabId.ALBUMS -> SortOption.ALBUMS
                    LibraryTabId.ARTISTS -> SortOption.ARTISTS
                    LibraryTabId.PLAYLISTS -> SortOption.PLAYLISTS
                    LibraryTabId.FOLDERS -> SortOption.FOLDERS
                    LibraryTabId.LIKED -> SortOption.LIKED
                }
            } finally {
                Trace.endSection()
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SortOption.SONGS
        )

    val isSyncingStateFlow: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    private val _isInitialDataLoaded = MutableStateFlow(false)

    // Public read-only access to all songs (using _masterAllSongs declared at class level)
    // Library State - delegated to LibraryStateHolder
    val allSongsFlow: StateFlow<ImmutableList<Song>> = libraryStateHolder.allSongs

    // Genres StateFlow - delegated to LibraryStateHolder
    val genres: StateFlow<ImmutableList<Genre>> = libraryStateHolder.genres
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    val paletteRegenerationTargets: StateFlow<List<Song>> = musicRepository.getDistinctAlbumArtSongs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val homeMixPreviewSongs: StateFlow<ImmutableList<Song>> = musicRepository.getHomeMixPreviewSongs(
        limit = HOME_MIX_PREVIEW_LIMIT
    ).map { it.toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    val songCountFlow: StateFlow<Int> = musicRepository.getSongCountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val hasCloudSongsFlow: StateFlow<Boolean?> = musicRepository.getCloudSongCountFlow()
        .map<Int, Boolean?> { it > 0 }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val albumsFlow: StateFlow<ImmutableList<Album>> = libraryStateHolder.albums
    val artistsFlow: StateFlow<ImmutableList<Artist>> = libraryStateHolder.artists

    var searchQuery by mutableStateOf("")
        private set

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    private var mediaController: MediaController? = null
    private val _isMediaControllerReady = MutableStateFlow(false)
    val isMediaControllerReady: StateFlow<Boolean> = _isMediaControllerReady.asStateFlow()
    // SessionToken injected via constructor
    private val mediaControllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (command.customAction == MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE) {
                val enabled = args.getBoolean(
                    MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED,
                    false
                )
                viewModelScope.launch {
                    if (enabled != playbackStateHolder.stablePlayerState.value.isShuffleEnabled) {
                        toggleShuffle()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }
    private val mediaControllerFuture: ListenableFuture<MediaController> =
        mediaControllerFactory.create(context, sessionToken, mediaControllerListener)
    val playbackAudioMetadata: StateFlow<PlaybackAudioMetadata> =
        mediaControllerSyncStateHolder.playbackAudioMetadata

    val favoriteSongIds: StateFlow<Set<String>> = musicRepository
        .getFavoriteSongIdsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isCurrentSongFavorite: StateFlow<Boolean> = combine(
        stablePlayerState
            .map { it.currentSong }
            .distinctUntilChanged { old, new ->
                old?.id == new?.id &&
                    old?.contentUriString == new?.contentUriString &&
                    old?.path == new?.path
            }
            .flatMapLatest { song ->
                kotlinx.coroutines.flow.flow {
                    emit(resolveFavoriteSongId(song))
                }
            },
        favoriteSongIds
    ) { favoriteSongId, ids ->
        favoriteSongId?.let { ids.contains(it) } ?: false
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---------------------------------------------------------------------------
    // FullPlayerSlice — consolidates 11 independent flows into ONE subscription.
    // Previously FullPlayerContent had ~13 separate collectAsStateWithLifecycle()
    // calls. Each emission from any of them caused a recompose of the entire 2k-line
    // composable. Now a single collect + distinctUntilChanged batches all settings.
    // ---------------------------------------------------------------------------
    data class FullPlayerSlice(
        val currentSongArtists: ImmutableList<Artist> = persistentListOf(),
        val lyricsSyncOffset: Int = 0,
        val albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
        val audioMetadata: PlaybackAudioMetadata = PlaybackAudioMetadata(),
        val showPlayerFileInfo: Boolean = true,
        val immersiveLyricsEnabled: Boolean = false,
        val immersiveLyricsTimeout: Long = 4000L,
        val isImmersiveTemporarilyDisabled: Boolean = false,
        val isRemotePlaybackActive: Boolean = false,
        val selectedRouteName: String? = null,
        val isBluetoothEnabled: Boolean = false,
        val bluetoothName: String? = null
    )

    // Intermediate combine #1: 5 settings flows
    private val fullPlayerSlicePart1 = combine(
        currentSongArtists,
        currentSongLyricsSyncOffset,
        albumArtQuality,
        playbackAudioMetadata,
        showPlayerFileInfo
    ) { artists: ImmutableList<Artist>, syncOffset: Int, artQuality: AlbumArtQuality,
        audioMeta: PlaybackAudioMetadata, showFileInfo: Boolean ->
        FullPlayerSlicePart1(artists, syncOffset, artQuality, audioMeta, showFileInfo)
    }

    private data class BluetoothSlice(val enabled: Boolean, val name: String?)

    private val bluetoothSlice = combine(isBluetoothEnabled, bluetoothName) { bt, btName ->
        BluetoothSlice(bt, btName)
    }

    // Intermediate combine #2: remaining flows (≤5 for Kotlin type inference)
    private val fullPlayerSlicePart2 = combine(
        immersiveLyricsEnabled,
        immersiveLyricsTimeout,
        isImmersiveTemporarilyDisabled,
        isRemotePlaybackActive,
        combine(selectedRouteName, bluetoothSlice) { route, bt -> route to bt }
    ) { immersive: Boolean, immersiveTimeout: Long, immersiveDisabled: Boolean,
        remotePb: Boolean, routeAndBt: Pair<String?, BluetoothSlice> ->
        val (routeName, bt) = routeAndBt
        FullPlayerSlicePart2(immersive, immersiveTimeout, immersiveDisabled, remotePb, routeName, bt.enabled, bt.name)
    }

    private data class FullPlayerSlicePart1(
        val currentSongArtists: ImmutableList<Artist>,
        val lyricsSyncOffset: Int,
        val albumArtQuality: AlbumArtQuality,
        val audioMetadata: PlaybackAudioMetadata,
        val showPlayerFileInfo: Boolean
    )

    private data class FullPlayerSlicePart2(
        val immersiveLyricsEnabled: Boolean,
        val immersiveLyricsTimeout: Long,
        val isImmersiveTemporarilyDisabled: Boolean,
        val isRemotePlaybackActive: Boolean,
        val selectedRouteName: String?,
        val isBluetoothEnabled: Boolean,
        val bluetoothName: String?
    )

    val fullPlayerSlice: StateFlow<FullPlayerSlice> = combine(
        fullPlayerSlicePart1,
        fullPlayerSlicePart2
    ) { p1, p2 ->
        FullPlayerSlice(
            currentSongArtists = p1.currentSongArtists,
            lyricsSyncOffset = p1.lyricsSyncOffset,
            albumArtQuality = p1.albumArtQuality,
            audioMetadata = p1.audioMetadata,
            showPlayerFileInfo = p1.showPlayerFileInfo,
            immersiveLyricsEnabled = p2.immersiveLyricsEnabled,
            immersiveLyricsTimeout = p2.immersiveLyricsTimeout,
            isImmersiveTemporarilyDisabled = p2.isImmersiveTemporarilyDisabled,
            isRemotePlaybackActive = p2.isRemotePlaybackActive,
            selectedRouteName = p2.selectedRouteName,
            isBluetoothEnabled = p2.isBluetoothEnabled,
            bluetoothName = p2.bluetoothName
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FullPlayerSlice())

    // ---------------------------------------------------------------------------
    // PlayerConfigSlice — consolidates 7 infrequently-changing preference flows
    // into ONE subscription. Previously the player sheet had 7 separate
    // collectAsStateWithLifecycle() calls for config values, each causing a full
    // sheet recomposition when any preference changed.
    // ---------------------------------------------------------------------------
    data class PlayerConfigSlice(
        val navBarCornerRadius: Int = 32,
        val navBarStyle: String = NavBarStyle.DEFAULT,
        val carouselStyle: String = CarouselStyle.NO_PEEK,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
        val tapBackgroundClosesPlayer: Boolean = false,
        val useSmoothCorners: Boolean = true,
        val playerThemePreference: String = ThemePreference.ALBUM_ART,
        val reduceAnimations: Boolean = false,
        val animationSpeedScale: String = "1.0",
        val backgroundBlurEnabled: Boolean = true
    )

    private val playerConfigSlicePart1 = combine(
        navBarCornerRadius,
        navBarStyle,
        carouselStyle,
        fullPlayerLoadingTweaks,
        tapBackgroundClosesPlayer
    ) { radius, style, carousel, tweaks, tapClose ->
        PlayerConfigSlicePart1(radius, style, carousel, tweaks, tapClose)
    }

    private data class PlayerConfigSlicePart1(
        val navBarCornerRadius: Int,
        val navBarStyle: String,
        val carouselStyle: String,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
        val tapBackgroundClosesPlayer: Boolean
    )

    private val playerConfigSlicePart2 = combine(
        useSmoothCorners,
        playerThemePreference,
        reduceAnimations,
        animationSpeedScale,
        backgroundBlurEnabled
    ) { smoothCorners, themePref, redAnims, speedScale, blurEnabled ->
        PlayerConfigSlicePart2(smoothCorners, themePref, redAnims, speedScale, blurEnabled)
    }

    private data class PlayerConfigSlicePart2(
        val useSmoothCorners: Boolean,
        val playerThemePreference: String,
        val reduceAnimations: Boolean,
        val animationSpeedScale: String,
        val backgroundBlurEnabled: Boolean
    )

    val playerConfigSlice: StateFlow<PlayerConfigSlice> = combine(
        playerConfigSlicePart1,
        playerConfigSlicePart2
    ) { p1, p2 ->
        PlayerConfigSlice(
            navBarCornerRadius = p1.navBarCornerRadius,
            navBarStyle = p1.navBarStyle,
            carouselStyle = p1.carouselStyle,
            fullPlayerLoadingTweaks = p1.fullPlayerLoadingTweaks,
            tapBackgroundClosesPlayer = p1.tapBackgroundClosesPlayer,
            useSmoothCorners = p2.useSmoothCorners,
            playerThemePreference = p2.playerThemePreference,
            reduceAnimations = p2.reduceAnimations,
            animationSpeedScale = p2.animationSpeedScale,
            backgroundBlurEnabled = p2.backgroundBlurEnabled
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerConfigSlice())

    // Library State - delegated to LibraryStateHolder
    // Favorites now use paginated flow from LibraryStateHolder (DB-level sort & filter)
    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow

    // Daily mix state is now managed by DailyMixStateHolder
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.dailyMixSongs
    val yourMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.yourMixSongs

    fun removeFromDailyMix(songId: String) {
        dailyMixStateHolder.removeFromDailyMix(songId)
    }

    /**
     * Observes a song by ID from Room DB, combined with the latest favorite status.
     * Uses direct Room query instead of scanning the full in-memory list.
     */
    /**
     * Per-songId cache so a screen that recomposes (and re-derives its
     * `observeSong(currentSong.id)`) doesn't subscribe a brand-new Room
     * collector on every recomposition. Once a screen drops the flow,
     * SharingStarted.WhileSubscribed(5000) tears the upstream down.
     *
     * Caching is bounded — we trim the oldest entry when the map gets too
     * large to avoid an unbounded Singleton-lifetime growth on a long
     * session where the user browses many songs.
     */
    private val observedSongFlows = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, kotlinx.coroutines.flow.SharedFlow<Song?>>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, kotlinx.coroutines.flow.SharedFlow<Song?>>?
            ): Boolean = size > 64
        }
    )

    fun observeSong(songId: String?): Flow<Song?> {
        if (songId == null) return flowOf(null)
        observedSongFlows[songId]?.let { return it }
        val shared = combine(
            musicRepository.getSong(songId),
            favoriteSongIds
        ) { song, favorites ->
            song?.copy(isFavorite = favorites.contains(songId))
        }.distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null
            )
        observedSongFlows[songId] = shared
        return shared
    }



    private fun updateDailyMix() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.updateDailyMix(
            favoriteSongIdsFlow = favoriteSongIds
        )
    }

    fun shuffleAllSongs(queueName: String = "All Songs (Shuffled)") =
        queueStateHolder.shuffleAll(queueName, shufflePlaybackCallbacks())

    /**
     * Called from Quick Settings tile. Unlike shuffleAllSongs(), this always starts
     * fresh playback regardless of current state, and correctly handles the case
     * where the MediaController isn't ready yet (cold start from tile).
     *
     * Queries a bounded random sample directly from the repository so the tile does
     * not depend on the eager in-memory song cache being populated first.
     */
    fun triggerShuffleAllFromTile() = playbackDispatchStateHolder.triggerShuffleAllFromTile()
    fun triggerShuffleAllFromTile() {
        Timber.d("[TileDebug] triggerShuffleAllFromTile called. mediaController=${mediaController != null}")
        val action: () -> Unit = {
            Timber.d("[TileDebug] action() invoked")
            viewModelScope.launch {
                // Prefer the in-memory library snapshot if it's already loaded
                // (warm start, tile launched from the lock screen / shortcut
                // after the app has been used at least once). Falling back to
                // a bounded repository sample only on cold start avoids an
                // unnecessary DB round-trip in the warm path.
                val cachedSongs = libraryStateHolder.allSongs.value
                var songs: List<Song> = if (cachedSongs.isNotEmpty()) {
                    Timber.d("[TileDebug] Using ${cachedSongs.size} cached songs from library snapshot")
                    cachedSongs
                } else {
                    val sample = musicRepository.getRandomSongs(limit = 500)
                    Timber.d("[TileDebug] Repository returned ${sample.size} random songs immediately")
                    sample
                }

    fun playRandomSong() =
        queueStateHolder.playRandom(shufflePlaybackCallbacks())

    fun shuffleFavoriteSongs() =
        queueStateHolder.shuffleFavorites(shufflePlaybackCallbacks())

    fun shuffleRandomAlbum() =
        queueStateHolder.shuffleRandomAlbum(shufflePlaybackCallbacks())

    fun shuffleRandomArtist() =
        queueStateHolder.shuffleRandomArtist(shufflePlaybackCallbacks())


    private fun loadPersistedDailyMix() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.loadPersistedDailyMix()
    }

    fun forceUpdateDailyMix() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.forceUpdate(
            favoriteSongIdsFlow = favoriteSongIds
        )
    }

    private var castSongUiSyncJob: Job? = null
    private var lastCastSongUiSyncedId: String? = null

    private fun incrementSongScore(song: Song) {
        listeningStatsTracker.onVoluntarySelection(song.id)
    }

    // MIN_SESSION_LISTEN_MS, currentSession, and ListeningStatsTracker class
    // have been moved to ListeningStatsTracker.kt for better modularity


    fun updatePredictiveBackCollapseFraction(fraction: Float) {
        _predictiveBackCollapseFraction.value = fraction.coerceIn(0f, 1f)
    }

    fun updatePredictiveBackSwipeEdge(edge: Int?) {
        _predictiveBackSwipeEdge.value = edge
    }

    fun resetPredictiveBackState() {
        _predictiveBackCollapseFraction.value = 0f
        _predictiveBackSwipeEdge.value = null
    }

    fun updateQueueSheetVisibility(visible: Boolean) {
        _isQueueSheetVisible.value = visible
    }

    fun updateCastSheetVisibility(visible: Boolean) {
        _isCastSheetVisible.value = visible
    }

    // Helper to resolve stored sort keys against the allowed group
    private fun resolveSortOption(
        optionKey: String?,
        allowed: Collection<SortOption>,
        fallback: SortOption
    ): SortOption {
        return SortOption.fromStorageKey(optionKey, allowed, fallback)
    }

    private data class FolderSourceState(
        val source: FolderSource,
        val rootPath: String,
        val isSdCardAvailable: Boolean
    )

    private fun resolveFolderSourceState(preferredSource: FolderSource): FolderSourceState {
        val storages = StorageUtils.getAvailableStorages(context)
        val internalPath = storages
            .firstOrNull { it.storageType == StorageType.INTERNAL }
            ?.path
            ?.path
            ?: android.os.Environment.getExternalStorageDirectory().path
        val sdPath = StorageUtils.getSdCardStorage(context)
            ?.path
            ?.path

        val effectiveSource = if (!ENABLE_FOLDERS_SOURCE_SWITCHING) {
            FolderSource.INTERNAL
        } else if (preferredSource == FolderSource.SD_CARD && sdPath == null) {
            FolderSource.INTERNAL
        } else {
            preferredSource
        }

        val resolvedRootPath = if (effectiveSource == FolderSource.SD_CARD) sdPath!! else internalPath
        return FolderSourceState(
            source = effectiveSource,
            rootPath = resolvedRootPath,
            isSdCardAvailable = sdPath != null
        )
    }

    // Connectivity refresh delegated to ConnectivityStateHolder
    fun refreshLocalConnectionInfo(refreshBluetoothDevices: Boolean = false) {
        connectivityStateHolder.refreshLocalConnectionInfo(refreshBluetoothDevices)
    }

    init {
        Timber.tag("PlayerViewModel").i("init started.")

        // Cast initialization if already connected
        val currentSession = sessionManager?.currentCastSession
        if (currentSession != null) {
            castStateHolder.setCastPlayer(CastPlayer(currentSession, context.contentResolver))
            castStateHolder.setRemotePlaybackActive(true)
        }



        viewModelScope.launch {
            userPreferencesRepository.migrateTabOrder()
        }

        viewModelScope.launch {
            userPreferencesRepository.ensureLibrarySortDefaults()
        }

        viewModelScope.launch {
            val legacyFavoriteIds = userPreferencesRepository.favoriteSongIdsFlow.first()
            if (legacyFavoriteIds.isNotEmpty()) {
                val roomFavoriteIds = musicRepository.getFavoriteSongIdsOnce()
                if (roomFavoriteIds.isEmpty()) {
                    legacyFavoriteIds.forEach { songId ->
                        musicRepository.setFavoriteStatus(songId, true)
                    }
                }
                userPreferencesRepository.clearFavoriteSongIds()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.isFoldersPlaylistViewFlow.collect { isPlaylistView ->
                folderNavigationStateHolder.setFoldersPlaylistViewState(
                    isPlaylistView = isPlaylistView,
                    updateUiState = { mutation -> _playerUiState.update(mutation) }
                )
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.foldersSourceFlow.collect { preferredSource ->
                val resolved = resolveFolderSourceState(preferredSource)
                if (resolved.source != preferredSource) {
                    userPreferencesRepository.setFoldersSource(resolved.source)
                }

                _playerUiState.update { currentState ->
                    val sourceChanged = currentState.folderSource != resolved.source ||
                            currentState.folderSourceRootPath != resolved.rootPath
                    currentState.copy(
                        folderSource = resolved.source,
                        folderSourceRootPath = resolved.rootPath,
                        isSdCardAvailable = resolved.isSdCardAvailable,
                        currentFolderPath = if (sourceChanged) null else currentState.currentFolderPath,
                        currentFolder = if (sourceChanged) null else currentState.currentFolder
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                userPreferencesRepository.folderBackGestureNavigationFlow,
                userPreferencesRepository.isAlbumsListViewFlow,
            ) { gestureNav, albumsList ->
                Pair(gestureNav, albumsList)
            }.collect { (gestureNav, albumsList) ->
                _playerUiState.update {
                    it.copy(
                        folderBackGestureNavigationEnabled = gestureNav,
                        isAlbumsListView = albumsList,
                    )
                }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.blockedDirectoriesFlow
                .distinctUntilChanged()
                .collect { blocked ->
                    if (lastBlockedDirectories == null) {
                        lastBlockedDirectories = blocked
                        return@collect
                    }

                    if (blocked != lastBlockedDirectories) {
                        lastBlockedDirectories = blocked
                        onBlockedDirectoriesChanged()
                    }
                }
        }

        viewModelScope.launch {
            combine(libraryTabsFlow, lastLibraryTabIndexFlow) { tabs, index ->
                tabs.getOrNull(index)?.toLibraryTabIdOrNull() ?: LibraryTabId.SONGS
            }.collect { tabId ->
                _currentLibraryTabId.value = tabId
            }
        }

        // Load initial sort options ONCE at startup.
        viewModelScope.launch {
            val initialSongSort = resolveSortOption(
                userPreferencesRepository.songsSortOptionFlow.first(),
                SortOption.SONGS,
                SortOption.SongTitleAZ
            )
            val initialAlbumSort = resolveSortOption(
                userPreferencesRepository.albumsSortOptionFlow.first(),
                SortOption.ALBUMS,
                SortOption.AlbumTitleAZ
            )
            val initialArtistSort = resolveSortOption(
                userPreferencesRepository.artistsSortOptionFlow.first(),
                SortOption.ARTISTS,
                SortOption.ArtistNameAZ
            )
            val initialFolderSort = resolveSortOption(
                userPreferencesRepository.foldersSortOptionFlow.first(),
                SortOption.FOLDERS,
                SortOption.FolderNameAZ
            )
            val initialLikedSort = resolveSortOption(
                userPreferencesRepository.likedSongsSortOptionFlow.first(),
                SortOption.LIKED,
                SortOption.LikedSongDateLiked
            )

            _playerUiState.update {
                it.copy(
                    currentSongSortOption = initialSongSort,
                    currentAlbumSortOption = initialAlbumSort,
                    currentArtistSortOption = initialArtistSort,
                    currentFolderSortOption = initialFolderSort,
                    currentFavoriteSortOption = initialLikedSort
                )
            }
            // Also update the dedicated flow for favorites to ensure consistency
            // _currentFavoriteSortOptionStateFlow.value = initialLikedSort // Delegated to LibraryStateHolder

            sortSongs(initialSongSort, persist = false)
            sortAlbums(initialAlbumSort, persist = false)
            sortArtists(initialArtistSort, persist = false)
            sortFolders(initialFolderSort, persist = false)
            sortFavoriteSongs(initialLikedSort, persist = false)
        }

        viewModelScope.launch {
            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            if (isPersistent) {
                // If persistent shuffle is on, read the last used shuffle state (On/Off)
                val savedShuffle = userPreferencesRepository.isShuffleOnFlow.first()
                // Update the UI state so the shuffle button reflects the saved setting immediately
                playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = savedShuffle) }
            }
        }

        // launchColorSchemeProcessor() - Handled by ThemeStateHolder and on-demand calls

        loadPersistedDailyMix()
        loadSearchHistory()

        viewModelScope.launch {
            isSyncingStateFlow.collect { isSyncing ->
                val oldSyncingLibraryState = _playerUiState.value.isSyncingLibrary
                _playerUiState.update { it.copy(isSyncingLibrary = isSyncing) }

                if (oldSyncingLibraryState && !isSyncing) {
                    Timber.tag("PlayerViewModel").i("Sync completed. Calling resetAndLoadInitialData from isSyncingStateFlow observer.")
                    resetAndLoadInitialData("isSyncingStateFlow observer")
                }
            }
        }

        viewModelScope.launch {
            if (!isSyncingStateFlow.value && !_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                Timber.tag("PlayerViewModel").i("Initial check: Sync not active and initial data not loaded. Calling resetAndLoadInitialData.")
                resetAndLoadInitialData("Initial Check")
            }
        }

        mediaControllerFuture.addListener({
            try {
                mediaController = mediaControllerFuture.get()
                // Pass controller to PlaybackStateHolder
                playbackStateHolder.setMediaController(mediaController)
                _isMediaControllerReady.value = true


                mediaControllerSyncStateHolder.setupMediaControllerListeners(mediaController)
                mediaControllerSyncStateHolder.flushPendingRepeatMode()
                syncShuffleStateWithSession(playbackStateHolder.stablePlayerState.value.isShuffleEnabled)
                // Execute any pending action that was queued while the controller was connecting
                playbackDispatchStateHolder.flushPendingPlaybackAction()
            } catch (e: Exception) {
                _playerUiState.update { it.copy(isLoadingInitialSongs = false, isLoadingLibraryCategories = false) }
                Timber.tag("PlayerViewModel").e("Error setting up MediaController", e)
            }
        }, ContextCompat.getMainExecutor(context))


        // Start Cast discovery
        castStateHolder.startDiscovery()

        // Observe selection for HTTP server management
        viewModelScope.launch {
            castStateHolder.selectedRoute.collect { route ->
                if (route != null && !route.isDefault && route.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)) {
                    castTransferStateHolder.primeHttpServerStart()
                } else if (route?.isDefault == true) {
                    val hasActiveRemoteSession = castStateHolder.castSession.value?.remoteMediaClient != null ||
                            castStateHolder.isRemotePlaybackActive.value ||
                            castStateHolder.isCastConnecting.value
                    if (hasActiveRemoteSession) {
                        return@collect
                    }
                    context.stopService(Intent(context, MediaFileHttpServerService::class.java))
                }
            }
        }

        // Initialize connectivity monitoring (WiFi/Bluetooth)
        connectivityStateHolder.initialize()

        // Initialize sleep timer state holder.
        // Create the current-song-id flow once and share it; previously the provider built a new
        // Eagerly-started stateIn() coroutine on every end-of-track toggle, leaking one per cycle.
        val currentSongIdFlow = stablePlayerState
            .map { it.currentSong?.id }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        sleepTimerStateHolder.initialize(
            scope = viewModelScope,
            toastEmitter = { msg -> _toastEvents.emit(msg) },
            mediaControllerProvider = { mediaController },
            currentSongIdProvider = { currentSongIdFlow },
            songTitleResolver = { songId -> libraryStateHolder.allSongsById.value[songId]?.title ?: "Unknown" }
        )

        // Initialize SearchStateHolder
        searchStateHolder.initialize(viewModelScope)

        // Collect SearchStateHolder flows
        viewModelScope.launch {
            combine(
                searchStateHolder.searchResults,
                searchStateHolder.selectedSearchFilter,
                searchStateHolder.searchHistory,
            ) { results, filter, history ->
                Triple(results, filter, history)
            }.collect { (results, filter, history) ->
                _playerUiState.update {
                    it.copy(
                        searchResults = results,
                        selectedSearchFilter = filter,
                        searchHistory = history,
                    )
                }
            }
        }

        // Initialize AiStateHolder
        aiStateHolder.initialize(
            scope = viewModelScope,
            allSongsProvider = { musicRepository.getAllSongsOnce() },
            favoriteSongIdsProvider = { favoriteSongIds.value },
            toastEmitter = { msg -> viewModelScope.launch { _toastEvents.emit(msg) } },
            playSongsCallback = { songs, startSong, queueName -> playSongs(songs, startSong, queueName) },
            openPlayerSheetCallback = { _isSheetVisible.value = true }
        )

        // Collect AiStateHolder flows for playlist generation state
        viewModelScope.launch {
            combine(
                aiStateHolder.showAiPlaylistSheet,
                aiStateHolder.isGeneratingAiPlaylist,
                aiStateHolder.aiStatus,
                aiStateHolder.aiError,
            ) { show, generating, status, error ->
                AiUiSnapshot(
                    showAiPlaylistSheet = show,
                    isGeneratingAiPlaylist = generating,
                    aiStatus = status,
                    aiError = error
                )
            }.collect { snapshot ->
                _playerUiState.update {
                    it.copy(
                        showAiPlaylistSheet = snapshot.showAiPlaylistSheet,
                        isGeneratingAiPlaylist = snapshot.isGeneratingAiPlaylist,
                        aiStatus = snapshot.aiStatus,
                        aiError = snapshot.aiError
                    )
        // Mirror AiStateHolder.isGeneratingMetadata into PlayerUiState. The
        // previous 5-arg combine projected 4 fields that PlayerUiState doesn't
        // even carry — pure waste. Screens that need showAiPlaylistSheet /
        // aiStatus / aiError already consume the AiStateHolder pass-throughs
        // exposed directly on this ViewModel.
        viewModelScope.launch {
            // StateFlow already dedupes via SharingStarted — no
            // distinctUntilChanged needed.
            aiStateHolder.isGeneratingMetadata.collect { generatingMetadata ->
                _playerUiState.update {
                    it.copy(isGeneratingAiMetadata = generatingMetadata)
                }
            }
        }

        // Initialize LibraryStateHolder
        libraryStateHolder.initialize(viewModelScope)

        // Sync library folders and loading states
        viewModelScope.launch {
            combine(
                libraryStateHolder.musicFolders,
                libraryStateHolder.isLoadingLibrary,
                libraryStateHolder.isLoadingCategories,
            ) { folders, loadingLibrary, loadingCategories ->
                Triple(folders, loadingLibrary, loadingCategories)
            }.collect { (folders, loadingLibrary, loadingCategories) ->
                _playerUiState.update {
                    it.copy(
                        musicFolders = folders,
                        isLoadingInitialSongs = loadingLibrary,
                        isLoadingLibraryCategories = loadingCategories,
                    )
                }
            }
        }

        // Sync sort options and storage filter
        viewModelScope.launch {
            combine(
                libraryStateHolder.currentSongSortOption,
                libraryStateHolder.currentAlbumSortOption,
                libraryStateHolder.currentArtistSortOption,
                libraryStateHolder.currentFolderSortOption,
                libraryStateHolder.currentFavoriteSortOption,
            ) { songSort, albumSort, artistSort, folderSort, favoriteSort ->
                SortOptionsSnapshot(songSort, albumSort, artistSort, folderSort, favoriteSort)
            }.collect { snapshot ->
                _playerUiState.update {
                    it.copy(
                        currentSongSortOption = snapshot.songSort,
                        currentAlbumSortOption = snapshot.albumSort,
                        currentArtistSortOption = snapshot.artistSort,
                        currentFolderSortOption = snapshot.folderSort,
                        currentFavoriteSortOption = snapshot.favoriteSort,
                    )
                }
            }
        }
        viewModelScope.launch {
            libraryStateHolder.currentStorageFilter.collect { filter ->
                _playerUiState.update { it.copy(currentStorageFilter = filter) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.hideLocalMediaFlow.collect { hide ->
                _playerUiState.update { it.copy(hideLocalMedia = hide) }
            }
        }


        castTransferStateHolder.initialize(
            scope = viewModelScope,
            getCurrentQueue = { _playerUiState.value.currentPlaybackQueue },
            updateQueue = { newQueue ->
                _playerUiState.update {
                    it.copy(currentPlaybackQueue = newQueue.toPlaybackQueue())
                }
            },
            getSongsByIdMap = { libraryStateHolder.allSongsById.value },
            onTransferBackComplete = { startProgressUpdates() },
            onSheetVisible = { _isSheetVisible.value = true },
            onDisconnect = { disconnect() },
            onCastError = { message ->
                viewModelScope.launch { _toastEvents.emit(message) }
            },
            onSongChanged = { uriString ->
                castSongUiSyncJob?.cancel()
                castSongUiSyncJob = viewModelScope.launch {
                    delay(220)
                    val currentSongId = stablePlayerState.value.currentSong?.id
                    if (currentSongId != null && currentSongId == lastCastSongUiSyncedId) {
                        return@launch
                    }
                    loadLyricsForCurrentSong()
                    uriString?.toUri()?.let { uri ->
                        themeStateHolder.extractAndGenerateColorScheme(uri, uriString)
                    }
                    if (currentSongId != null) {
                        lastCastSongUiSyncedId = currentSongId
                    }
                }
            }
        )



        viewModelScope.launch {
            // Repeat preference is only a startup restore value.
            // Keeping a live collector here creates a feedback path:
            // player -> DataStore -> collector -> player, which can cause
            // repeat mode oscillation if a transient player state is persisted.
            val savedRepeatMode = userPreferencesRepository.repeatModeFlow.first()
            mediaControllerSyncStateHolder.applyPreferredRepeatMode(savedRepeatMode)
        }

        viewModelScope.launch {
            stablePlayerState
                .map { it.isShuffleEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    syncShuffleStateWithSession(enabled)
                }
        }

        // Auto-hide undo bar when a new song starts playing
        playlistDismissUndoStateHolder.observeUndoStateAgainstPlayback(
            scope = viewModelScope,
            currentSongIdFlow = stablePlayerState.map { it.currentSong?.id },
            getUiState = { _playerUiState.value },
            onHideDismissUndoBar = { hideDismissUndoBar() }
        )
    }

    fun onMainActivityStart() {
        Trace.beginSection("PlayerViewModel.onMainActivityStart")
        try {
            preloadThemesAndInitialData()
            checkAndUpdateDailyMixIfNeeded()
        } finally {
            Trace.endSection()
        }
    }


    private fun checkAndUpdateDailyMixIfNeeded() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.checkAndUpdateIfNeeded(
            favoriteSongIdsFlow = favoriteSongIds
        )
    }

    private fun preloadThemesAndInitialData() {
        Trace.beginSection("PlayerViewModel.preloadThemesAndInitialData")
        try {
            viewModelScope.launch {
                _isInitialThemePreloadComplete.value = false
                if (isSyncingStateFlow.value && !_isInitialDataLoaded.value) {
                    // Sync is active - defer to sync completion handler
                } else if (!_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                    resetAndLoadInitialData("preloadThemesAndInitialData")
                }
                _isInitialThemePreloadComplete.value = true
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun loadInitialLibraryDataParallel() {
        libraryStateHolder.loadSongsFromRepository()
        libraryStateHolder.loadAlbumsFromRepository()
        libraryStateHolder.loadArtistsFromRepository()
        libraryStateHolder.loadFoldersFromRepository()
    }

    private fun resetAndLoadInitialData(caller: String = "Unknown") {
        Trace.beginSection("PlayerViewModel.resetAndLoadInitialData")
        try {
            Timber.tag("PlayerViewModel").d("resetAndLoadInitialData called by $caller")
            loadInitialLibraryDataParallel()
            updateDailyMix()
        } finally {
            Trace.endSection()
        }
    }

    fun loadSongsIfNeeded() = libraryStateHolder.loadSongsIfNeeded()
    fun loadAlbumsIfNeeded() = libraryStateHolder.loadAlbumsIfNeeded()
    fun loadArtistsIfNeeded() = libraryStateHolder.loadArtistsIfNeeded()
    fun loadFoldersFromRepository() = libraryStateHolder.loadFoldersFromRepository()

    fun setStorageFilter(filter: com.theveloper.pixelplay.data.model.StorageFilter) {
        libraryStateHolder.setStorageFilter(filter)
    }

    fun setPlaylistPickerStorageFilter(filter: com.theveloper.pixelplay.data.model.StorageFilter) {
        _playlistPickerStorageFilter.value = filter
    }

    fun setHideLocalMedia(hide: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setHideLocalMedia(hide)
        }
    }

    fun toggleStorageFilter() {
        val current = _playerUiState.value.currentStorageFilter
        val next = when (current) {
            com.theveloper.pixelplay.data.model.StorageFilter.ALL -> com.theveloper.pixelplay.data.model.StorageFilter.ONLINE
            com.theveloper.pixelplay.data.model.StorageFilter.ONLINE -> com.theveloper.pixelplay.data.model.StorageFilter.OFFLINE
            com.theveloper.pixelplay.data.model.StorageFilter.OFFLINE -> com.theveloper.pixelplay.data.model.StorageFilter.ALL
        }
        setStorageFilter(next)
    }

    fun showAndPlaySong(
        song: Song,
        contextSongs: List<Song>,
        queueName: String = "Current Context",
        isVoluntaryPlay: Boolean = true,
        cancelPendingQueueBuild: Boolean = true,
        playlistId: String? = null,
        indexInQueue: Int? = null
    ) = playbackDispatchStateHolder.showAndPlaySong(
        song, contextSongs, queueName, isVoluntaryPlay, cancelPendingQueueBuild, playlistId, indexInQueue
    )

    fun showAndPlaySong(song: Song) = playbackDispatchStateHolder.showAndPlaySong(song)

    fun playAlbum(album: Album) =
        queueStateHolder.playAlbum(album, playbackSourceCallbacks())

    fun playArtist(artist: Artist) =
        queueStateHolder.playArtist(artist, playbackSourceCallbacks())

    fun removeSongFromQueue(songId: String) {
        queueUndoStateHolder.removeSongFromQueue(
            scope = viewModelScope,
            mediaController = mediaController,
            songId = songId,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) }
        )
    }

    fun undoRemoveSongFromQueue() {
        queueUndoStateHolder.undoRemoveSongFromQueue(
            mediaController = mediaController,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) }
        )
    }

    fun hideQueueItemUndoBar() {
        queueUndoStateHolder.hideQueueItemUndoBar { mutation ->
            _playerUiState.update(mutation)
        }
    }

    fun reorderQueueItem(fromIndex: Int, toIndex: Int) {
        mediaController?.let { controller ->
            if (fromIndex >= 0 && fromIndex < controller.mediaItemCount &&
                toIndex >= 0 && toIndex < controller.mediaItemCount) {
                val currentIndexBeforeMove = controller.currentMediaItemIndex
                    .takeIf { it != C.INDEX_UNSET }
                    ?: playbackStateHolder.stablePlayerState.value.currentMediaItemIndex
                val updatedCurrentIndex = moveQueueIndex(currentIndexBeforeMove, fromIndex, toIndex)

                // Move the item in the MediaController's timeline.
                // This is the source of truth for playback.
                controller.moveMediaItem(fromIndex, toIndex)

                // Optimistically mirror the committed move in UI state. The drag preview stays
                // local while dragging, so this single state update does not add per-frame work.
                _playerUiState.update { state ->
                    val updatedQueue = state.currentPlaybackQueue.moveSong(fromIndex, toIndex)
                    if (updatedQueue === state.currentPlaybackQueue) {
                        state
                    } else {
                        state.copy(currentPlaybackQueue = updatedQueue)
                    }
                }

                playbackStateHolder.updateStablePlayerState { state ->
                    if (updatedCurrentIndex == C.INDEX_UNSET ||
                        state.currentMediaItemIndex == updatedCurrentIndex
                    ) {
                        state
                    } else {
                        state.copy(currentMediaItemIndex = updatedCurrentIndex)
                    }
                }
            }
        }
    }

    fun togglePlayerSheetState(resetPredictiveState: Boolean = true) {
        _sheetState.value = if (_sheetState.value == PlayerSheetState.COLLAPSED) {
            PlayerSheetState.EXPANDED
        } else {
            PlayerSheetState.COLLAPSED
        }
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun expandPlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.EXPANDED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun collapsePlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.COLLAPSED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun triggerAlbumNavigationFromPlayer(albumId: Long) {
        if (albumId == -1L) {
            Timber.tag("AlbumDebug").d("triggerAlbumNavigationFromPlayer ignored invalid albumId=$albumId")
            return
        }

        val existingJob = albumNavigationJob
        if (existingJob != null && existingJob.isActive) {
            Timber.tag("AlbumDebug").d("triggerAlbumNavigationFromPlayer ignored; navigation already in progress for albumId=$albumId")
            return
        }

        albumNavigationJob?.cancel()
        albumNavigationJob = viewModelScope.launch {
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            Timber.tag("AlbumDebug").d("triggerAlbumNavigationFromPlayer: albumId=$albumId, songId=${currentSong?.id}, title=${currentSong?.title}")
            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _albumNavigationRequests.emit(albumId)
        }
    }

    fun triggerArtistNavigationFromPlayer(artistId: Long) {
        if (artistId == 0L) {
            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer ignored invalid artistId=$artistId")
            return
        }

        val existingJob = artistNavigationJob
        if (existingJob != null && existingJob.isActive) {
            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer ignored; navigation already in progress for artistId=$artistId")
            return
        }

        artistNavigationJob?.cancel()
        artistNavigationJob = viewModelScope.launch {
            var resolvedId = artistId
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            
            if (resolvedId == -1L && currentSong != null) {
                val idFromName = musicRepository.getArtistIdByName(currentSong.artist)
                if (idFromName != null) {
                    resolvedId = idFromName
                }
            }

            if (resolvedId == 0L || resolvedId == -1L) {
                Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer: could not resolve artistId for name=${currentSong?.artist}")
                return@launch
            }

            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer: artistId=$resolvedId, songId=${currentSong?.id}, title=${currentSong?.title}")
            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _artistNavigationRequests.emit(resolvedId)
        }
    }

    suspend fun awaitSheetState(target: PlayerSheetState) {
        sheetState.first { it == target }
    }

    suspend fun awaitPlayerCollapse(threshold: Float = 0.1f, timeoutMillis: Long = 800L) {
        withTimeoutOrNull(timeoutMillis) {
            snapshotFlow { playerContentExpansionFraction.value }
                .first { it <= threshold }
        }
    }

    // rebuildPlayerQueue functionality moved to PlaybackStateHolder (simplified)
    fun playSongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) =
        playbackDispatchStateHolder.playSongs(songsToPlay, startSong, queueName, playlistId)

    fun playSongsShuffled(
        songsToPlay: List<Song>,
        queueName: String = "None",
        playlistId: String? = null,
        startAtZero: Boolean = false
    ) = playbackDispatchStateHolder.playSongsShuffled(songsToPlay, queueName, playlistId, startAtZero)

    fun playExternalUri(uri: Uri) = playbackDispatchStateHolder.playExternalUri(uri)

    fun showPlayer() {
        if (stablePlayerState.value.currentSong != null) {
            _isSheetVisible.value = true
        }
    }

    private fun setPreparingSong(songId: String?) {
        _playerUiState.update { state ->
            if (state.preparingSongId == songId) state else state.copy(preparingSongId = songId)
        }
    }

    private fun beginPreparingSong(song: Song) {
        // Skip the "Preparing playback…" pill for local files: they reach STATE_READY
        // in milliseconds, and transient STATE_BUFFERING from audio HAL/offload init
        // (or a re-tap of an already-loaded song) can otherwise leave the pill stuck.
        // Always write the new value (null for local, song.id for remote) so a stale
        // preparingSongId from a previous remote song cannot outlive a local track switch.
        if (!isLocalPlaybackSong(song)) {
            setPreparingSong(song.id)
        } else {
            setPreparingSong(null)
        }
        viewModelScope.launch(Dispatchers.Default) {
            val albumArtUri = song.albumArtUriString
            if (albumArtUri.isNullOrBlank()) {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = null,
                    currentSongUriString = null,
                    isPreload = false
                )
            } else {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = albumArtUri.toUri(),
                    currentSongUriString = albumArtUri,
                    isPreload = false
                )
            }
        }
    }

    private fun isLocalPlaybackSong(song: Song): Boolean {
        val scheme = MediaItemBuilder.playbackUri(song).scheme?.lowercase()
        return scheme == null || scheme in LOCAL_PLAYBACK_SCHEMES
    }

    private fun clearPreparingSongIfMatching(mediaId: String? = null) {
        val preparingSongId = _playerUiState.value.preparingSongId ?: return
        if (mediaId == null || preparingSongId == mediaId) {
            setPreparingSong(null)
        }
    }

    private suspend fun preparePlaybackQueueSegments(
        songsToPlay: List<Song>,
        startSongId: String,
        playlistId: String?
    ): PreparedPlaybackQueueSegments = withContext(Dispatchers.Default) {
        val currentIndex = songsToPlay
            .indexOfFirst { it.id == startSongId }
            .takeIf { it >= 0 }
            ?: 0

        val beforeCurrent = List(currentIndex) { index ->
            buildPlaybackMediaItem(songsToPlay[index], playlistId)
        }
        val afterStartIndex = currentIndex + 1
        val afterCurrent = List((songsToPlay.size - afterStartIndex).coerceAtLeast(0)) { offset ->
            buildPlaybackMediaItem(songsToPlay[afterStartIndex + offset], playlistId)
        }

        PreparedPlaybackQueueSegments(
            beforeCurrent = beforeCurrent,
            afterCurrent = afterCurrent,
            currentIndex = currentIndex
        )
    }

    private fun attachPreparedQueueSegmentsIfCurrent(
        player: Player,
        startSongId: String,
        preparedSegments: PreparedPlaybackQueueSegments
    ) {
        if (player.currentMediaItem?.mediaId != startSongId) return
        if (player.mediaItemCount != 1) return
        if (player.getMediaItemAt(0).mediaId != startSongId) return

        if (preparedSegments.beforeCurrent.isNotEmpty()) {
            player.addMediaItems(0, preparedSegments.beforeCurrent)
        }

        if (preparedSegments.afterCurrent.isNotEmpty()) {
            player.addMediaItems(
                preparedSegments.beforeCurrent.size + 1,
                preparedSegments.afterCurrent
            )
        }

        playbackStateHolder.updateStablePlayerState {
            it.copy(currentMediaItemIndex = preparedSegments.currentIndex)
        }
    }



    private suspend fun internalPlaySongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) {
        if (songsToPlay.isEmpty()) {
            clearPreparingSongIfMatching()
            return
        }
        val effectiveStartSong = songsToPlay.firstOrNull { it.id == startSong.id } ?: songsToPlay.first()

        // Update dynamic shortcut for last played playlist
        if (playlistId != null && queueName != "None") {
            appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
        }

        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            clearPreparingSongIfMatching()
            val remoteLoaded = castTransferStateHolder.playRemoteQueue(
                songsToPlay = songsToPlay,
                startSong = effectiveStartSong,
                isShuffleEnabled = playbackStateHolder.stablePlayerState.value.isShuffleEnabled
            )

            if (!remoteLoaded) {
                Timber.tag(CAST_LOG_TAG).w(
                    "Remote queue load failed in internalPlaySongs (songId=%s queueSize=%d).",
                    effectiveStartSong.id,
                    songsToPlay.size
                )
                castSession.remoteMediaClient?.requestStatus()
                return
            }

            _playerUiState.update { it.copy(currentPlaybackQueue = songsToPlay.toPlaybackQueue(), currentQueueSourceName = queueName) }
            playbackStateHolder.updateStablePlayerState {
                it.copy(
                    currentSong = effectiveStartSong,
                    currentMediaItemIndex = 0,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
                )
            }
        } else {
            beginPreparingSong(effectiveStartSong)
            _playerUiState.update {
                it.copy(
                    currentPlaybackQueue = songsToPlay.toPlaybackQueue(),
                    currentQueueSourceName = queueName
                )
            }
            playbackStateHolder.updateStablePlayerState {
                it.copy(
                    currentSong = effectiveStartSong,
                    currentMediaItemIndex = 0,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
                )
            }
            _isSheetVisible.value = true

            val startMediaItem = buildResolvedPlaybackMediaItem(effectiveStartSong)

            val playSongsAction = {
                // Use Direct Engine Access to avoid TransactionTooLargeException on Binder
                dualPlayerEngine.cancelNext()
                val enginePlayer = dualPlayerEngine.masterPlayer

                enginePlayer.setMediaItem(startMediaItem, 0L)
                enginePlayer.prepare()
                enginePlayer.play()
                _playerUiState.update { it.copy(isLoadingInitialSongs = false) }

                if (songsToPlay.size > 1) {
                    pendingQueueSegmentsJob?.cancel()
                    pendingQueueSegmentsJob = viewModelScope.launch {
                        val preparedSegments = preparePlaybackQueueSegments(
                            songsToPlay = songsToPlay,
                            startSongId = effectiveStartSong.id,
                            playlistId = playlistId
                        )
                        withContext(Dispatchers.Main.immediate) {
                            attachPreparedQueueSegmentsIfCurrent(
                                player = dualPlayerEngine.masterPlayer,
                                startSongId = effectiveStartSong.id,
                                preparedSegments = preparedSegments
                            )
                        }
                    }
                }
            }

            // We still check for mediaController to ensure the Service is bound and active
            // even though we aren't using it for the heavy lifting anymore.
            if (mediaController == null) {
                Timber.w("MediaController not available. Queuing playback action.")
                pendingPlaybackAction = playSongsAction
            } else {
                playSongsAction()
            }
        }
    }

    private suspend fun buildResolvedPlaybackMediaItem(song: Song): MediaItem {
        val mediaItem = MediaItemBuilder.build(song)
        val originalUri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = originalUri.scheme
        if (
            scheme != "telegram" &&
            scheme != "netease" &&
            scheme != "qqmusic" &&
            scheme != "navidrome" &&
            scheme != "jellyfin" &&
            scheme != "gdrive"
        ) {
            return mediaItem
        }

        if (scheme == "telegram") {
            ensureTelegramPlaybackObserversStarted()
        }

        val resolvedUri = dualPlayerEngine.resolveCloudUri(originalUri)
        return if (resolvedUri == originalUri) {
            mediaItem
        } else {
            mediaItem.buildUpon().setUri(resolvedUri).build()
        }
    }


    private fun loadAndPlaySong(song: Song) {
        cancelPendingFullQueuePlayback()
        beginPreparingSong(song)
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = song,
                isPlaying = true,
                playWhenReady = true
            )
        }
        _isSheetVisible.value = true

        val controller = mediaController
        if (controller == null) {
            pendingPlaybackAction = {
                loadAndPlaySong(song)
            }
            return
        }

        viewModelScope.launch {
            val mediaItem = buildResolvedPlaybackMediaItem(song)
            if (controller.currentMediaItem?.mediaId == song.id) {
                if (!controller.isPlaying) controller.play()
            } else {
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

// buildMediaMetadataForSong moved to MediaItemBuilder

    private fun syncShuffleStateWithSession(enabled: Boolean) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putBoolean(MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED, enabled)
        }
        controller.sendCustomCommand(
            SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE, Bundle()),
            args
        )
    }

    fun toggleShuffle(currentSongOverride: Song? = null) {
        playbackDispatchStateHolder.cancelPendingFullQueuePlayback()
        val currentQueue = _playerUiState.value.currentPlaybackQueue.toList()
        val currentSong = currentSongOverride
            ?: playbackStateHolder.stablePlayerState.value.currentSong
            ?: mediaController?.currentMediaItem?.let { mediaControllerSyncStateHolder.resolveSongFromMediaItem(it) }
            ?: currentQueue.firstOrNull()

        playbackStateHolder.toggleShuffle(
            currentSongs = currentQueue,
            currentSong = currentSong,
            currentQueueSourceName = _playerUiState.value.currentQueueSourceName,
            updateQueueCallback = { newQueue ->
                _playerUiState.update { it.copy(currentPlaybackQueue = newQueue.toPlaybackQueue()) }
            }
        )
    }

    fun cycleRepeatMode() {
        playbackStateHolder.cycleRepeatMode()
    }

    private suspend fun setFavoriteStatusEverywhere(songId: String, isFavorite: Boolean) {
        musicRepository.setFavoriteStatus(songId, isFavorite)
    }

    fun toggleFavorite() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(currentSong) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            setFavoriteStatusEverywhere(favoriteSongId, !currentlyFavorite)
        }
    }

    fun toggleFavoriteSpecificSong(song: Song, removing: Boolean = false) {
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(song) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            val targetFavoriteState = if (removing) false else !currentlyFavorite
            setFavoriteStatusEverywhere(favoriteSongId, targetFavoriteState)
        }
    }

    private suspend fun resolveFavoriteSongId(song: Song?): String? {
        song ?: return null
        if (song.id.toLongOrNull() != null) {
            return song.id
        }

        val contentUriCandidates = buildList {
            if (song.id.startsWith(EXTERNAL_SONG_ID_PREFIX)) {
                add(song.id.removePrefix(EXTERNAL_SONG_ID_PREFIX))
            }
            add(song.contentUriString)
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in contentUriCandidates) {
            musicRepository.getSongIdByContentUri(candidate)?.let { return it.toString() }
            parseMediaStoreAudioId(candidate)?.let { return it.toString() }
        }

        val pathCandidates = buildList {
            add(song.path)
            contentUriCandidates.forEach { candidate ->
                parseFileUriPath(candidate)?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in pathCandidates) {
            musicRepository.getSongByPath(candidate)?.id?.takeIf { it.toLongOrNull() != null }?.let {
                return it
            }
        }

        return null
    }

    private fun parseMediaStoreAudioId(uriString: String): Long? {
        val normalizedUri = uriString.substringBefore('?').substringBefore('#')
        if (
            !normalizedUri.startsWith("content://media/", ignoreCase = true) ||
            !normalizedUri.contains("/audio/media/", ignoreCase = true)
        ) {
            return null
        }

        return normalizedUri.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0L }
    }

    private fun parseFileUriPath(uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "file" }?.path?.takeIf { it.isNotBlank() }
    }

    fun addSongToQueue(song: Song) = playbackDispatchStateHolder.addSongToQueue(song)

    fun addSongNextToQueue(song: Song) = playbackDispatchStateHolder.addSongNextToQueue(song)

    // =====================================================
    // Multi-Selection Batch Operations — delegated to
    // [MultiSelectionStateHolder]; the ViewModel only supplies the
    // playback/toast collaborators via [selectionActionCallbacks].
    // =====================================================

    fun playSelectedSongs(songs: List<Song>) =
        multiSelectionStateHolder.playSelectedSongs(songs, selectionActionCallbacks())

    fun addSelectedToQueue(songs: List<Song>) =
        multiSelectionStateHolder.addSelectedToQueue(songs, selectionActionCallbacks())

    fun addSelectedAsNext(songs: List<Song>) =
        multiSelectionStateHolder.addSelectedAsNext(songs, selectionActionCallbacks())

    fun playSelectedAlbums(albums: List<Album>) =
        multiSelectionStateHolder.playSelectedAlbums(albums, selectionActionCallbacks())

    fun addSelectedAlbumsAsNext(albums: List<Album>) =
        multiSelectionStateHolder.addSelectedAlbumsAsNext(albums, selectionActionCallbacks())

    fun addSelectedAlbumsToQueue(albums: List<Album>) =
        multiSelectionStateHolder.addSelectedAlbumsToQueue(albums, selectionActionCallbacks())

    fun likeSelectedSongs(songs: List<Song>) =
        multiSelectionStateHolder.likeSelectedSongs(songs, selectionActionCallbacks())

    fun unlikeSelectedSongs(songs: List<Song>) =
        multiSelectionStateHolder.unlikeSelectedSongs(songs, selectionActionCallbacks())

    fun shareSelectedAsZip(songs: List<Song>) =
        multiSelectionStateHolder.shareSelectedAsZip(songs, selectionActionCallbacks())

    suspend fun getSongsForGenres(genres: List<Genre>): List<Song> =
        multiSelectionStateHolder.getSongsForGenres(genres)

    suspend fun getSongsForAlbums(albums: List<Album>): List<Song> =
        multiSelectionStateHolder.getSongsForAlbums(albums)

    fun playSelectedGenres(genres: List<Genre>) =
        multiSelectionStateHolder.playSelectedGenres(genres, selectionActionCallbacks())

    fun addSelectedGenresToQueue(genres: List<Genre>) =
        multiSelectionStateHolder.addSelectedGenresToQueue(genres, selectionActionCallbacks())

    fun addSelectedGenresAsNext(genres: List<Genre>) =
        multiSelectionStateHolder.addSelectedGenresAsNext(genres, selectionActionCallbacks())

                resolvedSelection.songs.forEach(::addSongToQueue)

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit("Only the first $MAX_ALBUM_BATCH_SELECTION albums were added to queue")
                } else {
                    _toastEvents.emit("${resolvedSelection.albums.size} albums added to queue")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e("Error adding selected albums to queue", e)
                _toastEvents.emit("Could not add selected albums to queue")
            }
        }
    }

    fun queueAndPlaySelectedAlbums(albums: List<Album>) {
        playSelectedAlbums(albums)
    }

    /**
     * Adds all selected songs to favorites.
     * Clears selection after liking.
     */
    fun likeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var likedCount = 0
            songs.forEach { song ->
                if (!favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, true)
                    favIds.add(song.id)
                    likedCount++
                }
            }
            if (likedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(R.plurals.n_songs_added_to_favorites, likedCount, likedCount),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_all_songs_already_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Removes all selected songs from favorites.
     * Clears selection after unliking.
     */
    fun unlikeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var unlikedCount = 0
            songs.forEach { song ->
                if (favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, false)
                    favIds.remove(song.id)
                    unlikedCount++
                }
            }
            if (unlikedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(
                        R.plurals.n_songs_removed_from_favorites,
                        unlikedCount,
                        unlikedCount,
                    ),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_no_songs_were_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Shares all selected songs as a ZIP file.
     * Clears selection after initiating share.
     */
    fun shareSelectedAsZip(songs: List<Song>) {
        viewModelScope.launch {
            _toastEvents.emit(context.getString(R.string.player_creating_zip))

            val result = ZipShareHelper.createAndShareZip(context, songs)

            result.onSuccess {
                multiSelectionStateHolder.clearSelection()
            }.onFailure { error ->
                _toastEvents.emit(
                    context.getString(R.string.player_share_zip_failed_format, error.localizedMessage ?: ""),
                )
                Timber.e(error, "Failed to share zip")
            }
        }
    }

    private suspend fun resolveSelectedAlbumSongs(albums: List<Album>): ResolvedAlbumSelection {
        val albumsToProcess = albums.take(MAX_ALBUM_BATCH_SELECTION)
        val wasTrimmed = albums.size > albumsToProcess.size

        // Fetch the songs for each album in parallel — six sequential Room
        // queries on Dispatchers.IO was wall-clock-bound by the slowest disk
        // read; async/awaitAll lets them overlap.
        val songs = withContext(Dispatchers.IO) {
            coroutineScope {
                albumsToProcess
                    .map { album ->
                        async { musicRepository.getSongsForAlbum(album.id).first() }
                    }
                    .awaitAll()
                    .flatMap { albumSongs ->
                        if (albumSongs.isEmpty()) emptyList()
                        else sortSongsForAlbumSelection(albumSongs)
                    }
            }
        }

        return ResolvedAlbumSelection(
            albums = albumsToProcess,
            songs = songs,
            wasTrimmed = wasTrimmed
        )
    }

    private fun sortSongsForAlbumSelection(songs: List<Song>): List<Song> {
        return songs.sortedWith(
            compareBy<Song> { it.discNumber ?: 1 }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    /**
     * Deletes all selected songs from device with confirmation.
     * Delegated to [SongRemovalStateHolder]; the ViewModel only supplies the
     * UI-state collaborators via [songRemovalCallbacks].
     */
    fun deleteSelectedFromDevice(activity: Activity, songs: List<Song>, onComplete: () -> Unit) {
        songRemovalStateHolder.deleteSelectedFromDevice(activity, songs, onComplete, songRemovalCallbacks())
    }

    fun deleteFromDevice(activity: Activity, song: Song, onResult: (Boolean) -> Unit = {}) {
        songRemovalStateHolder.deleteFromDevice(activity, song, onResult, songRemovalCallbacks())
    }

    /** Called from the UI after the user approves or denies the MediaStore delete request. */
    fun onDeletePermissionResult(granted: Boolean) {
        songRemovalStateHolder.onDeletePermissionResult(granted, songRemovalCallbacks())
    }

    suspend fun removeSong(song: Song) {
        toggleFavoriteSpecificSong(song, true)
        playbackStateHolder.setCurrentPosition(0L)
        _playerUiState.update { currentState ->
            currentState.copy(
                currentPlaybackQueue = currentState.currentPlaybackQueue.removeSongById(song.id),
                currentQueueSourceName = ""
            )
        }
        _isSheetVisible.value = false
        songRemovalStateHolder.removeSongFromLibrary(song)
    }

    private fun removeFromMediaControllerQueue(songId: String) {
        val controller = mediaController ?: return

        try {
            // Get the current timeline and media item count
            val timeline = controller.currentTimeline
            val mediaItemCount = timeline.windowCount

            // Find the media item to remove by iterating through windows
            for (i in 0 until mediaItemCount) {
                val window = timeline.getWindow(i, Timeline.Window())
                if (window.mediaItem.mediaId == songId) {
                    // Remove the media item by index
                    controller.removeMediaItem(i)
                    break
                }
            }
        } catch (e: Exception) {
            Timber.tag("MediaController").e("Error removing from queue: ${e.message}")
        }
    }

    /**
     * Signal from the player sheet whether the slider-bearing UI is currently
     * rendered. Drives the position-ticker's resolution (250 ms vs 1 s).
     */
    fun setSliderUiMounted(mounted: Boolean) {
        playbackStateHolder.setSliderUiMounted(mounted)
    }

    fun playPause() = playbackDispatchStateHolder.playPause()

    fun seekTo(position: Long) {
        playbackStateHolder.seekTo(position)
    }

    fun nextSong() {
        playbackStateHolder.nextSong()
    }

    fun previousSong() {
        playbackStateHolder.previousSong()
    }

    private fun startProgressUpdates() {
        playbackStateHolder.startProgressUpdates()
    }

    private fun stopProgressUpdates() {
        playbackStateHolder.stopProgressUpdates()
    }

    fun observeSongs(songIds: List<String>): Flow<List<Song>> {
        return musicRepository.getSongsByIds(songIds)
    }

    fun searchSongs(query: String): Flow<List<Song>> {
        return musicRepository.searchSongs(query)
    }

    suspend fun getSongs(songIds: List<String>) : List<Song>{
        return musicRepository.getSongsByIds(songIds).first()
    }

    //Sorting
    fun sortSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortSongs(sortOption, persist)
    }

    fun sortAlbums(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortAlbums(sortOption, persist)
    }

    fun sortArtists(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortArtists(sortOption, persist)
    }

    fun sortFavoriteSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFavoriteSongs(sortOption, persist)
    }

    fun sortFolders(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFolders(sortOption, persist)
    }

    fun setFoldersPlaylistView(isPlaylistView: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFoldersPlaylistView(isPlaylistView)
            folderNavigationStateHolder.setFoldersPlaylistViewState(
                isPlaylistView = isPlaylistView,
                updateUiState = { mutation -> _playerUiState.update(mutation) }
            )
        }
    }

    fun setFoldersSource(source: FolderSource) {
        if (!ENABLE_FOLDERS_SOURCE_SWITCHING) return
        viewModelScope.launch {
            userPreferencesRepository.setFoldersSource(source)
        }
    }

    fun navigateToFolder(path: String) {
        folderNavigationStateHolder.navigateToFolder(
            path = path,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            onFolderChanged = { folderPath ->
                folderNavigationStateHolder.hydrateCurrentFolderSongsIfNeeded(
                    scope = viewModelScope,
                    folderPath = folderPath,
                    getUiState = { _playerUiState.value },
                    updateUiState = { mutation -> _playerUiState.update(mutation) },
                    requiresHydration = { song -> playbackDispatchStateHolder.songRequiresHydration(song) },
                    hydrateSongs = { songs -> playbackDispatchStateHolder.hydrateSongsIfNeeded(songs) }
                )
            }
        )
    }

    fun navigateBackFolder() {
        folderNavigationStateHolder.navigateBackFolder(
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            onFolderChanged = { folderPath ->
                folderNavigationStateHolder.hydrateCurrentFolderSongsIfNeeded(
                    scope = viewModelScope,
                    folderPath = folderPath,
                    getUiState = { _playerUiState.value },
                    updateUiState = { mutation -> _playerUiState.update(mutation) },
                    requiresHydration = { song -> playbackDispatchStateHolder.songRequiresHydration(song) },
                    hydrateSongs = { songs -> playbackDispatchStateHolder.hydrateSongsIfNeeded(songs) }
                )
            }
        )
    }

    fun setAlbumsListView(isList: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumsListView(isList)
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        searchStateHolder.updateSearchFilter(filterType)
    }

    fun loadSearchHistory(limit: Int = 15) {
        searchStateHolder.loadSearchHistory(limit)
    }

    fun onSearchQuerySubmitted(query: String) {
        searchStateHolder.onSearchQuerySubmitted(query)
    }

    fun performSearch(query: String) {
        searchStateHolder.performSearch(query)
    }

    fun deleteSearchHistoryItem(query: String) {
        searchStateHolder.deleteSearchHistoryItem(query)
    }

    fun clearSearchHistory() {
        searchStateHolder.clearSearchHistory()
    }

    // --- AI Playlist Generation ---

    // --- AI Playlist Generation ---

    fun showAiPlaylistSheet() {
        aiStateHolder.showAiPlaylistSheet()
    }

    fun dismissAiPlaylistSheet() {
        aiStateHolder.dismissAiPlaylistSheet()
    }

    fun clearAiPlaylistError() {
        aiStateHolder.clearAiPlaylistError()
    }

    fun generateAiPlaylist(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        saveAsPlaylist: Boolean = false,
        playlistName: String? = null
    ) {
        aiStateHolder.generateAiPlaylist(
            prompt = prompt,
            minLength = minLength,
            maxLength = maxLength,
            saveAsPlaylist = saveAsPlaylist,
            playlistName = playlistName
        )
    }

    fun regenerateDailyMixWithPrompt(prompt: String) {
        aiStateHolder.regenerateDailyMixWithPrompt(prompt)
    }

    fun retryLastPlaylistGeneration() {
        aiStateHolder.retryLastPlaylistGeneration()
    }

    fun clearQueueExceptCurrent() {
        mediaController?.let { controller ->
            val currentSongIndex = controller.currentMediaItemIndex
            if (currentSongIndex == C.INDEX_UNSET) return@let
            val indicesToRemove = (0 until controller.mediaItemCount)
                .filter { it != currentSongIndex }
                .sortedDescending()

            for (index in indicesToRemove) {
                controller.removeMediaItem(index)
            }
        }
    }

    fun selectRoute(route: MediaRouter.RouteInfo) {
        castRouteStateHolder.selectRoute(route) { message ->
            viewModelScope.launch { _toastEvents.emit(message) }
        }
    }

    fun disconnect(resetConnecting: Boolean = true) {
        castRouteStateHolder.disconnect(resetConnecting = resetConnecting)
    }

    fun setRouteVolume(volume: Int) {
        castRouteStateHolder.setRouteVolume(volume)
    }

    fun refreshCastRoutes() {
        castRouteStateHolder.refreshCastRoutes(viewModelScope)
    }

    fun acquireMulticastLock() {
        castStateHolder.acquireMulticastLock()
    }

    fun releaseMulticastLock() {
        castStateHolder.releaseMulticastLock()
    }



    override fun onCleared() {
        val controllerToRelease = mediaController
        mediaControllerSyncStateHolder.clearMediaControllerPlaybackListeners(controllerToRelease)
        playbackStateHolder.clearMediaController(controllerToRelease)
        controllerToRelease?.release()
        mediaController = null
        mediaControllerFuture.cancel(true)
        super.onCleared()
        playbackDispatchStateHolder.onCleared()
        castSongUiSyncJob?.cancel()
        stopProgressUpdates()
        playbackStateHolder.onCleared()
        listeningStatsTracker.onCleared()
        dailyMixStateHolder.onCleared()
        lyricsStateHolder.onCleared()
        themeStateHolder.onCleared()
        castTransferStateHolder.onCleared()
        castStateHolder.onCleared()
        searchStateHolder.onCleared()
        aiStateHolder.onCleared()
        libraryStateHolder.onCleared()
        sleepTimerStateHolder.onCleared()
        connectivityStateHolder.onCleared()
        queueUndoStateHolder.onCleared()
        playlistDismissUndoStateHolder.onCleared()
    }

    // Sleep Timer Control Functions - delegated to SleepTimerStateHolder
    fun setSleepTimer(durationMinutes: Int) {
        sleepTimerStateHolder.setSleepTimer(durationMinutes)
    }

    fun playCounted(count: Int) {
        sleepTimerStateHolder.playCounted(count)
    }

    fun cancelCountedPlay() {
        sleepTimerStateHolder.cancelCountedPlay()
    }

    fun setEndOfTrackTimer(enable: Boolean) {
        val currentSongId = stablePlayerState.value.currentSong?.id
        sleepTimerStateHolder.setEndOfTrackTimer(enable, currentSongId)
    }

    fun cancelSleepTimer(overrideToastMessage: String? = null, suppressDefaultToast: Boolean = false) {
        sleepTimerStateHolder.cancelSleepTimer(overrideToastMessage, suppressDefaultToast)
    }

    fun dismissPlaylistAndShowUndo() {
        setMiniPlayerDismissing(false)
        playlistDismissUndoStateHolder.dismissPlaylistAndShowUndo(
            scope = viewModelScope,
            currentSong = playbackStateHolder.stablePlayerState.value.currentSong,
            queue = _playerUiState.value.currentPlaybackQueue,
            queueName = _playerUiState.value.currentQueueSourceName,
            position = playbackStateHolder.currentPosition.value,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            disconnectRemoteIfNeeded = {
                val hasCastSession = castStateHolder.castSession.value != null
                val shouldDisconnectRemote = hasCastSession ||
                    castStateHolder.isRemotePlaybackActive.value ||
                    castStateHolder.isCastConnecting.value
                if (shouldDisconnectRemote) {
                    if (hasCastSession) {
                        castTransferStateHolder.skipNextTransferBack()
                    }
                    disconnect()
                }
            },
            clearPlayback = {
                mediaController?.stop()
                mediaController?.clearMediaItems()
            },
            clearStablePlaybackState = {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = null,
                        isPlaying = false,
                        playWhenReady = false,
                        totalDuration = 0L
                    )
                }
            },
            setCurrentPosition = { playbackStateHolder.setCurrentPosition(it) },
            setSheetVisible = { _isSheetVisible.value = it }
        )
    }

    fun hideDismissUndoBar() {
        playlistDismissUndoStateHolder.hideDismissUndoBar { mutation ->
            _playerUiState.update(mutation)
        }
    }

    fun undoDismissPlaylist() {
        setMiniPlayerDismissing(false)
        playlistDismissUndoStateHolder.undoDismissPlaylist(
            scope = viewModelScope,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            playSongs = { songs, startSong, queueName ->
                playSongs(songs, startSong, queueName)
            },
            seekTo = { position -> mediaController?.seekTo(position) },
            setSheetVisible = { _isSheetVisible.value = it },
            setSheetCollapsed = { _sheetState.value = PlayerSheetState.COLLAPSED },
            emitToast = { message -> _toastEvents.emit(message) }
        )
    }

    fun getSongUrisForGenre(genreId: String): Flow<List<String>> {
        return musicRepository.getMusicByGenre(genreId).map { songs ->
            songs.take(4).mapNotNull { it.albumArtUriString?.takeIf { uri -> uri.isNotBlank() } }
        }
    }

    fun saveLastLibraryTabIndex(tabIndex: Int) {
        viewModelScope.launch {
            userPreferencesRepository.saveLastLibraryTabIndex(tabIndex)
        }
    }

    fun showSortingSheet() {
        libraryTabsStateHolder.showSortingSheet(_isSortingSheetVisible)
    }

    fun hideSortingSheet() {
        libraryTabsStateHolder.hideSortingSheet(_isSortingSheetVisible)
    }

    fun onLibraryTabSelected(tabIndex: Int) {
        libraryTabsStateHolder.onLibraryTabSelected(
            tabIndex = tabIndex,
            libraryTabs = libraryTabsFlow.value,
            loadedTabs = _loadedTabs,
            currentLibraryTabId = _currentLibraryTabId,
            saveLastTabIndex = { index -> userPreferencesRepository.saveLastLibraryTabIndex(index) },
            scope = viewModelScope,
            loadSongs = { loadSongsIfNeeded() },
            loadAlbums = { loadAlbumsIfNeeded() },
            loadArtists = { loadArtistsIfNeeded() },
            loadFolders = { loadFoldersFromRepository() }
        )
    }

    fun saveLibraryTabsOrder(tabs: List<String>) {
        viewModelScope.launch {
            val orderJson = Json.encodeToString(tabs)
            userPreferencesRepository.saveLibraryTabsOrder(orderJson)
        }
    }

    fun resetLibraryTabsOrder() {
        viewModelScope.launch {
            userPreferencesRepository.resetLibraryTabsOrder()
        }
    }

    fun selectSongForInfo(song: Song) {
        _selectedSongForInfo.value = song
        viewModelScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                musicRepository.getSong(song.id).first()
            } ?: return@launch
            if (_selectedSongForInfo.value?.id == song.id) {
                _selectedSongForInfo.value = hydrated
            }
        }
    }

    private fun loadLyricsForCurrentSong() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        // Delegate to LyricsStateHolder
        lyricsStateHolder.loadLyricsForSong(currentSong, lyricsSourcePreference.value)
    }

    fun saveBatchMetadata(
        songs: List<Song>,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        composer: String?,
        genre: String?,
        lyrics: String?,
        trackNumber: Int?,
        discNumber: Int?,
        replayGainTrackGainDb: String?,
        replayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?
    ) = metadataEditStateHolder.saveBatchMetadata(
        songs, title, artist, album, albumArtist, composer, genre, lyrics,
        trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate,
        metadataEditCallbacks()
    )

    fun editSongMetadata(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate?,
    ) = metadataEditStateHolder.editSongMetadata(
        song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics,
        newTrackNumber, newDiscNumber, newReplayGainTrackGainDb, newReplayGainAlbumGainDb, coverArtUpdate,
        metadataEditCallbacks()
    )

    /** Called from the UI after the user approves or denies the MediaStore write permission. */
    fun onWritePermissionResult(granted: Boolean) =
        metadataEditStateHolder.onWritePermissionResult(granted, metadataEditCallbacks())

    fun saveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) =
        metadataEditStateHolder.saveLyricsToFile(song, lyrics, preferSynced, metadataEditCallbacks())

    suspend fun forceRegenerateAlbumPaletteForSong(song: Song): Boolean {
        val albumArtUri = song.albumArtUriString?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            // Full reset: clear all cached variants for this URI and recreate every style from scratch.
            themeStateHolder.forceRegenerateColorScheme(
                uriString = albumArtUri,
                regenerateAllStyles = true
            )
            true
        }.getOrDefault(false)
    }

    private fun updateSongInStates(
        updatedSong: Song,
        newLyrics: Lyrics? = null,
        isLoadingLyrics: Boolean? = null
    ) {
        // Update the queue first
        val currentQueue = _playerUiState.value.currentPlaybackQueue
        val updatedQueue = currentQueue.replaceSong(updatedSong)

        if (updatedQueue !== currentQueue) {
            _playerUiState.update { it.copy(currentPlaybackQueue = updatedQueue) }
        }

        // Then, update the stable state
        playbackStateHolder.updateStablePlayerState { state ->
            // Only update lyrics if they are explicitly passed
            val finalLyrics = newLyrics ?: state.lyrics
            state.copy(
                currentSong = updatedSong,
                lyrics = if (state.currentSong?.id == updatedSong.id) finalLyrics else state.lyrics,
                isLoadingLyrics = isLoadingLyrics ?: state.isLoadingLyrics
            )
        }
    }

    /**
     * Busca la letra de la canción actual en el servicio remoto.
     */
    /**
     * Busca la letra de la canción actual en el servicio remoto.
     */
    fun fetchLyricsForCurrentSong(forcePickResults: Boolean = false) {
        val currentSong = stablePlayerState.value.currentSong ?: return
        lyricsStateHolder.fetchLyricsForSong(currentSong, forcePickResults, lyricsSourcePreference.value) { resId ->
            context.getString(resId)
        }
    }

    /**
     * Manual search lyrics using query provided by user (title and artist)
     */
    fun searchLyricsManually(title: String, artist: String? = null) {
        lyricsStateHolder.searchLyricsManually(title, artist)
    }

    fun acceptLyricsSearchResultForCurrentSong(result: LyricsSearchResult) {
        val currentSong = stablePlayerState.value.currentSong ?: return
        lyricsStateHolder.acceptLyricsSearchResult(result, currentSong)
    }

    fun resetLyricsForCurrentSong() {
        val songId = stablePlayerState.value.currentSong?.id?.toLongOrNull() ?: return
        lyricsStateHolder.resetLyrics(songId)
        playbackStateHolder.updateStablePlayerState { state -> state.copy(lyrics = null) }
    }

    fun resetAllLyrics() {
        lyricsStateHolder.resetAllLyrics()
        playbackStateHolder.updateStablePlayerState { state -> state.copy(lyrics = null) }
    }

    /**
     * Procesa la letra importada de un archivo, la guarda y actualiza la UI.
     * @param songId El ID de la canción para la que se importa la letra.
     * @param lyricsContent El contenido de la letra como String.
     */
    fun importLyricsFromFile(songId: Long, validatedImport: ValidatedLyricsImport) {
        val currentSong = stablePlayerState.value.currentSong
        lyricsStateHolder.importLyricsFromFile(songId, validatedImport, currentSong)
    }

    fun translateLyricsViaAi() {
        val currentSong = stablePlayerState.value.currentSong ?: return
        lyricsStateHolder.translateLyricsViaAi(
            currentSong = currentSong,
            lyricsObj = stablePlayerState.value.lyrics,
            cb = LyricsTranslationCallbacks(
                translate = { rawLyrics -> aiStateHolder.translateLyrics(rawLyrics) },
                getString = { resId -> context.getString(resId) },
                getErrorString = { detail -> context.getString(R.string.ai_state_error_generic, detail) }
            )
        )
    }

    /**
     * Resetea el estado de la búsqueda de letras a Idle.
     */
    fun resetLyricsSearchState() {
        lyricsStateHolder.resetSearchState()
    }

    private fun onBlockedDirectoriesChanged() {
        viewModelScope.launch {
            musicRepository.invalidateCachesDependentOnAllowedDirectories()
            resetAndLoadInitialData("Blocked directories changed")
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            val controller = mediaController ?: return@launch
            val mediaItem = playbackDispatchStateHolder.buildResolvedPlaybackMediaItem(song)

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()

            _isSheetVisible.value = true
            _sheetState.value = PlayerSheetState.EXPANDED
        }
    }

    fun prepareBenchmarkPlayerFromLibrary() {
        viewModelScope.launch {
            repeat(90) { attempt ->
                val controllerReady = mediaController != null
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getAllSongsOnce()
                }
                Timber.tag("PixelPlayBenchmark").i("prepare player attempt=$attempt controllerReady=$controllerReady songs=${songs.size}")
                if (controllerReady && songs.isNotEmpty()) {
                    playSongs(songs, songs.first(), "Benchmark Player")
                    delay(700L)
                    collapsePlayerSheet()
                    Timber.tag("PixelPlayBenchmark").i("Benchmark player prepared with ${songs.first().title}")
                    return@launch
                }
                delay(500L)
            }
            Timber.tag("PixelPlayBenchmark").w("Unable to prepare benchmark player from library")
        }
    }

    fun batchEditGenre(songs: List<Song>, newGenre: String) =
        metadataEditStateHolder.batchEditGenre(songs, newGenre, metadataEditCallbacks())

    // Custom Genres Names
    val customGenres: StateFlow<Set<String>> = userPreferencesRepository.customGenresFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val customGenreIcons: StateFlow<Map<String, Int>> = userPreferencesRepository.customGenreIconsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isGenreGridView: StateFlow<Boolean> = userPreferencesRepository.isGenreGridViewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun toggleGenreViewMode() {
        viewModelScope.launch {
            userPreferencesRepository.setGenreGridView(!isGenreGridView.value)
        }
    }

    fun addCustomGenre(genre: String, iconResId: Int? = null) {
        viewModelScope.launch {
            userPreferencesRepository.addCustomGenre(genre, iconResId)
        }
    }
}

internal fun Song.withRepositoryHydration(repositorySong: Song): Song {
    if (id != repositorySong.id) return this

    val hydratedArtworkUri = when {
        repositorySong.albumArtUriString.isNullOrBlank() -> albumArtUriString
        albumArtUriString.isNullOrBlank() -> repositorySong.albumArtUriString
        areEquivalentArtworkUrisForSong(id, albumArtUriString, repositorySong.albumArtUriString) ->
            albumArtUriString
        else -> repositorySong.albumArtUriString
    }

    return repositorySong.copy(
        contentUriString = repositorySong.contentUriString.ifBlank { contentUriString },
        albumArtUriString = hydratedArtworkUri,
        duration = repositorySong.duration.takeIf { it > 0L } ?: duration,
        lyrics = repositorySong.lyrics ?: lyrics
    )
}

internal fun areEquivalentArtworkUrisForSong(
    songId: String,
    firstUri: String?,
    secondUri: String?
): Boolean {
    if (firstUri == secondUri) return true
    if (firstUri.isNullOrBlank() || secondUri.isNullOrBlank()) return false

    val targetSongId = songId.toLongOrNull() ?: return false

    fun resolveUriSongId(uri: String): Long? {
        return LocalArtworkUri.parseSongId(uri)
            ?: SharedArtworkContentProvider.parseSongId(uri)
    }

    val firstSongId = resolveUriSongId(firstUri)
    val secondSongId = resolveUriSongId(secondUri)
    return firstSongId == targetSongId && secondSongId == targetSongId
}

internal fun Song.improvesLyricsLookupComparedTo(previousSong: Song): Boolean {
    return (previousSong.lyrics.isNullOrBlank() && !lyrics.isNullOrBlank()) ||
        (previousSong.path.isBlank() && path.isNotBlank()) ||
        (previousSong.contentUriString.isBlank() && contentUriString.isNotBlank())
}

internal fun parsePersistedLyrics(rawLyrics: String?): Lyrics? {
    val normalizedLyrics = rawLyrics?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsedLyrics = LyricsUtils.parseLyrics(normalizedLyrics)
    return parsedLyrics.takeIf {
        !it.synced.isNullOrEmpty() || !it.plain.isNullOrEmpty()
    }
}
