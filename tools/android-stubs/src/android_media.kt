@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.media

import android.content.Context
import android.net.Uri
import android.view.Surface
import java.io.Closeable
import java.io.FileDescriptor
import java.nio.ByteBuffer

class Image : Closeable {
    interface Plane {
        val buffer: ByteBuffer
        val pixelStride: Int
        val rowStride: Int
        val bufferCapacity: Int get() = 0
    }

    val width: Int get() = 0
    val height: Int get() = 0
    val format: Int get() = 0
    val timestamp: Long get() = 0L
    val planes: Array<Plane> get() = emptyArray()
    override fun close() {}
}

class MediaCodecInfo {
    val name: String get() = ""
    val isEncoder: Boolean get() = false
    val isSoftwareOnly: Boolean get() = false
    val supportedTypes: Array<String> get() = emptyArray()

    fun getCapabilitiesForType(type: String): CodecCapabilities = CodecCapabilities()

    class CodecCapabilities {
        val colorFormats: IntArray get() = IntArray(0)
        val profileLevels: Array<CodecProfileLevel> get() = emptyArray()

        class CodecProfileLevel {
            @JvmField var profile: Int = 0
            @JvmField var level: Int = 0
        }

        companion object {
            const val COLOR_FormatSurface = 0x7F000789
            const val COLOR_FormatYUV420Planar = 0x13
            const val COLOR_FormatYUV420PackedPlanar = 0x14
            const val COLOR_FormatYUV420SemiPlanar = 0x15
            const val COLOR_FormatYUV420PackedSemiPlanar = 0x16
            const val COLOR_FormatYUV420Flexible = 0x7F420888
            const val COLOR_QCOM_FormatYUV420SemiPlanar = 0x7FA30C00
        }
    }

    class EncoderCapabilities {
        val supportedBitrateModes: IntArray get() = IntArray(0)

        companion object {
            const val BITRATE_MODE_CBR = 1
            const val BITRATE_MODE_VBR = 2
            const val BITRATE_MODE_CQ = 3
        }
    }

    class VideoCapabilities {
        fun isSizeSupported(width: Int, height: Int): Boolean = true
        fun isSizeAndRateSupported(width: Int, height: Int, frameRate: Int): Boolean = true
    }
}

class MediaCodecList(kind: Int) {
    val codecInfos: Array<MediaCodecInfo> get() = emptyArray()

    companion object {
        const val ALL_CODECS = 1
        const val REGULAR_CODECS = 0
    }
}

class MediaFormat {
    fun setInteger(name: String, value: Int) {}
    fun setLong(name: String, value: Long) {}
    fun setFloat(name: String, value: Float) {}
    fun setString(name: String, value: String) {}
    fun setByteBuffer(name: String, value: ByteBuffer) {}
    fun getInteger(name: String): Int = 0
    fun getLong(name: String): Long = 0L
    fun getFloat(name: String): Float = 0f
    fun getString(name: String): String? = null
    fun containsKey(name: String): Boolean = false
    val keys: Set<String> get() = emptySet()

    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val MIMETYPE_VIDEO_HEVC = "video/hevc"
        const val MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
        const val KEY_MIME = "mime"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_COLOR_FORMAT = "color-format"
        const val KEY_BIT_RATE = "bitrate"
        const val KEY_BITRATE_MODE = "bitrate-mode"
        const val KEY_FRAME_RATE = "frame-rate"
        const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
        const val KEY_DURATION = "durationUs"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        const val KEY_MAX_INPUT_SIZE = "max-input-size"
        const val KEY_ROTATION = "rotation-degrees"

        @JvmStatic
        fun createVideoFormat(mime: String, width: Int, height: Int): MediaFormat = MediaFormat()
        @JvmStatic
        fun createAudioFormat(mime: String, sampleRate: Int, channelCount: Int): MediaFormat = MediaFormat()
    }
}

class MediaCodec {
    class BufferInfo {
        @JvmField var offset: Int = 0
        @JvmField var size: Int = 0
        @JvmField var presentationTimeUs: Long = 0L
        @JvmField var flags: Int = 0
        fun set(newOffset: Int, newSize: Int, newTimeUs: Long, newFlags: Int) {}
    }

