package com.rigstudio.app.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rigstudio.core.export.Mp4ContainerProbe
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes an MP4 with the platform encoder and muxer — no FFmpeg, no screen recording, no cloud.
 *
 * Frames arrive as ARGB pixels, are converted to YUV 4:2:0 and pushed into [MediaCodec] in
 * **buffer mode** with an explicit presentation timestamp per frame, then every encoded access
 * unit is written straight to [MediaMuxer]. Buffer mode (rather than an input surface) is what
 * gives exact timing: a surface would stamp frames with the wall clock and a stalled render would
 * silently stretch the video.
 *
 * Lifecycle: [open] → [submitFrame] × N → [endVideo] → [copyAudio] → [close].
 */
class Mp4Writer(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val keyFrameIntervalSeconds: Int,
    private val audio: AudioSource?,
) : AutoCloseable {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoFinished = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameBuffer: YuvConverter.FrameBuffer? = null

    val reusableFrameBuffer: YuvConverter.FrameBuffer
        get() = frameBuffer ?: YuvConverter.FrameBuffer(width, height).also { frameBuffer = it }

    /** Encoded bytes written so far — used for the live size readout in the progress UI. */
    var bytesWritten: Long = 0L
        private set

    fun open() {
        if (width % 2 != 0 || height % 2 != 0) {
            throw IOException("Video size ${width}×$height must be even for H.264.")
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Could not replace the previous export file.")
        }

        val muxerInstance = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            throw IOException("This device could not create an MP4 writer.", error)
        }
        muxer = muxerInstance

        // Audio track first: every track must be registered before the muxer starts.
        if (audio != null) {
            audioTrack = try {
                muxerInstance.addTrack(audio.format)
            } catch (error: Throwable) {
                throw IOException("That audio file cannot be written into an MP4.", error)
            }
        }

        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (error: Throwable) {
            throw IOException("This device has no H.264 encoder, so MP4 export is unavailable.", error)
        }
        encoder = codec

        val colorFormat = chooseColorFormat()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Throwable) {
            throw IOException("The H.264 encoder refused ${width}×$height at $fps fps.", error)
        }
    }

    /** Converts one rendered frame and feeds it to the encoder with an exact timestamp. */
    fun submitFrame(pixels: IntArray, presentationTimeUs: Long) {
        val codec = encoder ?: throw IOException("Encoder is not open.")
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) throw IOException("The video encoder stopped accepting frames.")
        val image = codec.getInputImage(index)
            ?: throw IOException("The video encoder does not accept pixel buffers on this device.")
        YuvConverter.argbToYuv420(pixels, width, height, image)
        codec.queueInputBuffer(index, 0, YuvConverter.yuvSize(width, height), presentationTimeUs, 0)
        drainEncoder(endOfStream = false)
    }

    /** Flushes the encoder and finishes the video track. */
    fun endVideo() {
        val codec = encoder ?: return
        if (videoFinished) return
        val index = codec.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoder(endOfStream = true)
        videoFinished = true
    }

    /**
     * Copies the chosen audio track into the file, trimmed to the video length.
     * Samples keep their own timestamps, so lip-sync is exactly as authored.
     */
    fun copyAudio(videoDurationUs: Long) {
        val source = audio ?: return
        val muxerInstance = muxer ?: return
        if (!muxerStarted) throw IOException("The MP4 writer is not ready for audio.")
        val info = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(AUDIO_BUFFER_BYTES)
        while (true) {
            val size = try {
                source.extractor.readSampleData(buffer, 0)
            } catch (error: Throwable) {
                break
            }
            if (size < 0) break
            val sampleTime = source.extractor.sampleTime
            if (sampleTime < 0 || sampleTime > videoDurationUs) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = sampleTime
            info.flags = if (source.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxerInstance.writeSampleData(audioTrack, buffer, info)
            bytesWritten += size
            if (!source.extractor.advance()) break
        }
    }

    override fun close() {
        try {
            encoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            encoder = null
        }
        try {
            muxer?.let { instance ->
                if (muxerStarted) runCatching { instance.stop() }
                runCatching { instance.release() }
            }
        } finally {
            muxer = null
            muxerStarted = false
        }
        audio?.close()
        frameBuffer = null
    }

    // --- internals ----------------------------------------------------------------------------

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        var attempts = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++attempts > MAX_DRAIN_ATTEMPTS) {
                        throw IOException("The video encoder stopped responding.")
                    }
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrack >= 0) throw IOException("The encoder changed format twice.")
                    val muxerInstance = muxer ?: throw IOException("Muxer is not open.")
                    videoTrack = muxerInstance.addTrack(codec.outputFormat)
                    startMuxerIfReady()
                }

                index >= 0 -> {
                    val encoded = codec.getOutputBuffer(index)
                        ?: throw IOException("The encoder returned an empty buffer.")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec-specific data belongs to the output format, not the sample stream.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(videoTrack, encoded, bufferInfo)
                        bytesWritten += bufferInfo.size
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                else -> throw IOException("The video encoder failed (code $index).")
            }
        }
    }

    private fun startMuxerIfReady() {
        if (muxerStarted) return
        if (videoTrack < 0) return
        if (audio != null && audioTrack < 0) return
        muxer?.start()
        muxerStarted = true
    }

    /**
     * Prefers the flexible YUV layout so the same conversion code works with planar and
     * semi-planar encoders alike; falls back to a concrete format when a device does not
     * advertise it.
     */
    private fun chooseColorFormat(): Int {
        val caps = encoderCapabilities() ?: return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val supported = caps.colorFormats.toSet()
        return listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
        ).firstOrNull { it in supported }
            ?: supported.firstOrNull { it != MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
            ?: throw IOException("This device's H.264 encoder does not accept pixel buffers.")
    }

    private fun encoderCapabilities(): MediaCodecInfo.CodecCapabilities? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val type = info.supportedTypes.firstOrNull {
                it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            } ?: continue
            return try {
                info.getCapabilitiesForType(type)
            } catch (error: Throwable) {
                null
            }
        }
        return null
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 20_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val MAX_DRAIN_ATTEMPTS = 60
        private const val AUDIO_BUFFER_BYTES = 512 * 1024

        /** Recommended bit rate: ~0.1 bits per pixel per frame, bounded to sane values. */
        fun recommendedBitRate(width: Int, height: Int, fps: Int): Int {
            val raw = (width.toLong() * height * fps * 10 / 100).toInt()
            return raw.coerceIn(1_500_000, 24_000_000)
        }

        /**
         * Post-write container validation using the engine's pure byte probe (the same checks the
         * unit tests exercise) plus the platform's own metadata reader.
         */
        fun probeContainer(file: File): Mp4ContainerProbe.Report {
            if (!file.isFile) {
                return Mp4ContainerProbe.probeBytes(ByteArray(0))
            }
            RandomAccessFile(file, "r").use { access ->
                return Mp4ContainerProbe.probe(access.length()) { offset, length ->
                    val buffer = ByteArray(length)
                    access.seek(offset)
                    var read = 0
                    while (read < length) {
                        val count = access.read(buffer, read, length - read)
                        if (count <= 0) break
                        read += count
                    }
                    buffer
                }
            }
        }
    }
}
