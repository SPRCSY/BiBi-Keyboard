package com.brycewg.asrkb.asr

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** SM8750-only SenseVoice. VAD continues to use the ONNX CPU runtime. */
internal object QnnSenseVoiceRuntime {
    const val TAG = "BiBiQnn"
    const val VARIANT = "qnn-sm8750-5s"
    private const val ASSET_DIR = "qnn-sm8750"
    @Volatile private var appContext: Context? = null
    @Volatile private var verifiedDirectory: File? = null

    fun resolve(context: Context): LocalModelCheck<SenseVoiceResolvedModel> {
        appContext = context.applicationContext
        val dir = File(context.noBackupFilesDir, ASSET_DIR)
        return try {
            // The large model is copied and verified on the inference IO thread, never
            // from the settings UI's model-status probe.
            context.assets.open("$ASSET_DIR/manifest.json").use { }
            LocalModelCheck.Ready(
                SenseVoiceResolvedModel(VARIANT, dir, File(dir, "tokens.txt").path, File(dir, "model.bin").path)
            )
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Bundled SM8750 model is missing", e)
            LocalModelCheck.Missing
        }
    }

    fun initialize(): String {
        val context = checkNotNull(appContext) { "QNN model resolver has not been initialized" }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        check(Build.VERSION.SDK_INT >= 31 && soc.uppercase().contains("SM8750")) {
            "This SenseVoice build requires SM8750 (HTP V79); found $soc. CPU fallback is disabled."
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val required = listOf(
            "libsherpa-onnx-jni.so", "libonnxruntime.so", "libQnnHtp.so", "libQnnSystem.so",
            "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so"
        )
        required.forEach { name -> check(File(nativeDir, name).isFile) { "Missing QNN runtime library: $name" } }
        val oldPath = Os.getenv("ADSP_LIBRARY_PATH").orEmpty()
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;$oldPath;/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp", true)
        materialize(context)
        Log.i(TAG, "QNN_CONFIG provider=qnn backend=libQnnHtp.so soc=$soc htp=V79 fallback=disabled")
        return nativeDir
    }

    @Synchronized
    private fun materialize(context: Context) {
        val dir = File(context.noBackupFilesDir, ASSET_DIR)
        if (verifiedDirectory == dir && File(dir, "model.bin").isFile && File(dir, "tokens.txt").isFile) return
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create QNN model directory" }
        val manifest = context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        check(manifest.getString("soc") == "SM8750" && manifest.getString("htp") == "V79" && manifest.getInt("seconds") == 5) {
            "Unexpected QNN model target or input duration"
        }
        for (name in listOf("model.bin", "tokens.txt")) {
            val spec = manifest.getJSONObject("files").getJSONObject(name)
            val expectedHash = spec.getString("sha256")
            val target = File(dir, name)
            if (target.isFile && target.length() == spec.getLong("size") && sha256(target) == expectedHash) continue
            val temporary = File(dir, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            check(temporary.length() == spec.getLong("size") && sha256(temporary) == expectedHash) {
                "QNN model integrity check failed: $name"
            }
            check(temporary.renameTo(target)) { "Cannot install QNN model: $name" }
        }
        verifiedDirectory = dir
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun windows(samples: FloatArray, sampleRate: Int): List<IntRange> {
        require(sampleRate == QnnAudioWindows.SAMPLE_RATE) { "SenseVoice QNN requires 16 kHz mono PCM" }
        val context = checkNotNull(appContext)
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            val signed = (value.coerceIn(-1f, 1f) * 32767).toInt()
            pcm[index * 2] = signed.toByte()
            pcm[index * 2 + 1] = (signed shr 8).toByte()
        }
        val silences = if (samples.size > QnnAudioWindows.WINDOW_SAMPLES) {
            RecordedAudioVoiceFilter.findSilenceRangesForLocalOfflineChunking(context, Prefs(context), pcm, sampleRate)
                .map { it.first / 2 until (it.last + 1) / 2 }
        } else emptyList()
        return QnnAudioWindows.ranges(samples.size, silences).filter { range ->
            silences.none { silence -> silence.first <= range.first && silence.last >= range.last }
        }
    }

    fun applyFirstRunDefaults(context: Context) {
        val migration = context.getSharedPreferences("qnn_build_defaults", Context.MODE_PRIVATE)
        if (migration.getBoolean("sm8750_v1", false)) return
        Prefs(context).apply {
            asrVendor = AsrVendor.SenseVoice
            backupAsrEnabled = false
            svPseudoStreamEnabled = true
            recordingAutoStopMode = Prefs.RecordingAutoStopMode.SILENCE
        }
        migration.edit().putBoolean("sm8750_v1", true).apply()
    }
}
