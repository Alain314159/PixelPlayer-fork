/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Type-safe navigation routes for Compose Navigation 2.8+.
 *
 * Status: skeleton — defines the @Serializable route objects for the new type-safe
 * Navigation Compose API. The existing Screen sealed class (Screen.kt) is kept as
 * the active navigation mechanism until all call sites are migrated.
 *
 * Migration plan (deferred — M effort):
 * 1. Replace `navController.navigate(Screen.Home.route)` with
 *    `navController.navigate(Home)` in all call sites (PlayerViewModel, UI composables).
 * 2. Replace `navArgument("albumId") { type = NavType.LongType }` in AppNavigation.kt
 *    with the auto-generated type-safe argument from the @Serializable data class.
 * 3. Replace `Screen.AlbumDetail.createRoute(123L)` with `AlbumDetail(albumId = 123L)`.
 * 4. Remove Screen.kt once all callers are migrated.
 *
 * Benefits:
 * - Compile-time argument type checking (no more stringly-typed NavType.LongType)
 * - IDE autocomplete for navController.navigate(...)
 * - Eliminates createRoute() helper functions
 * - Auto-generated argument extraction in composables
 */
package com.theveloper.pixelplay.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe route objects. Each @Serializable object or data class is a route.
 *
 * For routes without arguments, use `@Serializable object RouteName`.
 * For routes with arguments, use `@Serializable data class RouteName(val arg: Type)`.
 *
 * Navigation: `navController.navigate(RouteName)` or `navController.navigate(RouteName(arg = value))`.
 * Composable extraction: `val arg: Long = backStackEntry.toRoute<RouteName>().arg`.
 */
@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

@Serializable
object SettingsRoute

@Serializable
object AccountsRoute

@Serializable
data class SettingsCategoryRoute(val categoryId: String)

@Serializable
object PaletteStyleRoute

@Serializable
object ExperimentalRoute

@Serializable
object NavBarCrRadRoute

@Serializable
data class PlaylistDetailRoute(val playlistId: String)

@Serializable
object DailyMixRoute

@Serializable
object RecentlyPlayedRoute

@Serializable
object StatsRoute

@Serializable
data class GenreDetailRoute(val genreId: String)

@Serializable
object DJSpaceRoute

@Serializable
data class AlbumDetailRoute(val albumId: Long)

@Serializable
data class ArtistDetailRoute(val artistId: Long)

@Serializable
data class EditTransitionRoute(val playlistId: String? = null)

@Serializable
object AboutRoute

@Serializable
object OpenSourceLicensesRoute

@Serializable
object EasterEggRoute

@Serializable
object ArtistSettingsRoute

@Serializable
object DelimiterConfigRoute

@Serializable
object WordDelimiterConfigRoute

@Serializable
object EqualizerRoute

@Serializable
object DeviceCapabilitiesRoute

@Serializable
object NeteaseDashboardRoute

@Serializable
object QqMusicDashboardRoute

@Serializable
object NavidromeDashboardRoute

@Serializable
object JellyfinDashboardRoute
