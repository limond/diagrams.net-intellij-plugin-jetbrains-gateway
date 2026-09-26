package de.docs_as_co.intellij.plugin.drawio.editor

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefJSQuery
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.assertAlive
import de.docs_as_co.intellij.plugin.drawio.settings.DiagramsUiMode
import de.docs_as_co.intellij.plugin.drawio.settings.DiagramsUiTheme
import de.docs_as_co.intellij.plugin.drawio.utils.LoadableJCEFHtmlPanel
import de.docs_as_co.intellij.plugin.drawio.utils.SchemeHandlerFactory
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.net.URI

abstract class BaseDiagramsWebView(val lifetime: Lifetime, var uiTheme: String, var uiMode: String) {
    companion object {
        private val LOG = Logger.getInstance(BaseDiagramsWebView::class.java)

        val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private var myUiTheme = DiagramsUiTheme.DEFAULT.key
        private var myUiMode = DiagramsUiMode.AUTO.key

        private const val ORIGIN = "https://drawio-plugin"

        fun initializeSchemeHandler(uiTheme: String, uiMode: String) {
            // set new theme to private variable. Will be used when rendering the preview the next time
            myUiTheme = uiTheme
            myUiMode = uiMode
        }

        // Serves the bundled diagrams.net app under https://drawio-plugin. "https" is needed as the scheme because a
        // custom "drawio-plugin" scheme didn't allow the CORS requests diagrams.net needs to start (from IntelliJ
        // 2021.1 onwards; error: "CORS policy: Cross origin requests are only supported for protocol schemes...").
        private val assets = SchemeHandlerFactory { uri: URI ->
            LOG.debug("Scheme handler: Requested URI: ${uri.path}")
            if (uri.path == "/index.html") {
                LOG.debug("Scheme handler: Serving index.html with initialData")
                // Build initial data JSON manually to avoid Jackson reflection issues with local classes
                val initialDataJson = """{"baseUrl":"$ORIGIN","localStorage":null,"theme":"$myUiTheme","mode":"$myUiMode","lang":"en","showChrome":"1"}"""

                val text =
                    BaseDiagramsWebView::class.java.getResourceAsStream("/assets/index.html")?.reader()
                        ?.readText()
                if (text == null) {
                    LOG.error("Scheme handler: Failed to load /assets/index.html")
                    null
                } else {
                    val updatedText = text.replace(
                        "\$\$initialData\$\$",
                        initialDataJson
                    )
                    LOG.debug("Scheme handler: index.html loaded, size: ${updatedText.length} bytes")
                    updatedText.byteInputStream()
                }
            } else {
                LOG.debug("Scheme handler: Serving asset: /assets${uri.path}")
                val stream = BaseDiagramsWebView::class.java.getResourceAsStream("/assets" + uri.path)
                if (stream == null) {
                    LOG.error("Scheme handler: Asset not found: /assets${uri.path}")
                } else {
                    LOG.debug("Scheme handler: Asset found: /assets${uri.path}")
                }
                stream
            }
        }
    }

    private val panel = LoadableJCEFHtmlPanel()
    val component = panel.component

    fun openDevTools() {
        panel.browser.openDevtools()
    }

    private val responseMap = HashMap<String, AsyncPromise<IncomingMessage.Response>>()

    init {
        initializeSchemeHandler(uiTheme, uiMode)
        // The app is served per browser by a request handler rather than by a scheme handler factory registered on
        // CefApp: in remote development (JetBrains Gateway, Code With Me) the browser runs in the client, which only
        // sees the handlers of its own browser, so a factory registered on the host's CefApp is never asked
        // (ERR_UNKNOWN_URL_SCHEME). A per-browser handler works the same in a local IDE.
        object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean,
                isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler? {
                if (request?.url?.startsWith("$ORIGIN/") != true) return null
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler =
                        assets.create(browser, frame, "https", request)
                }
            }
        }.also { handler ->
            panel.browser.jbCefClient.addRequestHandler(handler, panel.browser.cefBrowser)
            lifetime.onTermination {
                panel.browser.jbCefClient.removeRequestHandler(handler, panel.browser.cefBrowser)
            }
        }
        val jsRequestHandler = JBCefJSQuery.create(panel.browser).also { handler ->
            handler.addHandler { request: String ->
                val message = mapper.readValue(request, IncomingMessage::class.java)

                if (message is IncomingMessage.Response) {
                    val promise = responseMap[message.requestId]!!
                    responseMap.remove(message.requestId)
                    promise.setResult(message)
                }

                if (message is IncomingMessage.Event) {
                    this.handleEvent(message)
                }

                null
            }
            lifetime.onTermination {
                handler.dispose()
                panel.dispose()
            }
        }
        object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (browser == null || frame?.isMain == false) return
                // On the browser, not the frame: in remote development CefFrame.executeJavaScript is not supported
                // (the host only logs "Not supported on backend"), CefBrowser.executeJavaScript is.
                browser.executeJavaScript(
                        "window.sendMessageToHost = function(message) {" +
                                jsRequestHandler.inject("message") +
                                "};",
                        frame?.url ?: browser.url, 0
                )
            }
        }.also { handler ->
            panel.browser.jbCefClient.addLoadHandler(handler, panel.browser.cefBrowser)
            lifetime.onTermination {
                panel.browser.jbCefClient.removeLoadHandler(handler, panel.browser.cefBrowser)
            }
        }
        panel.loadUrl("$ORIGIN/index.html")
    }

    private var requestId = 0

    open fun reload(uiTheme: String, uiMode: String, onThemeChanged: Runnable) {
        if (this.uiTheme != uiTheme || this.uiMode != uiMode) {
            this.uiTheme = uiTheme
            this.uiMode = uiMode
            initializeSchemeHandler(uiTheme, uiMode)
            this.panel.browser.cefBrowser.reloadIgnoreCache()
            onThemeChanged.run()
        }

    }
    private fun sendMessage(message: OutgoingMessage) {
        lifetime.assertAlive()

        val json = ObjectMapper().writeValueAsString(message)
        // The webview expects a json string, not an object, so that sending and receiving messages align.
        // This is why we need to encode it again.
        val jsonStr = ObjectMapper().writeValueAsString(json)
        val js = "window.processMessageFromHost($jsonStr)"
        panel.browser.cefBrowser.executeJavaScript(
                js,
                panel.browser.cefBrowser.url, 0
        )
    }

    protected fun send(message: OutgoingMessage.Request): Promise<IncomingMessage.Response> {
        message.requestId = "req-${requestId++}"
        val result = AsyncPromise<IncomingMessage.Response>()
        responseMap[message.requestId!!] = result
        sendMessage(message)
        return result
    }

    protected fun send(message: OutgoingMessage.Event) {
        sendMessage(message)
    }

    protected abstract fun handleEvent(event: IncomingMessage.Event)
}

