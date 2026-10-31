package com.aivicventures.subtitletranslator.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One downloadable file; [path] is relative to the models folder. */
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

sealed class ModelState {
    data object NotInstalled : ModelState()
    data class Downloading(val bytes: Long, val total: Long) : ModelState()
    data object Verifying : ModelState()
    data object Installed : ModelState()
    data class Failed(val message: String) : ModelState()
}

/**
 * Downloads models from pinned URLs (assets/model_catalog.json), resumes interrupted
 * downloads with HTTP Range, and installs a file only after its SHA-256 matches. A model
 * may consist of several files (M2M100: weights, vocabulary, tokenizer); it counts as
 * installed only when every file is verified.
 * Models live in app-private storage (filesDir/models) and are never bundled.
 */
class ModelManager(private val context: Context) {
    val catalog: List<ModelSpec> = loadCatalog()
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow(catalog.associate { it.id to initialState(it) })
    val states: StateFlow<Map<String, ModelState>> = _states

    fun spec(id: String): ModelSpec? = catalog.firstOrNull { it.id == id }
    /** The model's main file (single-file models: the model itself). */
    fun file(spec: ModelSpec) = File(dir, spec.files.first().path)
    /** Folder holding a multi-file model. */
    fun folder(spec: ModelSpec): File = file(spec).parentFile!!
    fun isInstalled(spec: ModelSpec) = _states.value[spec.id] == ModelState.Installed

    fun installedAsr(): List<ModelSpec> = catalog.filter { it.kind == "asr" && isInstalled(it) }
    fun vadModel(): ModelSpec? = catalog.firstOrNull { it.kind == "vad" && isInstalled(it) }
    fun translationModel(): ModelSpec? = catalog.firstOrNull { it.kind == "mt" && isInstalled(it) }

    /** The model the user chose, else the most accurate one installed that fits in memory. */
    fun chooseAsr(preferredId: String): ModelSpec? {
        val installed = installedAsr()
        installed.firstOrNull { it.id == preferredId }?.let { return it }
        val budgetMb = deviceMemoryMb() / 4
        return installed.filter { it.ramMb <= budgetMb }.maxByOrNull { it.size } ?: installed.minByOrNull { it.size }
    }

    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch {
            try {
                downloadAndVerify(spec)
                setState(spec.id, ModelState.Installed)
            } catch (e: Exception) {
                Log.w(TAG, "download ${spec.id} failed", e)
                setState(spec.id, if (isActive) ModelState.Failed(e.message ?: e.javaClass.simpleName) else initialState(spec))
            }
        }
    }

    fun cancel(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
        setState(spec.id, initialState(spec))
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        for (f in spec.files) {
            File(dir, f.path).delete()
            File(dir, f.path + ".part").delete()
            File(dir, f.path + ".ok").delete()
        }
        setState(spec.id, ModelState.NotInstalled)
    }

    private suspend fun downloadAndVerify(spec: ModelSpec) {
        var done = 0L
        for (f in spec.files) {
            if (!fileVerified(f)) downloadFile(spec, f, done)
            done += f.size
        }
    }

    private suspend fun downloadFile(spec: ModelSpec, f: ModelFile, offset: Long) {
        val part = File(dir, f.path + ".part")
        part.parentFile?.mkdirs()
        var have = if (part.exists()) part.length() else 0L
        if (have > f.size) { part.delete(); have = 0 }
        if (have < f.size) {
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code == 200) have = 0 else if (code != 206) error("HTTP $code")
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport > 1_000_000 || have == f.size) {
                                lastReport = have
                                setState(spec.id, ModelState.Downloading(offset + have, spec.size))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        setState(spec.id, ModelState.Verifying)
        val actual = sha256(part)
        if (part.length() != f.size || !actual.equals(f.sha256, ignoreCase = true)) {
            part.delete()
            error("checksum mismatch for ${f.path.substringAfterLast('/')} (got ${actual.take(12)}…), download removed")
        }
        val target = File(dir, f.path)
        target.delete()
        if (!part.renameTo(target)) error("could not install ${f.path}")
        // Remember the verified size + checksum so start-up does not hash 500 MB again.
        File(dir, f.path + ".ok").writeText("${f.size} ${f.sha256}")
    }

    private fun fileVerified(f: ModelFile): Boolean {
        val file = File(File(context.filesDir, "models"), f.path)
        val ok = File(file.path + ".ok")
        return file.exists() && file.length() == f.size && ok.exists() && ok.readText().trim() == "${f.size} ${f.sha256}"
    }

    private fun initialState(spec: ModelSpec): ModelState =
        if (spec.files.all { fileVerified(it) }) ModelState.Installed else ModelState.NotInstalled

    private fun setState(id: String, s: ModelState) {
        _states.value = _states.value.toMutableMap().apply { put(id, s) }
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

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
