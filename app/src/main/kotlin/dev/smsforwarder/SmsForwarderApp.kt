package dev.smsforwarder

import android.app.Application
import dev.smsforwarder.di.AppContainer
import dev.smsforwarder.di.AppContainerImpl

class SmsForwarderApp : Application() {

    val container: AppContainer by lazy { AppContainerImpl(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile private var instance: SmsForwarderApp? = null
        fun get(): SmsForwarderApp = checkNotNull(instance) {
            "SmsForwarderApp not yet created"
        }
    }
}
