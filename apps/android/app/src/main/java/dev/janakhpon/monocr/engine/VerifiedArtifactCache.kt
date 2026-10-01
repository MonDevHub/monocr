package dev.janakhpon.monocr.engine

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal object VerifiedArtifactCache {
    private const val PARTIAL_SUFFIX = ".partial"

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Delete the temporary files [ensure] leaves behind when the process dies between
     * creating one and publishing it. Its own `finally` never runs in that case, and
     * nothing else looks at the file again, so each crash would leave a model-sized
     * copy in the cache directory. Only names starting with [prefix] are touched.
     */
    fun deleteOrphanedPartials(directory: File, prefix: String): List<String> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(PARTIAL_SUFFIX) }
            .filter { it.delete() }
            .map { it.name }

    /** Verify the published file, repair once from the bundled source, then publish atomically. */
    fun ensure(file: File, hash: String, openSource: () -> InputStream): File {
        if (file.isFile && sha256(file) == hash) return file
        val temporary = File.createTempFile(file.name, PARTIAL_SUFFIX, file.parentFile)
        try {
            openSource().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(sha256(temporary) == hash) { "Bundled model checksum does not match this build" }
            check(temporary.renameTo(file)) { "Could not publish verified model cache" }
            return file
        } finally { temporary.delete() }
    }
}
