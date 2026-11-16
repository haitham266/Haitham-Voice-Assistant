package com.haitham.voiceassistant.speech

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haitham.voiceassistant.settings.AssistantSettings
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Manages the offline Vosk models stored on the device:
 *  - "bundled": shipped inside the APK (assets/model-ar), unpacked once to internal storage.
 *  - "custom": a Vosk model zip imported by the user (e.g. vosk-model-ar-mgb2-0.4).
 * Nothing is ever downloaded by the app (it has no INTERNET permission).
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val ASSET_DIR = "model-ar"
    }

    data class ModelInfo(val dir: File, val name: String, val supportsGrammar: Boolean, val sizeMb: Long)

    private val root get() = File(context.filesDir, "models")
    private fun dirFor(id: String) = File(root, id)

    /** Unpacks the bundled model if missing or outdated. Returns its directory. */
    fun ensureBundled(onProgress: (Int) -> Unit = {}): File {
        val target = dirFor(AssistantSettings.MODEL_BUNDLED)
        val assetVersion = context.assets.open("$ASSET_DIR/MODEL_VERSION").bufferedReader().use { it.readText() }
        val marker = File(target, "MODEL_VERSION")
        if (marker.exists() && marker.readText() == assetVersion && File(target, "am/final.mdl").exists()) return target

        Log.i(TAG, "Unpacking bundled model…")
        val tmp = File(root, "bundled.tmp").apply { deleteRecursively(); mkdirs() }
        val files = context.assets.open("$ASSET_DIR/FILES").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        files.forEachIndexed { i, rel ->
            val out = File(tmp, rel)
            out.parentFile?.mkdirs()
            context.assets.open("$ASSET_DIR/$rel").use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            onProgress((i + 1) * 100 / files.size)
        }
        File(tmp, "MODEL_VERSION").writeText(assetVersion)
        target.deleteRecursively()
        check(tmp.renameTo(target)) { "Cannot move model into place" }
        return target
    }

    fun hasCustomModel(): Boolean = isValidModel(dirFor(AssistantSettings.MODEL_CUSTOM))

    fun describe(dir: File): ModelInfo {
        val supportsGrammar = File(dir, "graph/HCLr.fst").exists() && File(dir, "graph/Gr.fst").exists()
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
        val name = File(dir, "MODEL_VERSION").takeIf { it.exists() }?.readText()?.substringBefore(':')?.trim()
            ?: File(dir, "NAME").takeIf { it.exists() }?.readText()?.trim() ?: dir.name
        return ModelInfo(dir, name, supportsGrammar, size)
    }

    fun resolve(modelId: String, onProgress: (Int) -> Unit = {}): File =
        if (modelId == AssistantSettings.MODEL_CUSTOM && hasCustomModel()) dirFor(AssistantSettings.MODEL_CUSTOM)
        else ensureBundled(onProgress)

    fun isValidModel(dir: File): Boolean =
        File(dir, "am/final.mdl").exists() && File(dir, "conf/model.conf").exists() &&
            (File(dir, "graph/HCLG.fst").exists() || File(dir, "graph/HCLr.fst").exists())

    /**
     * Imports a Vosk model zip chosen by the user (Storage Access Framework).
     * The top-level folder inside the zip is stripped. Returns the model info or throws.
     */
    fun importZip(uri: Uri, onProgress: (Long) -> Unit = {}): ModelInfo {
        val tmp = File(root, "custom.tmp").apply { deleteRecursively(); mkdirs() }
        var bytes = 0L
        var name = "custom"
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "لا يمكن فتح الملف" }
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val parts = e.name.split('/')
                    if (parts.size > 1 && name == "custom") name = parts.first()
                    val rel = if (parts.size > 1) parts.drop(1).joinToString("/") else e.name
                    if (rel.isEmpty()) continue
                    val out = File(tmp, rel)
                    require(out.canonicalPath.startsWith(tmp.canonicalPath)) { "مسار غير صالح داخل الملف" }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> bytes += zip.copyTo(o, 1 shl 16) }
                    onProgress(bytes)
                }
            }
        }
        if (!isValidModel(tmp)) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("الملف ليس نموذج Vosk صالحاً (يجب أن يحتوي am و conf و graph)")
        }
        File(tmp, "NAME").writeText(name)
        val target = dirFor(AssistantSettings.MODEL_CUSTOM)
        target.deleteRecursively()
        check(tmp.renameTo(target))
        return describe(target)
    }

    fun deleteCustom() { dirFor(AssistantSettings.MODEL_CUSTOM).deleteRecursively() }
}
