package com.rm.whelk.core

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** A file or folder on the server. Times are milliseconds since 1970. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
    /** Like "rwxr-xr-x". */
    val permissions: String,
)

/** File operations over an SSH connection's SFTP channel. Every call blocks. */
class Sftp(connection: SshConnection) : Closeable {
    private val sftp: SFTPClient = connection.client.newSFTPClient()

    fun home(): String = sftp.canonicalize(".")

    fun canonical(path: String): String = sftp.canonicalize(path)

    /** The folder's contents, folders first, then by name. Links to folders count as folders. */
    fun list(path: String): List<RemoteEntry> = sftp.ls(path).filter { safeName(it.name) != null }.map { r ->
        val a = r.attributes
        val link = a.type == FileMode.Type.SYMLINK
        val dir = a.type == FileMode.Type.DIRECTORY ||
            (link && runCatching { sftp.stat(r.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false))
        RemoteEntry(r.name, r.path, dir, link, a.size, a.mtime * 1000, permissionString(a.mode.permissionsMask))
    }.sortedWith(compareBy<RemoteEntry> { !it.isDir }.thenBy { it.name.lowercase() })

    /** Copies the file to [out], calling [progress] with the bytes so far. */
    fun download(path: String, out: OutputStream, progress: (Long) -> Unit = {}) {
        sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
            val input = file.ReadAheadRemoteFileInputStream(16)
            copy(input, out, progress)
        }
    }

    /** Writes [input] to the file at [path], replacing it. */
    fun upload(input: InputStream, path: String, progress: (Long) -> Unit = {}) {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            val out = file.RemoteFileOutputStream(0, 16)
            copy(input, out, progress)
            out.close()
        }
    }

    fun mkdir(path: String) = sftp.mkdir(path)

    fun rename(from: String, to: String) = sftp.rename(from, to)

    /** Deletes a file, or a folder and everything in it. */
    fun delete(entry: RemoteEntry) {
        if (entry.isDir && !entry.isLink) {
            for (child in list(entry.path)) delete(child)
            sftp.rmdir(entry.path)
        } else {
            sftp.rm(entry.path)
        }
    }

    override fun close() {
        runCatching { sftp.close() }
    }

    private fun copy(input: InputStream, out: OutputStream, progress: (Long) -> Unit) {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total - lastReport >= 64 * 1024) {
                lastReport = total
                progress(total)
            }
        }
        out.flush()
        progress(total)
    }

    companion object {
        fun permissionString(mask: Int): String {
            val chars = "rwxrwxrwx"
            return buildString {
                for (i in 0 until 9) append(if (mask and (1 shl (8 - i)) != 0) chars[i] else '-')
            }
        }

        /** The parent folder of a path, or null at the root. */
        fun parent(path: String): String? {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return null
            val i = p.lastIndexOf('/')
            return if (i <= 0) "/" else p.substring(0, i)
        }

        fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

        /**
         * A file name that's safe to use as one part of a path, or null. Names from a server or
         * another app could hold slashes or "..", which would reach outside the folder they go in.
         */
        fun safeName(name: String): String? {
            val last = name.substringAfterLast('/').substringAfterLast('\\')
            return last.takeIf { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it && last == name }
        }
    }
}
