package com.glosso.app.product

import com.glosso.app.api.ProductConfig

/**
 * The client version policy read from `client` in the config. It only ever
 * warns: [belowMinimum] and [outdated] are meant for a soft notice, and nothing
 * in the app blocks on them. A version of 0 (the server did not say) is ignored.
 */
data class VersionNotice(
    val currentVersion: Int,
    val recommendedVersion: Int,
    val minimumSupportedVersion: Int,
) {
    /** A newer build is recommended, but this one keeps working. */
    val outdated: Boolean get() = recommendedVersion > 0 && currentVersion < recommendedVersion

    /** Older than the minimum the server supports; still only a warning. */
    val belowMinimum: Boolean get() = minimumSupportedVersion > 0 && currentVersion < minimumSupportedVersion

    /** True when there is anything worth telling the learner. */
    val visible: Boolean get() = outdated || belowMinimum

    companion object {
        val None = VersionNotice(0, 0, 0)

        /** The version policy from [config], for a soft, non-blocking notice. */
        fun from(config: ProductConfig, currentVersion: Int): VersionNotice =
            VersionNotice(
                currentVersion = currentVersion,
                recommendedVersion = config.client.recommendedVersion,
                minimumSupportedVersion = config.client.minimumSupportedVersion,
            )
    }
}
