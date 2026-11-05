package org.folyan.app  // поменяйте на свой package

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

// Запускает встроенный Python один раз при старте приложения.
// Укажите в AndroidManifest.xml: <application android:name=".FolyanApplication" ...>
class FolyanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
