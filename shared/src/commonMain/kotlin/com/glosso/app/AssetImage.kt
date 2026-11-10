package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.glosso.app.api.GlossoApi

/**
 * Resolves content artwork to a Coil model and hands it to [GlossoArtwork]. A
 * downloaded pack of the current content version resolves to its local file, so
 * images keep working with no network; otherwise the versioned asset URL is
 * loaded and cached by Coil. A missing path resolves to null, which draws the
 * brand fallback instead of nothing. [AssetResolver] is the only place that
 * decides this, and the content version is a remember key so an update
 * re-resolves instead of showing the previous catalogue's artwork.
 */
@Composable
internal fun AssetImage(
    path: String?,
    modifier: Modifier = Modifier,
    api: GlossoApi = appApi,
    contentDescription: String? = null,
) {
    val version by OfflineStore.state.collectAsState()
    val model = remember(path, api, version.contentVersion) { AssetResolver.imageModel(path, api) }
    GlossoArtwork(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
