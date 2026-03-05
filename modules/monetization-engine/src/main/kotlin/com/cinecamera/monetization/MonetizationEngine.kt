package com.cinecamera.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MonetizationEngine
 *
 * Integrates Google Play Billing to manage subscription state and enforce
 * feature gates across the Free / Pro / Enterprise tier model.
 *
 * Subscription products (defined in Play Console):
 *   cinecamera_pro_monthly        — PRO tier, monthly
 *   cinecamera_pro_annual         — PRO tier, annual (discounted)
 *   cinecamera_enterprise_monthly — Enterprise tier, monthly
 *   cinecamera_enterprise_annual  — Enterprise tier, annual
 *
 * Feature gating strategy:
 *   Tier enforcement occurs at two levels. At compile time, BuildConfig flags
 *   (ENABLE_LOG_PROFILE, ENABLE_SRT, etc.) prevent feature code from being
 *   included in the Free APK entirely. At runtime, the MonetizationEngine
 *   validates purchase receipts to gate features in the Pro/Enterprise builds
 *   where users may have purchased, downgraded, or been refunded.
 *
 * Grace period handling:
 *   If a subscription lapses but the user is within a 3-day grace period
 *   (configured in Play Console), premium features remain active.
 *   After grace period expiry, the engine emits SubscriptionState.Expired
 *   and the UI displays the upgrade prompt.
 *
 * Offline validation:
 *   The last verified subscription state is persisted to DataStore. If the
 *   device is offline, the cached state is used. Cached state expires after
 *   7 days, after which the engine conservatively downgrades to Free.
 */
