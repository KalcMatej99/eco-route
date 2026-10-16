package org.kalc.ecoroute

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

data class MapViewportInsets(
    val top: Dp = 0.dp,
    val end: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val start: Dp = 0.dp,
)

@Composable
fun RouteMap(
    payload: String,
    modifier: Modifier = Modifier,
    viewportInsets: MapViewportInsets = MapViewportInsets(),
    onRouteSelected: (String) -> Unit = {},
    drivingPayload: String = "{\"active\":false}",
) {
    val latestDrivingPayload = rememberUpdatedState(drivingPayload)
    val latestPayload = rememberUpdatedState(payload)
    val latestInsets = rememberUpdatedState(viewportInsets)
    val latestOnRouteSelected = rememberUpdatedState(onRouteSelected)

    fun WebView.renderLatest() {
        val insets = latestInsets.value
        evaluateJavascript(
            "window.setViewportInsets && window.setViewportInsets({" +
                "top:${insets.top.value},right:${insets.end.value}," +
                "bottom:${insets.bottom.value},left:${insets.start.value}})",
            null,
        )
        if (tag != latestPayload.value) {
            evaluateJavascript("window.renderRoute && window.renderRoute(${latestPayload.value})", null)
            tag = latestPayload.value
        }
        evaluateJavascript("window.updateDriving && window.updateDriving(${latestDrivingPayload.value})", null)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.userAgentString = settings.userAgentString +
                    " EcoRouteAndroid/0.9 (org.kalc.ecoroute)"
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.domStorageEnabled = false
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        "ecoRouteSelection",
                        setOf("https://appassets.androidplatform.net"),
                        object : WebViewCompat.WebMessageListener {
                            override fun onPostMessage(
                                view: WebView,
                                message: WebMessageCompat,
                                sourceOrigin: Uri,
                                isMainFrame: Boolean,
                                replyProxy: JavaScriptReplyProxy,
                            ) {
                                val routeId = message.data?.takeIf {
                                    isMainFrame && sourceOrigin.host == "appassets.androidplatform.net" &&
                                        it.length in 1..160
                                } ?: return
                                view.post { latestOnRouteSelected.value(routeId) }
                            }
                        },
                    )
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                        if (request.url.scheme == "https" && request.url.host == "tile.openstreetmap.org") {
                            return null
                        }
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == "appassets.androidplatform.net") return false
                        if (request.hasGesture() && request.url.scheme == "https") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) { view.tag = null; view.renderLatest() }
                }
                loadUrl("https://appassets.androidplatform.net/assets/map.html")
            }
        },
        update = { it.renderLatest() },
        onRelease = {
            it.stopLoading()
            it.destroy()
        },
    )
}
