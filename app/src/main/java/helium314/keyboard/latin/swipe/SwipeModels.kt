// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Gets the FUTO Swipe models into [FutoSwipe.modelDir], by download or from a zip the user picks.
 * The models are under the FUTO Model Weights License 1.0 and are not part of the app.
 */
object SwipeModels {
    private const val TAG = "SwipeModels"
    // pinned so the checksums below stay valid
    private const val REVISION = "18328c3042b066952c0936b3771d492fe2ec289a"
    private const val BASE_URL = "https://huggingface.co/futo-org/futo-swipe/resolve/$REVISION/"
    const val LICENSE_URL = "https://huggingface.co/futo-org/futo-swipe/blob/main/LICENSE.md"

    class ModelFile(val path: String, val size: Long, val sha256: String)

    val FILES = listOf(
        ModelFile("honorable_sturgeon/model_fp32.pte", 2649856, "725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"),
        ModelFile("honorable_sturgeon/metadata.json", 2110, "d2c5aecd89d97e21125046eb1f311b5aed1bdb5805e97316bba70b13f1c7be2c"),
        ModelFile("magic_macaw/model_fp32.pte", 1247468, "01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85"),
        ModelFile("magic_macaw/metadata.json", 1683, "65ffc8890de41782eb3322aa96f31df24463ea434a200d3ce84aad4fe7c28a11"),
        ModelFile("hungry_jellyfish/context_lm.pte", 6247616, "74d29f56a513c0c60abcd43df3b16a6b68925cdf4e97e51b094a5275ec2810d7"),
        ModelFile("hungry_jellyfish/metadata.json", 59, "3daee38ea94796b676e7f60c3e1cf22525d5ae4dbdfa2787979a99e4573d9495"),
        ModelFile("hungry_jellyfish/vocab.txt", 284965, "a7db66376783b5a23ee3d4a2aaa8f2499fd9b35f975e92bcb248664c2cf6ebd1"),
    )
    val totalSize = FILES.sumOf { it.size }

    /** Blocking. [onProgress] gets 0..1. Throws IOException with a message for the user on failure. */
    fun download(context: Context, onProgress: (Float) -> Unit) {
        val staging = stagingDir(context)
        var done = 0L
        try {
            for (file in FILES) {
                val target = File(staging, file.path).apply { parentFile?.mkdirs() }
                val connection = URL(BASE_URL + file.path).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK)
                        throw IOException("server answered ${connection.responseCode} for ${file.path}")
                    connection.inputStream.use { input ->
                        copy(input, target, file.size) { done += it; onProgress(done.toFloat() / totalSize) }
                    }
                } finally {
                    connection.disconnect()
                }
                if (sha256(target) != file.sha256) throw IOException("${file.path} does not match its checksum")
            }
            install(context, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Blocking. Reads a zip that holds the three model folders, at the top level or inside one
     * folder (as in a download of the model repository). Files are matched by name; checksums are
     * not required to match, so newer models can be tried.
     */
    fun importZip(context: Context, uri: Uri) {
        val staging = stagingDir(context)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    val file = FILES.firstOrNull { name == it.path || name.endsWith("/" + it.path) } ?: continue
                    val target = File(staging, file.path).apply { parentFile?.mkdirs() }
                    copy(zip, target, 64L * 1024 * 1024) { }
                }
            }
            val missing = FILES.filter { !File(staging, it.path).isFile }
            if (missing.isNotEmpty()) throw IOException("the zip is missing ${missing.first().path}")
            install(context, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun remove(context: Context) {
        FutoSwipe.modelDir(context).deleteRecursively()
        FutoSwipe.onModelsChanged(context)
    }

    private fun stagingDir(context: Context) =
        File(context.cacheDir, "swipe_models_staging").apply { deleteRecursively(); mkdirs() }

    private fun install(context: Context, staging: File) {
        val target = FutoSwipe.modelDir(context)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
        }
        Log.i(TAG, "swipe models installed")
        FutoSwipe.onModelsChanged(context)
    }

    private fun copy(input: InputStream, target: File, maxSize: Long, onBytes: (Int) -> Unit) {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxSize) throw IOException("${target.name} is larger than expected")
                output.write(buffer, 0, read)
                onBytes(read)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