    class CodecException(message: String? = null) : IllegalStateException(message)

    val outputFormat: MediaFormat get() = MediaFormat()
    val inputFormat: MediaFormat get() = MediaFormat()
    val isEncoding: Boolean get() = true

    fun configure(format: MediaFormat, surface: Surface?, crypto: Any?, flags: Int) {}
    fun start() {}
    fun stop() {}
    fun release() {}
    fun flush() {}
    fun dequeueInputBuffer(timeoutUs: Long): Int = -1
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = -1
    fun getInputBuffer(index: Int): ByteBuffer? = null
    fun getInputImage(index: Int): Image? = null
    fun getOutputBuffer(index: Int): ByteBuffer? = null
    fun getOutputImage(index: Int): Image? = null
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
    fun releaseOutputBuffer(index: Int, render: Boolean) {}
    fun signalEndOfInputStream() {}

    companion object {
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_BUFFERS_CHANGED = -3
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val CONFIGURE_FLAG_ENCODE = 1
        const val CONFIGURE_FLAG_USE_BLOCK_MODEL = 4

        @JvmStatic
        fun createEncoderByType(type: String): MediaCodec = MediaCodec()
        @JvmStatic
        fun createDecoderByType(type: String): MediaCodec = MediaCodec()
        @JvmStatic
        fun createByCodecName(name: String): MediaCodec = MediaCodec()
    }
}

class MediaMuxer(path: String, format: Int) {
    class OutputFormat {
        companion object {
            const val MUXER_OUTPUT_MPEG_4 = 0
            const val MUXER_OUTPUT_WEBM = 1
            const val MUXER_OUTPUT_3GPP = 2
        }
    }

    fun addTrack(format: MediaFormat): Int = 0
    fun start() {}
    fun stop() {}
    fun release() {}
    fun setOrientationHint(degrees: Int) {}
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {}
}

class MediaExtractor {
    val trackCount: Int get() = 0
    val sampleTime: Long get() = 0L
    val sampleFlags: Int get() = 0
    val sampleSize: Int get() = 0

    fun setDataSource(path: String) {}
    fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {}
    fun setDataSource(fd: FileDescriptor, offset: Long, length: Long) {}
    fun getTrackFormat(index: Int): MediaFormat = MediaFormat()
    fun getTrackMediaType(index: Int): String = ""
    fun selectTrack(index: Int) {}
    fun unselectTrack(index: Int) {}
    fun readSampleData(byteBuf: ByteBuffer, offset: Int): Int = -1
    fun advance(): Boolean = false
    fun seekTo(timeUs: Long, mode: Int) {}
    fun release() {}

    companion object {
        const val SAMPLE_FLAG_SYNC = 1
        const val SEEK_TO_PREVIOUS_SYNC = 0
        const val SEEK_TO_CLOSEST_SYNC = 2
    }
}

class MediaMetadataRetriever : Closeable {
    fun setDataSource(path: String) {}
    fun setDataSource(context: Context, uri: Uri) {}
    fun extractMetadata(keyCode: Int): String? = null
    fun getFrameAtTime(timeUs: Long, option: Int): android.graphics.Bitmap? = null
    fun release() {}
    override fun close() {}

    companion object {
        const val METADATA_KEY_MIMETYPE = 12
        const val METADATA_KEY_VIDEO_WIDTH = 3
        const val METADATA_KEY_VIDEO_HEIGHT = 4
        const val METADATA_KEY_VIDEO_ROTATION = 24
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_BITRATE = 20
        const val METADATA_KEY_NUM_TRACKS = 10
        const val OPTION_PREVIOUS_SYNC = 0
        const val OPTION_CLOSEST_SYNC = 2
    }
}

class MediaScannerConnection {
    companion object {
        @JvmStatic
        fun scanFile(context: Context, paths: Array<String>, mimeTypes: Array<String>?, callback: Any?) {}
    }
}
