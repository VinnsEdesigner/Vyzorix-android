package com.vyzorix.engine

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Unpacks a `.tar.gz` into a directory, streaming.
 *
 * ## Why this exists rather than a library
 *
 * The container's rootfs arrives as a gzipped tar — `debian-core-<abi>.tar.gz` — and
 * Android ships no tar reader. Adding one would mean a dependency in the trust path
 * of the thing that runs as the container's root, for a format that is a few hundred
 * lines to read. So it is read here, and only the parts the build actually emits:
 * regular files, directories and symlinks.
 *
 * ## Streaming, not buffered
 *
 * Entries are written straight out as they are read, never collected first. A rootfs
 * is ~145 MB extracted, and `/data` on a phone is frequently tighter than that; a
 * "read it all, then write" shape needs both copies live at once. This needs one.
 *
 * ## What it refuses
 *
 * - **A path that escapes the destination.** A tar entry named `../../etc/passwd`, or
 *   one whose symlink chain leads out, is an archive that writes outside the rootfs.
 *   Both are refused rather than sanitised: the rootfs is our own build artifact, so
 *   an escaping path means the archive is not what we think it is.
 * - **A gzip stream that does not verify.** The caller passes the expected SHA-256 and
 *   it is checked as the bytes stream past, so a truncated or tampered archive fails
 *   before its contents are trusted — and without a second pass over 46 MB.
 *
 * Not handled, because the build never emits them: hard links, device nodes, PAX
 * extended headers beyond the name, sparse files, and long-name GNU extensions.
 * A tar containing them fails with a clear message rather than being half-read.
 */
internal object TarGz {
    private const val TAG = "TarGz"

    /** How much to copy at a time. Large enough that a 145 MB tree is not 35k syscalls. */
    private const val COPY_BUFFER = 64 * 1024

    private const val BLOCK = 512

    /** The three execute bits of a tar mode. Any one set means "run this". */
    private const val EXECUTE_BITS = 0b001_001_001
    private const val NAME_LEN = 100
    private const val PREFIX_LEN = 155
    private const val MODE_OFFSET = 100
    private const val MODE_LEN = 8
    private const val SIZE_OFFSET = 124
    private const val SIZE_LEN = 12
    private const val TYPE_OFFSET = 156
    private const val LINK_OFFSET = 157
    private const val LINK_LEN = 100
    private const val MAGIC_OFFSET = 257

    /** "ustar" plus a version byte. */
    private const val MAGIC_LEN = 6
    private const val PREFIX_OFFSET = 345
    private const val USTAR_MAGIC = "ustar"

