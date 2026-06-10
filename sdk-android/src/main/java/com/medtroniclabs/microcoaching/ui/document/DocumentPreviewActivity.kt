package com.medtroniclabs.microcoaching.ui.document

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.network.SourceDocumentPresignedUrlRequest
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * In-SDK preview for source documents cited from chat citation chips.
 *
 * **Default behaviour (today):** all documents open in the device's default
 * browser via `Intent.ACTION_VIEW` over the raw presigned URL. The activity
 * stays alive only long enough to fetch the presigned URL (the API call needs
 * the SDK's authed Retrofit instance), then hands off and `finish()`es.
 *
 * Rationale: Android's stock `WebView` does **not** render `application/pdf`
 * on most OEM builds — it shows a blank page — and system PDF viewer apps
 * (Drive / file managers) usually expect local file URIs, not HTTP URLs with
 * signed query strings, so MIME-typed `ACTION_VIEW` lands on a viewer that
 * can't fetch the bytes. Browsers handle HTTP PDFs natively (Chrome /
 * Firefox), so they're the reliable target.
 *
 * **Re-enabling the in-app WebView path:** flip [OPEN_DOCUMENTS_IN_WEBVIEW]
 * to `true` (single line). The full WebView code path is preserved below
 * with WebViewClient / WebChromeClient diagnostics + auto-fallback to
 * external on main-frame errors. Use this when Android WebView reliably
 * renders PDFs on the target device fleet, or when SA content shifts to
 * HTML.
 *
 * The presigned URL is short-lived (`expires_seconds`) so we deliberately do
 * NOT cache it across activity recreations — every fresh launch re-fetches.
 */
class DocumentPreviewActivity : ComponentActivity() {

    internal sealed interface PreviewState {
        object Loading : PreviewState
        data class Loaded(val url: String) : PreviewState
        object Error : PreviewState
        /** Auto-fallback fired — we've handed off to the system; keep showing the spinner briefly until finish(). */
        object HandedOffExternal : PreviewState
    }

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    private val state: StateFlow<PreviewState> = _state.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
        val titleArg = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (sourceDocumentId.isBlank()) {
            Log.w(TAG, "DocumentPreviewActivity launched with blank documentId — finishing")
            finish()
            return
        }

        val sdkLang = runCatching { MicroCoachingSDK.getInstance().language }
            .getOrDefault(com.medtroniclabs.microcoaching.Language.ENGLISH)
        val localedContext = SdkLocaleHelper.wrap(this, sdkLang)
        val fallbackTitle = titleArg.takeIf { it.isNotBlank() }
            ?: localedContext.getString(R.string.chat_source_default)

        setContent {
            SdkLocalizedTheme {
                val current by state.collectAsState()
                DocumentPreviewScreen(
                    title = fallbackTitle,
                    state = current,
                    onBack = ::finish,
                    onOpenExternal = ::openLoadedExternally,
                    onWebViewError = ::onWebViewError,
                )
            }
        }

