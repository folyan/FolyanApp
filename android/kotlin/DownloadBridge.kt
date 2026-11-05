package org.folyan.app  // поменяйте на свой package

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.chaquo.python.PyException
import com.chaquo.python.Python
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

// Мост Flutter <-> Python (Chaquopy). Канал: "folyan/download".
// Тяжёлые вызовы идут в отдельном потоке, ответ возвращается на главный поток.
class DownloadBridge(private val activity: Activity, engine: FlutterEngine) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ffmpeg лежит рядом с нативными библиотеками как libffmpeg.so
    private val ffmpegPath: String =
        activity.applicationInfo.nativeLibraryDir + "/libffmpeg.so"

    private fun dl() = Python.getInstance().getModule("dl")

    init {
        MethodChannel(engine.dartExecutor.binaryMessenger, "folyan/download")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "download" -> {
                        val url = call.argument<String>("url")!!
                        val height = call.argument<Int>("height")!!
                        val codec = call.argument<String>("codec")!!
                        val ai = call.argument<Boolean>("ai")!!
                        val fmt = call.argument<String>("fmt")!!
                        val outDir = call.argument<String>("outDir")!!
                        async(result) {
                            dl().callAttr("download", url, height, codec, ai, fmt, outDir, ffmpegPath)
                                .toString()
                        }
                    }
                    "convert" -> {
                        val src = call.argument<String>("src")!!
                        val fmt = call.argument<String>("fmt")!!
                        val outDir = call.argument<String>("outDir")!!
                        async(result) {
                            dl().callAttr("convert", src, fmt, outDir, ffmpegPath).toString()
                        }
                    }
                    "merge" -> {
                        val video = call.argument<String>("video")!!
                        val audio = call.argument<String>("audio")!!
                        val fmt = call.argument<String>("fmt")!!
                        val outDir = call.argument<String>("outDir")!!
                        async(result) {
                            dl().callAttr("merge", video, audio, outDir, fmt, ffmpegPath).toString()
                        }
                    }
                    "status" -> result.success(dl().callAttr("status").toString())
                    "cancel" -> {
                        dl().callAttr("cancel")
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun async(result: MethodChannel.Result, block: () -> String) {
        worker.execute {
            try {
                val value = block()
                main.post { result.success(value) }
            } catch (e: PyException) {
                // type: имя исключения Python, message: текст ошибки
                main.post { result.error("DL_FAILED", e.message, e.type) }
            }
        }
    }
}