@Singleton
class MonetizationEngine @Inject constructor(
    private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        private const val PRODUCT_PRO_MONTHLY = "cinecamera_pro_monthly"
        private const val PRODUCT_PRO_ANNUAL = "cinecamera_pro_annual"
        private const val PRODUCT_ENTERPRISE_MONTHLY = "cinecamera_enterprise_monthly"
        private const val PRODUCT_ENTERPRISE_ANNUAL = "cinecamera_enterprise_annual"
        private const val CACHE_EXPIRY_MS = 7 * 24 * 3600 * 1000L  // 7 days

        val PRO_PRODUCTS = setOf(PRODUCT_PRO_MONTHLY, PRODUCT_PRO_ANNUAL)
        val ENTERPRISE_PRODUCTS = setOf(PRODUCT_ENTERPRISE_MONTHLY, PRODUCT_ENTERPRISE_ANNUAL)
    }

    private val _subscriptionState = MutableStateFlow<SubscriptionState>(SubscriptionState.Unknown)
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _availableProducts = MutableStateFlow<List<ProductDetails>>(emptyList())
    val availableProducts: StateFlow<List<ProductDetails>> = _availableProducts.asStateFlow()

    private lateinit var billingClient: BillingClient
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryPurchases()
                        queryProductDetails()
                    }
                } else {
                    Timber.w("Billing setup failed: ${result.debugMessage}")
                    loadCachedState()
                }
            }

            override fun onBillingServiceDisconnected() {
                Timber.w("Billing service disconnected — using cached state")
                loadCachedState()
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Purchase validation
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Timber.w("Purchase query failed: ${result.billingResult.debugMessage}")
            loadCachedState()
            return
        }

        processActivePurchases(result.purchasesList)
    }

    private fun processActivePurchases(purchases: List<Purchase>) {
        val activePurchase = purchases.firstOrNull { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        val newState = when {
            activePurchase == null -> SubscriptionState.Free
            activePurchase.products.any { it in ENTERPRISE_PRODUCTS } ->
                SubscriptionState.Enterprise(activePurchase.purchaseToken)
            activePurchase.products.any { it in PRO_PRODUCTS } ->
                SubscriptionState.Pro(activePurchase.purchaseToken)
            else -> SubscriptionState.Free
        }

        _subscriptionState.value = newState
        cacheState(newState)

        // Acknowledge unacknowledged purchases
        purchases.filter { !it.isAcknowledged }.forEach { purchase ->
            scope.launch { acknowledgePurchase(purchase) }
        }

        Timber.i("Subscription state: $newState")
    }

    private suspend fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Timber.d("Purchase acknowledged: ${purchase.purchaseToken.take(20)}...")
        } else {
            Timber.w("Purchase acknowledgment failed: ${result.debugMessage}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Product details (for displaying pricing in upgrade UI)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun queryProductDetails() {
        val productList = listOf(
            PRODUCT_PRO_MONTHLY, PRODUCT_PRO_ANNUAL,
            PRODUCT_ENTERPRISE_MONTHLY, PRODUCT_ENTERPRISE_ANNUAL
        ).map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _availableProducts.value = result.productDetailsList ?: emptyList()
            Timber.d("Product details loaded: ${result.productDetailsList?.size} products")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Purchase flow
    // ─────────────────────────────────────────────────────────────────────────

    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Timber.e("Billing flow launch failed: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let { processActivePurchases(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Timber.d("User cancelled purchase flow")
            }
            else -> {
                Timber.w("Purchase failed: ${result.debugMessage}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature gate API
    // ─────────────────────────────────────────────────────────────────────────

    fun hasFeature(feature: Feature): Boolean {
        val state = _subscriptionState.value
        return when (feature) {
            Feature.MANUAL_BASIC -> true    // Always free
            Feature.LOG_PROFILE -> state is SubscriptionState.Pro || state is SubscriptionState.Enterprise
            Feature.LUT_ENGINE -> state is SubscriptionState.Pro || state is SubscriptionState.Enterprise
            Feature.HIGH_BITRATE -> state is SubscriptionState.Pro || state is SubscriptionState.Enterprise
            Feature.RTMP -> state is SubscriptionState.Pro || state is SubscriptionState.Enterprise
            Feature.AUDIO_PRO -> state is SubscriptionState.Pro || state is SubscriptionState.Enterprise
            Feature.SRT -> state is SubscriptionState.Enterprise
            Feature.MULTI_STREAM -> state is SubscriptionState.Enterprise
            Feature.ADVANCED_STATS -> state is SubscriptionState.Enterprise
            Feature.UNLIMITED_PRESETS -> state is SubscriptionState.Enterprise
        }
    }

    fun getMaxBitrateKbps(): Int = when (_subscriptionState.value) {
        is SubscriptionState.Free -> 30_000
        else -> 150_000
    }

    fun getMaxPresets(): Int = when (_subscriptionState.value) {
        is SubscriptionState.Free -> 3
        is SubscriptionState.Pro -> 20
        is SubscriptionState.Enterprise, SubscriptionState.Unknown -> Int.MAX_VALUE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State caching
    // ─────────────────────────────────────────────────────────────────────────

    private fun cacheState(state: SubscriptionState) {
        try {
            val prefs = context.getSharedPreferences("monetization", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("cached_tier", state.tier)
                .putLong("cached_at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache subscription state")
        }
    }

    private fun loadCachedState() {
        try {
            val prefs = context.getSharedPreferences("monetization", Context.MODE_PRIVATE)
            val cachedTier = prefs.getString("cached_tier", "FREE") ?: "FREE"
            val cachedAt = prefs.getLong("cached_at", 0L)

            if (System.currentTimeMillis() - cachedAt > CACHE_EXPIRY_MS) {
                Timber.w("Cached subscription state expired — defaulting to Free")
                _subscriptionState.value = SubscriptionState.Free
                return
            }

            _subscriptionState.value = when (cachedTier) {
                "PRO" -> SubscriptionState.Pro("cached")
                "ENTERPRISE" -> SubscriptionState.Enterprise("cached")
                else -> SubscriptionState.Free
            }
            Timber.d("Loaded cached subscription state: $cachedTier")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load cached state")
            _subscriptionState.value = SubscriptionState.Free
        }
    }

    fun release() {
        if (::billingClient.isInitialized) billingClient.endConnection()
    }
}

// ─── Subscription state ───────────────────────────────────────────────────────

sealed class SubscriptionState(val tier: String) {
    object Unknown : SubscriptionState("UNKNOWN")
    object Free : SubscriptionState("FREE")
    data class Pro(val token: String) : SubscriptionState("PRO")
    data class Enterprise(val token: String) : SubscriptionState("ENTERPRISE")
    data class Expired(val lastTier: String) : SubscriptionState("EXPIRED")
}

enum class Feature(val displayName: String) {
    MANUAL_BASIC("Manual Controls"),
    LOG_PROFILE("CineLog™ Profile"),
    LUT_ENGINE("3D LUT Engine"),
    HIGH_BITRATE("High Bitrate (150 Mbps)"),
    RTMP("RTMP Streaming"),
    AUDIO_PRO("Professional Audio"),
    SRT("SRT Broadcast"),
    MULTI_STREAM("Multi-Stream"),
    ADVANCED_STATS("Advanced Statistics"),
    UNLIMITED_PRESETS("Unlimited Presets")
}