        loadPresignedUrl(sourceDocumentId)
    }

    private fun loadPresignedUrl(sourceDocumentId: String) {
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull()
        if (sdk == null) {
            Log.w(TAG, "MicroCoachingSDK not initialised — cannot fetch presigned URL")
            _state.value = PreviewState.Error
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                sdk.apiService.getSourceDocumentPresignedUrls(
                    SourceDocumentPresignedUrlRequest(listOf(sourceDocumentId)),
                )
            }
            val response = result.getOrNull()
            if (response == null || !response.isSuccessful) {
                Log.w(
                    TAG,
                    "Presigned URL fetch failed: " +
                        "code=${response?.code()} err=${result.exceptionOrNull()?.message}",
                )
                _state.value = PreviewState.Error
                return@launch
            }
            val body = response.body()
            val entry = body?.urls?.firstOrNull { it.sourceDocumentId == sourceDocumentId }
            if (entry == null || entry.presignedUrl.isBlank()) {
                Log.w(
                    TAG,
                    "Presigned URL not in response (missing_ids=${body?.missingIds}) for $sourceDocumentId",
                )
                _state.value = PreviewState.Error
                return@launch
            }
            val url = entry.presignedUrl
            if (!isSafeDocumentUrl(url, sdk.config.backendUrl)) {
                Log.w(TAG, "Blocked unsafe document URL host/scheme for sourceDocumentId=$sourceDocumentId")
                _state.value = PreviewState.Error
                return@launch
            }
            Log.i(TAG, "Resolved presigned URL for $sourceDocumentId (expires in ${entry.expiresSeconds}s)")

            // Default route — open in the device browser. Browsers handle the
            // signed HTTP URL natively (Chrome / Firefox render PDFs inline);
            // MIME-typed handoff to a PDF viewer app fails because those apps
            // expect local file URIs, not HTTP. Flip [OPEN_DOCUMENTS_IN_WEBVIEW]
            // to keep the URL in-app via the WebView path below.
            if (!OPEN_DOCUMENTS_IN_WEBVIEW) {
                Log.i(TAG, "Opening source document in external browser (in-app WebView disabled)")
                if (openInBrowser(url)) {
                    _state.value = PreviewState.HandedOffExternal
                    finish()
                } else {
                    Log.w(TAG, "No browser on device — falling back to in-app WebView render")
                    _state.value = PreviewState.Loaded(url)
                }
                return@launch
            }

            // PDFs go straight to an external viewer — Android WebView doesn't
            // render PDF natively on most OEM builds and shows a blank page.
            // Other content types stay in-app.
            if (isPdfUrl(url)) {
                Log.i(TAG, "URL looks like a PDF — handing off to system viewer via ACTION_VIEW")
                if (openExternally(url, mime = "application/pdf")) {
                    _state.value = PreviewState.HandedOffExternal
                    finish()
                } else {
                    Log.w(TAG, "No app on device handles application/pdf — falling back to WebView")
                    _state.value = PreviewState.Loaded(url)
                }
                return@launch
            }
            _state.value = PreviewState.Loaded(url)
        }
    }

    /**
     * Hand off to a browser via plain `Intent.ACTION_VIEW` over the HTTP(S)
     * URI — no MIME type, no `CATEGORY_BROWSABLE` indirection. Browsers
     * register an intent filter for `http`/`https` schemes and handle PDFs
     * inline; PDF viewer apps that only take `content://` / `file://` URIs
     * deliberately won't match this dispatch.
     */
    private fun openInBrowser(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.startsWith("http") != true) {
            Log.w(TAG, "openInBrowser: not an http(s) URL — scheme=${uri?.scheme}")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            Log.i(TAG, "openInBrowser: dispatched to system browser")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openInBrowser: no browser registered — ${e.message}")
            false
        }
    }

    /** Toolbar "Open externally" action — only meaningful when state is Loaded. */
    private fun openLoadedExternally() {
        val url = (_state.value as? PreviewState.Loaded)?.url ?: return
        // Always hand off to the browser via plain HTTP-scheme ACTION_VIEW —
        // matches the default-path behaviour and dodges the "PDF viewer app
        // can't fetch HTTP" failure mode.
        if (openInBrowser(url)) {
            _state.value = PreviewState.HandedOffExternal
            finish()
        }
    }

    /**
     * Called from the WebView's error callbacks. Logs the failure context and
     * auto-falls-back to the browser so the user never lands on a permanently
     * blank screen.
     */
    private fun onWebViewError(stage: String, detail: String) {
        Log.w(TAG, "WebView error at $stage: $detail — falling back to browser")
        val url = (_state.value as? PreviewState.Loaded)?.url ?: return
        if (openInBrowser(url)) {
            _state.value = PreviewState.HandedOffExternal
            finish()
        }
    }

    /**
     * Fire an `ACTION_VIEW` Intent for [url]. Returns true if the system
     * dispatched it to a handler; false when no app on the device claims the
     * MIME / scheme so the caller can fall back to in-app rendering.
     */
    private fun openExternally(url: String, mime: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null) {
            Log.w(TAG, "openExternally: failed to parse URL")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) setDataAndType(uri, mime) else data = uri
            addCategory(Intent.CATEGORY_BROWSABLE)
            // CATEGORY_DEFAULT + new-task keep the handoff clean across hosts.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            Log.i(TAG, "openExternally: dispatched to system (mime=$mime)")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openExternally: no app handles mime=$mime — ${e.message}")
            // Final retry without the MIME constraint — lets browsers pick it up.
            if (mime != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Log.i(TAG, "openExternally: dispatched via untyped retry")
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun isPdfUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".pdf") ||
            lower.contains("response-content-type=application%2fpdf") ||
            lower.contains("content-type=application/pdf")
    }

    private fun isSafeDocumentUrl(rawUrl: String, backendUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        // HTTPS is accepted for any host because presigned URLs may point to
        // storage/CDN domains that differ from the API host.
        if (scheme == "https") return true
        if (scheme != "http") return false
        val backendHost = runCatching { Uri.parse(backendUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        // For cleartext HTTP, only trust the configured backend host.
        return host == backendHost
    }

    companion object {
        private const val TAG = "DocumentPreviewActivity"
        private const val EXTRA_DOCUMENT_ID = "extra_source_document_id"
        private const val EXTRA_TITLE = "extra_title"

        /**
         * Single-line toggle for source-document rendering:
         *
         *  - `false` (current default): always open in the device browser via
         *    `Intent.ACTION_VIEW`. Browsers handle HTTP-served PDFs reliably;
         *    Android `WebView` does not.
         *  - `true`: render in the in-app `WebView` with full diagnostic logging
         *    and an auto-fallback to external on main-frame errors. Use this
         *    once Android `WebView` gains reliable PDF rendering or when SA
         *    content is HTML rather than PDF.
         */
        private const val OPEN_DOCUMENTS_IN_WEBVIEW: Boolean = false

        /**
         * Launch the preview for a single source document. [title] is the chip
         * label the user just tapped — surfaced in the toolbar while the
         * presigned URL resolves so the screen isn't empty during the fetch.
         */
        fun start(context: Context, sourceDocumentId: String, title: String) {
            val intent = Intent(context, DocumentPreviewActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ID, sourceDocumentId)
                putExtra(EXTRA_TITLE, title)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun DocumentPreviewScreen(
    title: String,
    state: DocumentPreviewActivity.PreviewState,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onWebViewError: (stage: String, detail: String) -> Unit,
) {
    Scaffold(
        topBar = {
            SdkScreenHeader(
                title = title,
                onBack = onBack,
                trailing = {
                    // Toolbar escape hatch — always-on access to a real PDF
                    // viewer in case the in-app WebView rendering disappoints.
                    if (state is DocumentPreviewActivity.PreviewState.Loaded) {
                        IconButton(
                            onClick = onOpenExternal,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is DocumentPreviewActivity.PreviewState.Loaded -> DocumentWebView(
                    url = state.url,
                    onError = onWebViewError,
                )
                is DocumentPreviewActivity.PreviewState.Error -> Text(
                    text = stringResource(R.string.chat_source_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DocumentPreviewActivity.PreviewState.Loading,
                DocumentPreviewActivity.PreviewState.HandedOffExternal,
                -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DocumentWebView(
    url: String,
    onError: (stage: String, detail: String) -> Unit,
) {
    val ctx = LocalContext.current
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadingUrl: String?, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG_WV, "onPageStarted url=$loadingUrl")
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    Log.d(TAG_WV, "onPageFinished url=$loadedUrl progress=${view?.progress}")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    val mainFrame = request?.isForMainFrame == true
                    val msg = "code=${error?.errorCode} desc=${error?.description} mainFrame=$mainFrame " +
                        "url=${request?.url}"
                    Log.w(TAG_WV, "onReceivedError: $msg")
                    // Only auto-fallback on main-frame errors — sub-resource
                    // failures (favicons etc.) shouldn't tank the whole view.
                    if (mainFrame) onError("onReceivedError", msg)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val mainFrame = request?.isForMainFrame == true
                    val msg = "status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} " +
                        "mainFrame=$mainFrame url=${request?.url}"
                    Log.w(TAG_WV, "onReceivedHttpError: $msg")
                    if (mainFrame) onError("onReceivedHttpError", msg)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    Log.v(TAG_WV, "progress=$newProgress")
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(
                        TAG_WV,
                        "console[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} " +
                            "(${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})",
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }
    }
    LaunchedEffect(url) {
        Log.i(TAG_WV, "Loading URL in WebView (length=${url.length})")
        webView.loadUrl(url)
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })
}

private const val TAG_WV = "DocumentPreview/WV"
