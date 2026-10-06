package ai.deepseek.dshmobile

import android.app.Application
import ai.deepseek.dshmobile.data.DshClient
import ai.deepseek.dshmobile.data.Prefs

class DshApp : Application() {

    lateinit var prefs: Prefs
        private set

    lateinit var client: DshClient
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        client = DshClient(prefs)
    }
}
