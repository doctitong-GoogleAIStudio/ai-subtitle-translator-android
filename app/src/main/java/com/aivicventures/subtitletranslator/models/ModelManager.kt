package com.aivicventures.subtitletranslator.models

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.json.JSONObject
import java.io.File

/** One model file; [path] is relative to assets/models in the APK. [url] and [sha256] pin
 *  the build-time download (fetchModels in app/build.gradle.kts). */
data class ModelFile(val path: String, val url: String, val size: Long, val sha256: String)

data class ModelSpec(
    val id: String,
    val kind: String,
    val name: String,
    val description: String,
    val files: List<ModelFile>,
    val license: String,
    val ramMb: Int,
    /** Speech models only: Automatic translation uses M2M100 with this model (measured to help). */
    val autoMt: Boolean = false,
) {
    val size: Long get() = files.sumOf { it.size }
}

/**
 * Every model of assets/model_catalog.json ships inside the APK (assets/models, stored
 * uncompressed), so nothing is downloaded. The big weights are read in place from the APK by
 * whisper.cpp and CTranslate2 ([weights]: file descriptor + offset); the few small files they
 * need as real files (Silero VAD, M2M100's vocabulary, config and tokenizer) are copied to
 * app storage once ([localFile], [localFolder]).
 */
class ModelManager(private val context: Context) {
    val catalog: List<ModelSpec> = loadCatalog()
    private val bundled: Set<String> = listAssets("models")
    private val localDir = File(context.filesDir, "bundled")

    init {
        // Downloads from versions before 1.0 are not used any more; free their space.
        File(context.filesDir, "models").takeIf { it.exists() }?.let { old ->
            Log.i(TAG, "removing downloaded models (now bundled): ${old.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000} MB")
            old.deleteRecursively()
        }
    }

    fun spec(id: String): ModelSpec? = catalog.firstOrNull { it.id == id }
    fun isInstalled(spec: ModelSpec) = spec.files.all { "models/${it.path}" in bundled }

    fun installedAsr(): List<ModelSpec> = catalog.filter { it.kind == "asr" && isInstalled(it) }
    fun vadModel(): ModelSpec? = catalog.firstOrNull { it.kind == "vad" && isInstalled(it) }
    fun translationModel(): ModelSpec? = catalog.firstOrNull { it.kind == "mt" && isInstalled(it) }

    /** The model the user chose, else the most accurate one that fits in this phone's memory. */
    fun chooseAsr(preferredId: String): ModelSpec? {
        val installed = installedAsr()
        installed.firstOrNull { it.id == preferredId }?.let { return it }
        val budgetMb = deviceMemoryMb() / 4
        return installed.filter { it.ramMb <= budgetMb }.maxByOrNull { it.size } ?: installed.minByOrNull { it.size }
    }

    /** The model's main file as a byte range of the APK; the caller closes it after loading. */
    fun weights(spec: ModelSpec): AssetFileDescriptor = context.assets.openFd("models/${spec.files.first().path}")

    /** A copy of one small model file in app storage (made once, refreshed when the APK's differs). */
    @Synchronized
    fun localFile(f: ModelFile): File {
        val target = File(localDir, f.path)
        val ok = File(target.path + ".ok")
        if (target.length() == f.size && ok.exists() && ok.readText() == f.sha256) return target
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        context.assets.open("models/${f.path}").use { input -> part.outputStream().use { input.copyTo(it) } }
        check(part.length() == f.size) { "${f.path} is damaged in this APK" }
        target.delete()
        check(part.renameTo(target)) { "could not write ${f.path}" }
        ok.writeText(f.sha256)
        return target
    }

    /** Folder with every file of a multi-file model except its main weights (read from the APK). */
    fun localFolder(spec: ModelSpec): File {
        spec.files.drop(1).forEach { localFile(it) }
        return File(localDir, spec.files.first().path).parentFile!!
    }

    private fun listAssets(dir: String): Set<String> = buildSet {
        for (name in context.assets.list(dir).orEmpty()) {
            val path = "$dir/$name"
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) add(path) else addAll(listAssets(path))
        }
    }

    private fun loadCatalog(): List<ModelSpec> {
        val json = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("models")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val files = o.optJSONArray("files")?.let { fa ->
                (0 until fa.length()).map { k ->
                    val f = fa.getJSONObject(k)
                    ModelFile(f.getString("path"), f.getString("url"), f.getLong("size"), f.getString("sha256"))
                }
            } ?: listOf(ModelFile(o.getString("file"), o.getString("url"), o.getLong("size"), o.getString("sha256")))
            ModelSpec(
                id = o.getString("id"), kind = o.getString("kind"), name = o.getString("name"),
                description = o.getString("description"), files = files,
                license = o.getString("license"), ramMb = o.getInt("ram_mb"),
                autoMt = o.optBoolean("auto_mt", false),
            )
        }
    }

    fun deviceMemoryMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}
