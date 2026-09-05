package com.rigstudio.core.export

/**
 * Container-level validation of exported files.
 *
 * The app layer adds device checks (track count, duration and dimensions via
 * `MediaMetadataRetriever`); this file covers the parts that are pure byte inspection, so they can
 * be unit-tested off-device. Nothing is ever reported as a successful export until these pass —
 * a truncated or half-written file must never reach the Share sheet.
 */
object Mp4ContainerProbe {

    /** Result of walking an MP4's top-level boxes. */
    data class Report(
        val fileSize: Long,
        val hasFtyp: Boolean,
        val brand: String,
        val boxes: List<String>,
        val hasMoov: Boolean,
        val hasMdat: Boolean,
        val mdatSize: Long,
        val truncated: Boolean,
    ) {
        /**
         * A playable MP4 must declare a brand, contain movie metadata (`moov`) and at least one
         * media data box (`mdat`) that actually holds bytes.
         */
        val looksValid: Boolean
            get() = fileSize > 0 && hasFtyp && hasMoov && hasMdat && mdatSize > 0 && !truncated

        /** Reason suitable for an error dialog, or null when the file looks fine. */
        val failureReason: String?
            get() = when {
                fileSize <= 0 -> "The exported file is empty."
                !hasFtyp -> "The exported file is not a valid MP4 (missing file type box)."
                truncated -> "The exported file is incomplete — it was cut short while writing."
                !hasMoov -> "The exported file has no video metadata (moov box missing)."
                !hasMdat || mdatSize <= 0 -> "The exported file contains no video data."
                else -> null
            }
    }

    /**
     * Walks the top-level box structure.
     *
     * @param size total file size in bytes.
     * @param reader reads up to [length] bytes at [offset]; must return fewer bytes at EOF.
     */
    fun probe(size: Long, reader: (offset: Long, length: Int) -> ByteArray): Report {
        if (size < 16) {
            return Report(size, false, "", emptyList(), false, false, 0, size > 0)
        }
        val header = reader(0, 12)
        if (header.size < 12) {
            return Report(size, false, "", emptyList(), false, false, 0, true)
        }
        val ftypType = String(header, 4, 4, Charsets.US_ASCII)
        val hasFtyp = ftypType == "ftyp"
        val brand = if (hasFtyp) String(header, 8, 4, Charsets.US_ASCII) else ""

        val boxes = mutableListOf<String>()
        var offset = 0L
        var mdatSize = 0L
        var hasMoov = false
        var hasMdat = false
        var truncated = false
        var guard = 0

        while (offset < size && guard < 4096) {
            guard++
            val headerBytes = reader(offset, 16)
            if (headerBytes.size < 8) {
                truncated = true
                break
            }
            var boxSize = readUInt32(headerBytes, 0)
            val type = String(headerBytes, 4, 4, Charsets.US_ASCII)
            var headerLength = 8L

            when (boxSize) {
                1L -> {
                    // 64-bit extended size follows the type.
                    if (headerBytes.size < 16) {
                        truncated = true
                        break
                    }
                    boxSize = readUInt64(headerBytes, 8)
                    headerLength = 16L
                }
                0L -> // box runs to end of file
                    boxSize = size - offset
                else -> Unit
            }

            if (boxSize < headerLength || offset + boxSize > size) {
                truncated = true
                break
            }
            boxes += type
            if (type == "moov") hasMoov = true
            if (type == "mdat") {
                hasMdat = true
                mdatSize += boxSize - headerLength
            }
            offset += boxSize
        }

        if (offset < size && !truncated) {
            // Trailing bytes that are not part of any box: treat as a damaged file.
            truncated = true
        }

        return Report(
            fileSize = size,
            hasFtyp = hasFtyp,
            brand = brand,
            boxes = boxes,
            hasMoov = hasMoov,
            hasMdat = hasMdat,
            mdatSize = mdatSize,
            truncated = truncated,
        )
    }

    /** Convenience overload for in-memory buffers (used by tests). */
    fun probeBytes(bytes: ByteArray): Report = probe(bytes.size.toLong()) { offset, length ->
        val start = offset.toInt().coerceIn(0, bytes.size)
        val end = (start + length).coerceAtMost(bytes.size)
        bytes.copyOfRange(start, end)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

/** PNG signature and header checks for the PNG-sequence exporter. */
object PngProbe {

    val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun hasValidSignature(header: ByteArray): Boolean {
        if (header.size < SIGNATURE.size) return false
        for (i in SIGNATURE.indices) {
            if (header[i] != SIGNATURE[i]) return false
        }
        return true
    }

    data class Report(
        val fileSize: Long,
        val validSignature: Boolean,
        val width: Int,
        val height: Int,
        val hasIhdr: Boolean,
        val hasIend: Boolean,
    ) {
        /**
         * A PNG is only accepted when it is complete: signature, header with usable dimensions
         * and the terminating IEND chunk that proves the encoder finished writing.
         */
        val looksValid: Boolean
            get() = fileSize > 0 && validSignature && hasIhdr && width > 0 && height > 0 && hasIend

        val failureReason: String?
            get() = when {
                fileSize <= 0 -> "The exported PNG is empty."
                !validSignature -> "The exported file is not a valid PNG."
                !hasIhdr -> "The exported PNG has no image header."
                width <= 0 || height <= 0 -> "The exported PNG has invalid dimensions."
                !hasIend -> "The exported PNG is incomplete (missing IEND)."
                else -> null
            }
    }

    /**
     * Inspects the first bytes of a PNG: signature, IHDR dimensions and (when the whole file is
     * available) the terminating IEND chunk.
     */
    fun probe(size: Long, reader: (offset: Long, length: Int) -> ByteArray): Report {
        if (size < 33) return Report(size, false, 0, 0, false, false)
        val head = reader(0, 33)
        if (head.size < 33) return Report(size, false, 0, 0, false, false)

        val validSignature = hasValidSignature(head)
        val ihdrType = String(head, 12, 4, Charsets.US_ASCII)
        val hasIhdr = ihdrType == "IHDR"
        val width = if (hasIhdr) readInt32(head, 16) else 0
        val height = if (hasIhdr) readInt32(head, 20) else 0

        // IEND is the last 12 bytes of every valid PNG (length + type + CRC, no payload).
        val tail = reader((size - 12).coerceAtLeast(0), 12)
        val hasIend = tail.size == 12 && String(tail, 4, 4, Charsets.US_ASCII) == "IEND"

        return Report(size, validSignature, width, height, hasIhdr, hasIend)
    }

    fun probeBytes(bytes: ByteArray): Report = probe(bytes.size.toLong()) { offset, length ->
        val start = offset.toInt().coerceIn(0, bytes.size)
        val end = (start + length).coerceAtMost(bytes.size)
        bytes.copyOfRange(start, end)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
