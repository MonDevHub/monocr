package dev.janakhpon.monocr.engine

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal object VerifiedArtifactCache {
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

    /** Verify the published file, repair once from the bundled source, then publish atomically. */
    fun ensure(file: File, hash: String, openSource: () -> InputStream): File {
        if (file.isFile && sha256(file) == hash) return file
        val temporary = File.createTempFile(file.name, ".partial", file.parentFile)
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
