package com.glosso.app.platform

import platform.Foundation.NSProcessInfo

/**
 * Launch switches for testing on the simulator, read only in debug builds
 * (`IosBuild.debug`); a release build ignores them. They come from the launch
 * environment, e.g. `SIMCTL_CHILD_GLOSSO_MUTE=1 xcrun simctl launch ...`
 * (docs/IOS-DEV.md):
 * - `GLOSSO_MUTE=1`: every player in the app runs at volume 0, so a test run on
 *   the simulator never plays sound through the Mac;
 * - `GLOSSO_FAKE_WAV=<path>`: the recorder returns this 16 kHz mono WAV instead
 *   of the microphone, so a real take reaches cloud scoring with no voice;
 * - `GLOSSO_OFFLINE=1`: connectivity reports no network, to walk the offline
 *   screens on a simulator, which has no airplane mode.
 *
 * A debug build also traces each clip the audio device decodes and plays
 * ([debug]), which is how a muted run is checked.
 */
object IosDebugEnv {
    var debug: Boolean = false
        private set
    var muted: Boolean = false
        private set
    var fakeWavPath: String? = null
        private set
    var offline: Boolean = false
        private set

    fun load(debug: Boolean) {
        this.debug = debug
        if (!debug) return
        val env = NSProcessInfo.processInfo.environment
        muted = (env["GLOSSO_MUTE"] as? String) == "1"
        fakeWavPath = (env["GLOSSO_FAKE_WAV"] as? String)?.takeIf { it.isNotBlank() }
        offline = (env["GLOSSO_OFFLINE"] as? String) == "1"
    }
}
