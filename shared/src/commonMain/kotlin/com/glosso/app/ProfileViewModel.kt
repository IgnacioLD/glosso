package com.glosso.app

import androidx.lifecycle.ViewModel
import com.glosso.app.platform.PlatformContext
import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the device's phonetic profile and its dated history, republishing both
 * as the store changes. The history is what the trend and pattern views are
 * built from; the aggregate still drives the sound map.
 */
class ProfileViewModel(context: PlatformContext) : ViewModel() {

    private val store = ProfileStore.get(context)

    val profile: StateFlow<PhoneticProfileModel?> = store.profile

    val history: StateFlow<List<PhonemeObservationModel>> = store.history
}
