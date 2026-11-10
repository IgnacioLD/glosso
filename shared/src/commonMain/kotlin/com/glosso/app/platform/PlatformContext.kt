package com.glosso.app.platform

/**
 * What the platform needs to reach its storage and settings: the Android
 * `Context` (a typealias, so Android callers pass theirs unchanged) and, on iOS,
 * the single [PlatformContext] instance, since iOS reaches everything through
 * process-wide singletons.
 */
expect abstract class PlatformContext
