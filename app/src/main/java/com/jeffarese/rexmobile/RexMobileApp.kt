package com.jeffarese.rexmobile

import android.app.Application
import com.jeffarese.rexmobile.model.AppModel
import kotlin.concurrent.thread

class RexMobileApp : Application() {
    private val modelLazy = lazy { AppModel(this) }
    val model: AppModel by modelLazy

    /** Whether [model] is running, so a broadcast can reach it without starting it. */
    val modelStarted: Boolean get() = modelLazy.isInitialized()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        com.jeffarese.rexmobile.ui.RexFonts.init(this)
        // Loading the fonts takes a moment, which the first terminal opened would otherwise spend on the main thread.
        thread(name = "fonts") { com.jeffarese.rexmobile.ui.RexFonts.prewarm() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Terminals keep their history in native memory, which only they can give back. A model not started has none.
        if (modelStarted) model.onTrimMemory(level)
    }
}