    /**
     * Extracts [source] into [dest], verifying [expectedSha256] as it goes.
     *
     * [dest] is created if missing. Entries are written in archive order; a tar lists
     * a directory before its contents, which is what makes a single pass work.
     *
     * @throws IOException on a malformed archive, an escaping path, or a checksum
     *   mismatch. A partial tree may be left behind on failure; the caller removes it
     *   rather than resuming, because a half-extracted rootfs is not a state to run in.
     */
    fun extract(
        source: InputStream,
        dest: File,
        expectedSha256: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = DigestInputStream(BufferedInputStream(source, COPY_BUFFER), digest)

        // gzip is in the JDK, so this is one wrapper rather than another dependency.
        val gz = GZIPInputStream(counted, COPY_BUFFER)

        dest.mkdirs()
        if (!dest.isDirectory) {
            throw IOException("could not create ${dest.absolutePath}")
        }

        val canonicalDest = dest.canonicalFile

        readEntries(gz, canonicalDest)

        // An empty expectation means "nothing to verify against", not "verify and
        // fail". A bundled asset is covered by the APK signature, so the caller has
        // nothing to check it against; treating "" as a mismatch would reject every
        // bundled archive.
        if (expectedSha256.isNotEmpty()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("rootfs checksum mismatch: expected $expectedSha256, got $actual")
            }
        }
    }

    private fun readEntries(
        gz: InputStream,
        canonicalDest: File,
    ) {
        val header = ByteArray(BLOCK)

        while (true) {
            if (!readFully(gz, header)) return

            // A tar ends with two zero blocks. One is enough to stop: the second is
            // padding the writer is not obliged to emit.
            if (header.all { it == 0.toByte() }) return

            val name = entryName(header)
            if (name.isEmpty()) return

            val size = entrySize(header)
            val mode = entryMode(header)
            val type = header[TYPE_OFFSET].toInt().toChar()
            val linkName = cString(header, LINK_OFFSET, LINK_LEN)

            val target = resolve(canonicalDest, name)

            when (type) {
                '0', '\u0000' -> writeFile(gz, target, size, mode)
                '5' -> {
                    target.mkdirs()
                    skip(gz, size)
                }
                '1' -> {
                    // A hard link: two names for one inode. It must be a real link,
                    // not a copy — `usr/bin/perl` is one, and perl resolves its
                    // library paths from its own invocation name, so a copy would
                    // leave it looking for modules under the wrong name. The link
                    // target is earlier in the archive, which is what makes a single
                    // forward pass work.
                    val linkTarget = resolve(canonicalDest, linkName)
                    if (!linkTarget.isFile) {
                        throw IOException(
                            "hard link $name points at $linkName, which is not in the archive",
                        )
                    }
                    target.parentFile?.mkdirs()
                    target.delete()
                    try {
                        Files.createLink(target.toPath(), linkTarget.toPath())
                    } catch (e: IOException) {
                        // A filesystem that cannot hard-link across the tree (some
                        // FUSE mounts) still needs a working rootfs, and a copy is
                        // functionally equivalent for every case except perl's argv[0]
                        // resolution — which the link above gets when it can.
                        Log.w(TAG, "hard link $name failed, copying instead: ${e.message}")
                        linkTarget.copyTo(target, overwrite = true)
                    }

                    // The link entry carries its OWN mode, which is not always the
                    // target's. `usr/bin/perl` is `hrwxr-xr-x` pointing at an
                    // `-rwxr-xr-x` target, and a hard link shares the inode — so
                    // without this the link's mode (0600 here, from an unreadable
                    // header) is what the inode ends up with, and perl is not
                    // executable. Applying it keeps the two consistent.
                    applyMode(target, mode)

                    skip(gz, size)
                }
                '2' -> {
                    // A symlink inside the rootfs: /bin -> usr/bin is the common one,
                    // from Debian's merged-usr layout.
                    target.parentFile?.mkdirs()

                    // Removing whatever is at `target` first, because the extended
                    // tarball is `core + EXTENDED_PACKAGES` and therefore re-ships
                    // every core symlink — so by the time it is extracted the core
                    // has already created them, and `createSymbolicLink` would fail
                    // with FileAlreadyExistsException, aborting the whole install.
                    //
                    // **Never delete a real directory here.** The obvious fix —
                    // `deleteRecursively()` when the target is a directory — is
                    // catastrophic: `usr/lib64` is a real directory that the
                    // `lib64 -> usr/lib64` symlink resolves to, so removing it and
                    // then writing the symlink leaves `usr/lib64 -> usr/lib64`, an
                    // infinite self-loop. Every dynamically linked binary in the
                    // image then fails to exec ("ld-linux.so not found"), which
                    // reads as a corrupt rootfs.
                    //
                    // A directory in the way means the archive and the tree
                    // disagree, and the safe answer is to leave the directory
                    // alone: the merged-usr layout already satisfies the symlink,
                    // and a missing symlink is recoverable where a deleted lib
                    // directory is not.
                    if (target.isDirectory && !Files.isSymbolicLink(target.toPath())) {
                        Log.w(TAG, "symlink $name skipped: a real directory is in the way")
                        skip(gz, size)

                        continue
                    }

                    target.delete()

                    try {
                        Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
                    } catch (e: IOException) {
                        throw IOException(
                            "could not create symlink ${target.absolutePath} -> " +
                                "$linkName: ${e.message}",
                            e,
                        )
                    }
                    skip(gz, size)
                }
                else -> throw IOException(
                    "unsupported tar entry type '$type' for $name; " +
                        "the rootfs build emits only files, directories and symlinks",
                )
            }

            // Entries are padded to a 512-byte boundary.
            val padding = (BLOCK - (size % BLOCK)) % BLOCK
            skip(gz, padding)
        }
    }

    /**
     * Resolves an entry name against the destination, refusing anything that escapes.
     *
     * The check is on the *canonical* path, so a name like `a/../../b` is caught even
     * though it does not start with `..`.
     */
    private fun resolve(
        canonicalDest: File,
        name: String,
    ): File {
        val cleaned = name.removePrefix("./")
        val candidate = File(canonicalDest, cleaned).canonicalFile

        val destPath = canonicalDest.path
        val candidatePath = candidate.path
        if (candidatePath != destPath && !candidatePath.startsWith("$destPath${File.separator}")) {
            throw IOException("tar entry escapes the destination: $name")
        }

        return candidate
    }

    private fun writeFile(
        gz: InputStream,
        target: File,
        size: Long,
        mode: Int,
    ) {
        target.parentFile?.mkdirs()

        FileOutputStream(target).use { out ->
            var remaining = size
            val buffer = ByteArray(COPY_BUFFER)
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = gz.read(buffer, 0, want)
                if (read < 0) throw IOException("tar truncated inside ${target.name}")
                out.write(buffer, 0, read)
                remaining -= read
            }
        }

        applyMode(target, mode)
    }

    /**
     * Restores the execute bits the archive carried.
     *
     * Without this every file is non-executable, `bin/bash` included, and
     * `ProotLauncher.isReady()` is false — the exact failure the rootfs spec calls
     * out. Only the execute bits are restored: ownership and the exact read/write
     * bits do not matter inside proot, which maps the app uid to root, and Android
     * will not grant a wider mode than the app's own anyway.
     */
    private fun applyMode(
        target: File,
        mode: Int,
    ) {
        if (mode and EXECUTE_BITS == 0) return

        target.setExecutable(true, true)
        target.setReadable(true, true)
    }

    /** The mode, read as octal. Only the executable bits are used. */
    private fun entryMode(header: ByteArray): Int {
        val raw = cString(header, MODE_OFFSET, MODE_LEN).trim()
        if (raw.isEmpty()) return 0

        return raw.toIntOrNull(8) ?: 0
    }

    private fun skip(
        input: InputStream,
        count: Long,
    ) {
        var remaining = count
        val scratch = ByteArray(BLOCK)
        while (remaining > 0) {
            val want = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, want)
            if (read < 0) return
            remaining -= read
        }
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset != 0
            offset += read
        }

        return true
    }

    /** The name, honouring the ustar prefix field for paths over 100 bytes. */
    private fun entryName(header: ByteArray): String {
        val name = cString(header, 0, NAME_LEN)
        val magic = cString(header, MAGIC_OFFSET, MAGIC_LEN)
        if (!magic.startsWith(USTAR_MAGIC)) return name

        val prefix = cString(header, PREFIX_OFFSET, PREFIX_LEN)

        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The size, read as octal. The build never emits a base-256 size. */
    private fun entrySize(header: ByteArray): Long {
        val raw = cString(header, SIZE_OFFSET, SIZE_LEN).trim()
        if (raw.isEmpty()) return 0

        return raw.toLongOrNull(8)
            ?: throw IOException("unreadable tar entry size: '$raw'")
    }

    /** A NUL-terminated field. */
    private fun cString(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        var end = offset
        val limit = minOf(offset + length, buffer.size)
        while (end < limit && buffer[end] != 0.toByte()) end++

        return String(buffer, offset, end - offset, Charsets.UTF_8).trim()
    }
}
