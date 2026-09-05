package com.rigstudio.app.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException

/**
 * A locally stored audio track the user chose for their export.
 *
 * Audio is **copied**, never transcoded and never generated: RigStudio has no synthesis of its
 * own, and re-encoding would need a decoder/encoder pair for no benefit. That also means the
 * container has to accept the codec as-is — MP4 takes AAC, so anything else is refused with a
 * plain-language message instead of failing halfway through a render.
 */
class AudioSource private constructor(
    val extractor: MediaExtractor,
    val trackIndex: Int,
    val format: MediaFormat,
    val durationMicros: Long,
) : AutoCloseable {

    val mimeType: String get() = format.getString(MediaFormat.KEY_MIME) ?: ""

    val sampleRate: Int get() = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    } else {
        0
    }

    override fun close() {
        try {
            extractor.release()
        } catch (ignored: Throwable) {
            // nothing useful to do while tearing down
        }
    }

    companion object {

        /** Codecs an MP4 container can carry without transcoding. */
        private val MP4_COMPATIBLE = setOf("audio/mp4a-latm", "audio/mp4a", "audio/aac")

        /**
         * Opens the first audio track of [uri].
         *
         * @return null when the file has no audio track at all.
         * @throws IOException when the file cannot be read or its codec cannot go into an MP4.
         */
        fun open(context: Context, uri: Uri): AudioSource? {
            val extractor = MediaExtractor()
            try {
                val opened = try {
                    extractor.setDataSource(context, uri, null)
                    true
                } catch (error: Throwable) {
                    false
                }
                if (!opened) {
                    val path = uri.path
                    if (path != null) {
                        extractor.setDataSource(path)
                    } else {
                        throw IOException("That audio file could not be opened.")
                    }
                }
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (trackIndex == null) {
                    extractor.release()
                    return null
                }
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.lowercase() !in MP4_COMPATIBLE) {
                    extractor.release()
                    throw IOException(
                        "That audio file uses ${mime.ifBlank { "an unsupported" }} audio, which " +
                            "cannot be placed inside an MP4. Please choose an AAC or M4A file.",
                    )
                }
                extractor.selectTrack(trackIndex)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                return AudioSource(extractor, trackIndex, format, duration)
            } catch (error: Throwable) {
                try {
                    extractor.release()
                } catch (ignored: Throwable) {
                    // already failing; nothing to add
                }
                if (error is IOException) throw error
                throw IOException("That audio file could not be read.", error)
            }
        }
    }
}
