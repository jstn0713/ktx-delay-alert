package io.github.jstn0713.ktxdelay

import android.app.Application
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.service.Notifs

class KtxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRepo.init(this)
        Notifs.createChannels(this)
    }
}
