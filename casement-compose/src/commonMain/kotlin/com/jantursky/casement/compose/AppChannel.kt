package com.jantursky.casement.compose

/**
 * The seam between the bridge and a platform renderer. The renderer calls [fromApp] with every message the app
 * posts, and installs a sink with [attach] once the page can receive messages. All calls happen on the main thread.
 */
internal class AppChannel {
    var onFromApp: (String) -> Unit = {}
    private var sink: ((String) -> Unit)? = null

    fun fromApp(text: String) = onFromApp(text)

    fun toApp(text: String) {
        sink?.invoke(text)
    }

    fun attach(sink: (String) -> Unit) {
        this.sink = sink
    }

    fun detach() {
        sink = null
    }
}
