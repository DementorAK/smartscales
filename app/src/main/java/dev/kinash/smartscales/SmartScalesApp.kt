package dev.kinash.smartscales

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kinash.smartscales.reminders.Reminders
import javax.inject.Inject

@HiltAndroidApp
class SmartScalesApp : Application() {
    @Inject
    lateinit var reminders: Reminders

    override fun onCreate() {
        super.onCreate()
        reminders.start()
    }
}
