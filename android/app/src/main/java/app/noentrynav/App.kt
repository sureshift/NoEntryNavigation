package app.noentrynav

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Writes uncaught crashes to a file so the next launch can tell the driver what happened. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(ctx).writeText("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(3000)}")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Returns the first line of the previous crash (if any) and removes the file. */
    fun take(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists()) f.readText().lineSequence().firstOrNull()?.also { f.delete() } else null
    } catch (_: Throwable) { null }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
