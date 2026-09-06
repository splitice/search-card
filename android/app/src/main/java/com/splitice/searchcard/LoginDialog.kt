package com.splitice.searchcard

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.splitice.searchcard.core.*
import java.util.UUID

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginDialog(model: SearchViewModel, close: () -> Unit) {
    val origin = remember { DashboardAddress.parse(model.storage.settings.dashboard).origin }
    val nonce = remember { UUID.randomUUID().toString() }
    var exchanged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val authorize = remember {
        Uri.parse("$origin/auth/authorize").buildUpon().appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", AUTH_CALLBACK).appendQueryParameter("state", nonce).build().toString()
    }
    DisposableEffect(lifecycleOwner) {
        fun destroyLogin() {
            model.cancelLogin()
            webView?.apply { stopLoading(); loadUrl("about:blank"); onPause(); removeAllViews(); destroy() }
            webView = null
        }
        // Do not rely on recomposition after onStop: its frame clock may already be paused.
        val observer = LifecycleEventObserver { _, event ->
            // Release the WebView and request immediately, but keep the user's login intent.
            // Returning from an interruption recreates the login with a fresh state/code.
            if (event == Lifecycle.Event.ON_STOP) destroyLogin()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); destroyLogin() }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Sign in to Home Assistant", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = close) { Text("Cancel") }
                }
                Text(origin, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (exchanged) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            private fun navigate(url: String): Boolean {
                                try {
                                    val code = authorizationCode(url, nonce)
                                    if (code != null) {
                                        if (!exchanged) {
                                            exchanged = true
                                            stopLoading()
                                            model.acceptCode(code) { success ->
                                                if (success) close() else {
                                                    error = model.state.value.error ?: "Could not finish sign-in. Close and try again."
                                                    exchanged = false
                                                }
                                            }
                                        }
                                        return true
                                    }
                                    val uri = Uri.parse(url)
                                    if ("${uri.scheme}://${uri.encodedAuthority}" != origin) {
                                        error = "This login must stay on your Home Assistant server. External SSO is not supported."
                                        return true
                                    }
                                } catch (e: Exception) { error = e.message; return true }
                                return false
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                if (request.isForMainFrame) navigate(request.url.toString()) else false
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                                if (request.isForMainFrame) error = "Could not load sign-in. Check the server URL and connection."
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: android.net.http.SslError) {
                                handler.cancel(); error = "The server certificate could not be verified."
                            }
                        }
                        loadUrl(authorize)
                    }
                })
            }
        }
    }
}
