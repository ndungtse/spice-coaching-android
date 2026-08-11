package com.medtroniclabs.microcoaching.network

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Factory for creating the SDK's OkHttpClient and Retrofit instance.
 *
 * Every request carries the two headers the backend gateway authenticates on:
 * `Authorization` with [MicroCoachingConfig.authToken] verbatim, and
 * `Client: mob`. The token is the value SPICE stored from the login response's
 * own `Authorization` header, so it is forwarded unchanged — adding a `Bearer`
 * prefix here would produce a value the gateway rejects with 401.
 *
 * Note: This is separate from SPICE's own OkHttpClient. The SDK manages its
 * own HTTP client to avoid coupling to SPICE's AppInterceptor configuration.
 */
object NetworkModule {

    private val json = com.medtroniclabs.microcoaching.util.LenientJson

    /** Value of the `Client` header identifying calls as coming from mobile. */
    const val CLIENT_HEADER_VALUE = "mob"

    /**
     * The auth headers the backend gateway requires, built from the current
     * [config] values. Shared with non-Retrofit callers (e.g. the model
     * download worker) so every SDK call to the backend is authenticated the
     * same way.
     */
    fun authHeaders(config: MicroCoachingConfig): Map<String, String> = buildMap {
        if (config.authToken.isNotBlank()) put("Authorization", config.authToken)
        put("Client", CLIENT_HEADER_VALUE)
    }

    /** Logcat tag for the auth fingerprint: `adb logcat -s CoachingAuth:D`. */
    private const val AUTH_TAG = "CoachingAuth"

    /** Last fingerprint emitted per stage, so an unchanged token logs only once. */
    private val lastLoggedFingerprint = ConcurrentHashMap<String, String>()

    /**
     * Redacted rendering of [token]: outer characters, length, and any scheme
     * prefix. A prefix is not wrong on its own — the gateway wants whatever the
     * login response's `Authorization` header held, prefix included — so it is
     * reported as a plain fact to compare against SPICE's own AppInterceptor.
     */
    private fun authFingerprint(token: String): String {
        if (token.isBlank()) return "<BLANK>"
        val scheme = token.substringBefore(' ', missingDelimiterValue = "").ifEmpty { "<none>" }
        return "${token.take(4)}…${token.takeLast(4)} len=${token.length} scheme=$scheme"
    }

    /**
     * Debug-build-only fingerprint of the auth headers actually leaving the SDK,
     * labelled with the calling [stage].
     *
     * Only [authFingerprint]'s redacted form is emitted — a live credential (and
     * the identity claims inside it) never reaches logcat. That is still enough
     * to tell apart the three ways auth breaks here: the token is blank (never
     * passed to `Builder.authToken()`), it carries a scheme prefix that does not
     * match what SPICE's own interceptor sends, or it changed mid-session.
     * Logged once per distinct value per stage, so a rotation shows up without
     * every request repeating the line.
     */
    fun logAuthFingerprint(token: String, stage: String) {
        if (!BuildConfig.DEBUG) return
        val fingerprint = authFingerprint(token)
        if (lastLoggedFingerprint.put(stage, fingerprint) == fingerprint) return
        if (token.isBlank()) {
            Log.w(AUTH_TAG, "auth[$stage]: Authorization=<BLANK> — Builder.authToken() got no token; every call will 401")
        } else {
            Log.d(AUTH_TAG, "auth[$stage]: Authorization=$fingerprint, Client=$CLIENT_HEADER_VALUE")
        }
    }

    fun createOkHttpClient(config: MicroCoachingConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectionTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder().apply {
                    // Read per request (not captured at build time) so
                    // MicroCoachingSDK.updateAuthToken() applies to the next
                    // call without rebuilding this client.
                    authHeaders(config).forEach { (name, value) -> header(name, value) }
                    if (config.tenantId.isNotBlank()) {
                        header("X-Tenant-Id", config.tenantId)
                    }
                    header("X-SDK-Version", BuildConfig.SDK_VERSION)
                    header("X-Client", "micro-coaching-android")
                }.build()
                logAuthFingerprint(config.authToken, "api")
                val response = chain.proceed(request)
                // The token fingerprint above is deduplicated, so pair a rejection
                // with the endpoint that saw it — otherwise a 401 shows up in
                // logcat with no way to tell which call and which token produced it.
                if (response.code == 401 && BuildConfig.DEBUG) {
                    Log.w(
                        AUTH_TAG,
                        "401 from ${request.method} ${request.url.encodedPath} " +
                            "(sent Authorization=${authFingerprint(config.authToken)}, Client=$CLIENT_HEADER_VALUE)",
                    )
                }
                response
            }

        if (config.enableOtelDebugLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                    redactHeader("X-Tenant-Id")
                }
            )
        }

        return builder.build()
    }

    /**
     * Builds the Retrofit API service. Pass [client] to share one OkHttpClient
     * across consumers — each client owns a dispatcher executor and connection
     * pool, so per-call clients add threads and allocation for no benefit. The
     * SDK's cached [com.medtroniclabs.microcoaching.MicroCoachingSDK.apiService]
     * is the instance the sync workers should reuse.
     */
    fun createApiService(
        config: MicroCoachingConfig,
        client: OkHttpClient = createOkHttpClient(config),
    ): CoachingApiService {
        val baseUrl = config.backendUrl.trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CoachingApiService::class.java)
    }
}
