package com.cinecamera.monetization

/**
 * Feature — every premium capability that can be gated by subscription tier.
 * All feature checks throughout the app go through MonetizationEngine.hasFeature(Feature).
 */
enum class Feature {
    /** Manual camera controls — ISO, shutter speed, white balance. Available on all tiers. */
    MANUAL_BASIC,
    /** CineLog™ proprietary log color profile. Pro and Enterprise only. */
    LOG_PROFILE,
    /** 3D LUT engine with .cube file import. Pro and Enterprise only. */
    LUT_ENGINE,
    /** SRT low-latency live streaming. Enterprise only. */
    SRT,
    /** RTMP/RTMPS live streaming. Pro and Enterprise. */
    RTMP,
    /** Simultaneous multi-destination streaming. Enterprise only. */
    MULTI_STREAM,
    /** Professional audio DSP chain (noise gate, limiter). Pro and Enterprise. */
    AUDIO_PRO,
    /** Advanced streaming analytics and session reports. Pro and Enterprise. */
    ADVANCED_STATS
}

/**
 * SubscriptionTier — the three commercial tiers of CineCamera.
 */
enum class SubscriptionTier { FREE, PRO, ENTERPRISE }

/**
 * SubscriptionState — current billing state, including grace period and expiry tracking.
 */
sealed class SubscriptionState {
    object Free : SubscriptionState()
    data class Active(val tier: SubscriptionTier, val expiresAtMs: Long) : SubscriptionState()
    data class GracePeriod(val tier: SubscriptionTier, val graceEndsAtMs: Long) : SubscriptionState()
    object Expired : SubscriptionState()
    object Unknown : SubscriptionState()
}
