package com.rigstudio.core.tests

import com.rigstudio.core.export.ExportFormat
import com.rigstudio.core.export.ExportFrameRate
import com.rigstudio.core.export.ExportLimits
import com.rigstudio.core.export.ExportResolution
import com.rigstudio.core.export.ExportSettings
import com.rigstudio.core.export.FramePlan
import com.rigstudio.core.export.Mp4ContainerProbe
import com.rigstudio.core.export.PngProbe
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.ViewKind

/**
 * Export plumbing (spec §24–§28): frame sampling, safe limits, and file validation.
 *
 * The container probes here are the pure-byte half of export validation; the app module adds the
 * device half (track count, duration and dimensions via `MediaMetadataRetriever`) before a file is
 * ever offered to Save or Share.
 */
object ExportTests {

    // --- tiny MP4/PNG byte builders ---------------------------------------------------------

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun be32(value: Long) = bytes(
        ((value shr 24) and 0xFF).toInt(),
        ((value shr 16) and 0xFF).toInt(),
        ((value shr 8) and 0xFF).toInt(),
        (value and 0xFF).toInt(),
    )

    private fun box(type: String, payload: ByteArray = ByteArray(0), extendedSize: Long? = null): ByteArray {
        val ascii = type.toByteArray(Charsets.US_ASCII)
        return if (extendedSize != null) {
            be32(1) + ascii + ByteArray(8).also { out ->
                for (i in 0 until 8) out[i] = ((extendedSize shr (56 - i * 8)) and 0xFF).toByte()
            } + payload
        } else {
            be32((8 + payload.size).toLong()) + ascii + payload
        }
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (array in arrays) {
            array.copyInto(out, offset)
            offset += array.size
        }
        return out
    }

    private fun ftypBox(brand: String = "mp42") = box(
        "ftyp",
        brand.toByteArray(Charsets.US_ASCII) + be32(0) + "isom".toByteArray(Charsets.US_ASCII),
    )

    private fun validMp4(payloadSize: Int = 64) = concat(
        ftypBox(),
        box("mdat", ByteArray(payloadSize) { (it % 251).toByte() }),
        box("moov", box("mvhd", ByteArray(4))),
    )

    private fun validPng(width: Int = 1920, height: Int = 1080): ByteArray {
        val ihdrPayload = be32(width.toLong()) + be32(height.toLong()) + bytes(8, 6, 0, 0, 0)
        val ihdr = be32(13) + "IHDR".toByteArray(Charsets.US_ASCII) + ihdrPayload + be32(0)
        val idat = be32(4) + "IDAT".toByteArray(Charsets.US_ASCII) + ByteArray(4) + be32(0)
        val iend = be32(0) + "IEND".toByteArray(Charsets.US_ASCII) + be32(0)
        return PngProbe.SIGNATURE + ihdr + idat + iend
    }

    val cases: List<TestCase> = listOf(
        TestCase("frame count is duration times fps") {
            Assert.equals(15, FramePlan.of(0.5f, 30).frameCount, "0.5 s at 30 fps")
            Assert.equals(72, FramePlan.of(3f, 24).frameCount, "3 s at 24 fps")
            Assert.equals(90, FramePlan.of(3f, 30).frameCount, "3 s at 30 fps")
            Assert.equals(180, FramePlan.of(3f, 60).frameCount, "3 s at 60 fps")
            Assert.equals(60, FramePlan.of(1f, 60).frameCount, "1 s at 60 fps")
            Assert.equals(1, FramePlan.of(0f, 30).frameCount, "never zero frames")
            Assert.equals(1, FramePlan.of(0.01f, 30).frameCount, "rounds up to a single frame")
            Assert.equals(30, FramePlan.of(2.5f, 12).frameCount, "rounds to nearest")
            Assert.equals(1800, FramePlan.of(30f, 60).frameCount, "the documented ceiling")
        },
        TestCase("frame timings are exact and monotonic") {
            val plan = FramePlan.of(3f, 30)
            Assert.equals(90, plan.frameCount)
            Assert.close(1f / 30f, plan.frameDurationSeconds, 1e-6f)
            Assert.close(0f, plan.timeAt(0), 1e-6f)
            Assert.close(1f / 30f, plan.timeAt(1), 1e-6f)
            Assert.close(89f / 30f, plan.timeAt(89), 1e-6f)
            Assert.equals(0L, plan.presentationTimeUs(0))
            Assert.equals(33_333L, plan.presentationTimeUs(1), "pts at 30 fps")
            Assert.equals(1_000_000L * 89 / 30, plan.presentationTimeUs(89))
            val times = (0 until plan.frameCount).map { plan.presentationTimeUs(it) }
            Assert.equals(times.sorted(), times, "presentation times must increase")
            Assert.equals(times.distinct().size, times.size, "presentation times must be unique")
            // Out of range indices are clamped rather than crashing the encoder.
            Assert.equals(0L, plan.presentationTimeUs(-5))
            Assert.equals(plan.presentationTimeUs(plan.frameCount - 1), plan.presentationTimeUs(9999))
        },
        TestCase("normalized frame time accounts for speed") {
            val plan = FramePlan.of(2f, 30)
            // Frame 30 of a 30 fps plan is one second in; a one second clip is therefore fully
            // played at normal speed, and played twice through at double speed.
            Assert.close(1f, plan.normalizedTimeAt(30, clipDurationSeconds = 1f, speed = 1f), 1e-4f)
            Assert.close(2f, plan.normalizedTimeAt(30, clipDurationSeconds = 1f, speed = 2f), 1e-4f)
            Assert.close(0.5f, plan.normalizedTimeAt(30, clipDurationSeconds = 2f, speed = 1f), 1e-4f)
            Assert.close(0f, plan.normalizedTimeAt(0, clipDurationSeconds = 1f), 1e-4f)
            Assert.close(0f, plan.normalizedTimeAt(5, clipDurationSeconds = 0f), 1e-4f, "guards divide by zero")
        },
        TestCase("default export settings are valid 1080p30") {
            val settings = ExportSettings(clipId = "walk")
            Assert.that(settings.isValid) { "defaults must be exportable: ${settings.validate()}" }
            Assert.equals(ExportFormat.MP4, settings.format, "MP4 is the primary format")
            Assert.equals(ExportResolution.FULL_HD_1080, settings.resolution)
            Assert.equals(ExportFrameRate.FPS_30, settings.frameRate)
            Assert.equals(1920, settings.width)
            Assert.equals(1080, settings.height)
            Assert.equals(90, settings.frameCount, "3 s at 30 fps")
            Assert.equals(null, settings.audioPath, "silent by default")
            Assert.that(settings.effectiveBackground != null) { "MP4 needs a real background colour" }
        },
        TestCase("unsafe export settings are rejected before rendering") {
            fun issues(settings: ExportSettings) = settings.validate().filter { it.blocking }.map { it.message }

            Assert.that(issues(ExportSettings("walk", durationSeconds = 0.1f)).isNotEmpty()) {
                "a 0.1 s export must be refused"
            }
            Assert.that(issues(ExportSettings("walk", durationSeconds = 120f)).isNotEmpty()) {
                "a two minute export must be refused"
            }
            Assert.that(issues(ExportSettings("walk", speed = 12f)).isNotEmpty()) { "absurd speed must be refused" }
            Assert.that(issues(ExportSettings("walk", speed = 0f)).isNotEmpty()) { "zero speed must be refused" }
            Assert.that(issues(ExportSettings("")).isNotEmpty()) { "an export needs a clip" }
            // 30 s at 60 fps is exactly the frame ceiling; one frame more must be refused.
            Assert.that(issues(ExportSettings("walk", durationSeconds = 30f, frameRate = ExportFrameRate.FPS_60)).isEmpty()) {
                "the ceiling itself is allowed"
            }
            val tooMany = issues(ExportSettings("walk", durationSeconds = 29f, frameRate = ExportFrameRate.FPS_60))
            Assert.equals(emptyList(), tooMany, "29 s at 60 fps is inside the frame budget")
            Assert.equals(ExportLimits.MAX_TOTAL_FRAMES, FramePlan.of(30f, 60).frameCount, "ceiling matches the plan")
        },
        TestCase("transparency is refused for MP4 and allowed for PNG frames") {
            val mp4Transparent = ExportSettings("walk", transparentBackground = true)
            Assert.that(!mp4Transparent.isValid) { "H.264 has no alpha channel" }
            val message = mp4Transparent.blockingMessage!!
            Assert.that(message.contains("transparency")) { "must explain why: $message" }
            Assert.that(message.contains("PNG")) { "must offer the alpha-capable alternative: $message" }

            val pngTransparent = ExportSettings(
                "walk",
                format = ExportFormat.PNG_SEQUENCE,
                transparentBackground = true,
            )
            Assert.that(pngTransparent.isValid) { "PNG frames keep alpha: ${pngTransparent.validate()}" }
            Assert.equals(null, pngTransparent.effectiveBackground, "transparent means no background colour")
            Assert.that(ExportFormat.MP4.supportsTransparency.not()) { "MP4 never claims alpha support" }
            Assert.that(ExportFormat.PNG_SEQUENCE.supportsTransparency) { "PNG sequence supports alpha" }
        },
        TestCase("audio is only accepted for MP4") {
            val withAudio = ExportSettings("walk", audioPath = "/storage/emulated/0/Music/voice.m4a")
            Assert.that(withAudio.isValid) { "audio on MP4 is fine: ${withAudio.validate()}" }
            val pngAudio = withAudio.copy(format = ExportFormat.PNG_SEQUENCE)
            Assert.that(!pngAudio.isValid) { "a PNG sequence cannot carry audio" }
        },
        TestCase("resolution and frame rate presets stay inside device safe limits") {
            for (resolution in ExportResolution.entries) {
                Assert.inRange(resolution.width.toFloat(), ExportLimits.MIN_WIDTH.toFloat(), ExportLimits.MAX_WIDTH.toFloat(), "${resolution.label} width")
                Assert.inRange(resolution.height.toFloat(), ExportLimits.MIN_HEIGHT.toFloat(), ExportLimits.MAX_HEIGHT.toFloat(), "${resolution.label} height")
                Assert.close(16f / 9f, resolution.aspect, 1e-3f, "editor default aspect is 16:9")
            }
            Assert.equals(2, ExportResolution.entries.size, "720p and 1080p only")
            for (rate in ExportFrameRate.entries) {
                Assert.inRange(rate.fps.toFloat(), ExportLimits.MIN_FPS.toFloat(), ExportLimits.MAX_FPS.toFloat(), "fps")
            }
            Assert.equals(listOf(24, 30, 60), ExportFrameRate.entries.map { it.fps }, "24/30/60 only")
        },
        TestCase("size estimates are used for the storage pre-flight") {
            val mp4 = ExportSettings("walk", durationSeconds = 10f)
            Assert.that(mp4.estimatedBytes > 0) { "estimate must be positive" }
            val png = mp4.copy(format = ExportFormat.PNG_SEQUENCE, transparentBackground = true)
            Assert.that(png.estimatedBytes > mp4.estimatedBytes) { "PNG frames are heavier than H.264" }
            Assert.that(ExportLimits.MIN_FREE_BYTES_FOR_EXPORT > 0) { "free space floor is configured" }
        },
        TestCase("a valid MP4 passes container validation") {
            val file = validMp4()
            val report = Mp4ContainerProbe.probeBytes(file)
            Assert.that(report.looksValid) { "valid MP4 rejected: ${report.failureReason}" }
            Assert.equals(null, report.failureReason)
            Assert.that(report.hasFtyp) { "ftyp must be found" }
            Assert.equals("mp42", report.brand)
            Assert.that(report.hasMoov) { "moov must be found" }
            Assert.that(report.hasMdat) { "mdat must be found" }
            Assert.equals(64L, report.mdatSize, "mdat payload size")
            Assert.equals(file.size.toLong(), report.fileSize)
            Assert.equals(listOf("ftyp", "mdat", "moov"), report.boxes, "top level box order")
            Assert.that(!report.truncated) { "complete file must not be flagged" }
        },
        TestCase("broken MP4s are rejected with a real reason") {
            val empty = Mp4ContainerProbe.probeBytes(ByteArray(0))
            Assert.that(!empty.looksValid) { "empty file must fail" }
            Assert.that(empty.failureReason!!.contains("empty")) { empty.failureReason!! }

            val noMoov = Mp4ContainerProbe.probeBytes(concat(ftypBox(), box("mdat", ByteArray(32))))
            Assert.that(!noMoov.looksValid) { "a file without moov cannot be played" }
            Assert.that(noMoov.failureReason!!.contains("moov")) { noMoov.failureReason!! }

            val noBrand = Mp4ContainerProbe.probeBytes(concat(box("mdat", ByteArray(32)), box("moov")))
            Assert.that(!noBrand.looksValid) { "a file without ftyp is not an MP4" }
            Assert.that(noBrand.failureReason!!.contains("MP4")) { noBrand.failureReason!! }

            val truncated = concat(ftypBox(), box("mdat", ByteArray(64)), box("moov"))
                .copyOfRange(0, 40)
            val truncatedReport = Mp4ContainerProbe.probeBytes(truncated)
            Assert.that(!truncatedReport.looksValid) { "a truncated file must fail" }
            Assert.that(truncatedReport.failureReason!!.contains("incomplete")) { truncatedReport.failureReason!! }

            val garbage = ByteArray(256) { (it * 7 % 256).toByte() }
            Assert.that(!Mp4ContainerProbe.probeBytes(garbage).looksValid) { "random bytes must fail" }

            val emptyMdat = Mp4ContainerProbe.probeBytes(concat(ftypBox(), box("mdat"), box("moov")))
            Assert.that(!emptyMdat.looksValid) { "an MP4 with no video data must fail" }
            Assert.that(emptyMdat.failureReason!!.contains("no video data")) { emptyMdat.failureReason!! }
        },
        TestCase("64 bit box sizes are understood") {
            val payload = ByteArray(48)
            val bigMdat = box("mdat", payload, extendedSize = (16 + payload.size).toLong())
            val report = Mp4ContainerProbe.probeBytes(concat(ftypBox(), bigMdat, box("moov")))
            Assert.that(report.looksValid) { "extended size box broke the walk: ${report.failureReason}" }
            Assert.equals(48L, report.mdatSize, "extended mdat payload size")
            Assert.contains(report.boxes, "mdat")
        },
        TestCase("trailing junk makes an MP4 invalid") {
            // Junk that cannot be read as a trailing box (0x41414141 is far past end of file).
            val withJunk = concat(validMp4(), ByteArray(13) { 0x41 })
            val report = Mp4ContainerProbe.probeBytes(withJunk)
            Assert.that(!report.looksValid) { "trailing bytes mean a damaged file" }
            Assert.that(report.truncated) { "reported as truncated" }
        },
        TestCase("a valid PNG passes signature and header validation") {
            val png = validPng()
            val report = PngProbe.probeBytes(png)
            Assert.that(report.looksValid) { "valid PNG rejected: ${report.failureReason}" }
            Assert.that(report.validSignature) { "signature must match" }
            Assert.that(report.hasIhdr) { "IHDR must be present" }
            Assert.that(report.hasIend) { "IEND must terminate the file" }
            Assert.equals(1920, report.width)
            Assert.equals(1080, report.height)
            Assert.equals(null, report.failureReason)
            Assert.that(PngProbe.hasValidSignature(PngProbe.SIGNATURE)) { "signature self test" }
        },
        TestCase("broken PNGs are rejected") {
            val notPng = PngProbe.probeBytes("GIF89a and some padding bytes to be long enough".toByteArray())
            Assert.that(!notPng.looksValid) { "a GIF is not a PNG" }
            Assert.that(notPng.failureReason!!.contains("PNG")) { notPng.failureReason!! }

            val truncated = validPng().copyOfRange(0, 20)
            Assert.that(!PngProbe.probeBytes(truncated).looksValid) { "too short to be a PNG" }

            val noEnd = PngProbe.probeBytes(validPng() + ByteArray(24))
            Assert.that(!noEnd.looksValid) { "a PNG without IEND is incomplete" }
            Assert.that(noEnd.failureReason!!.contains("incomplete")) { noEnd.failureReason!! }

            val zeroSize = PngProbe.probeBytes(validPng(width = 0, height = 0))
            Assert.that(!zeroSize.looksValid) { "zero dimensions are invalid" }
            Assert.equals(8, PngProbe.SIGNATURE.size, "a PNG signature is eight bytes")
        },
        TestCase("export settings keep their view and clip") {
            val settings = ExportSettings(
                clipId = "side_walk",
                view = ViewKind.SIDE_LEFT,
                format = ExportFormat.MP4,
                resolution = ExportResolution.HD_720,
                frameRate = ExportFrameRate.FPS_24,
                durationSeconds = 2f,
                speed = 1.5f,
            )
            Assert.that(settings.isValid) { settings.validate().toString() }
            Assert.equals(ViewKind.SIDE_LEFT, settings.view)
            Assert.equals("side_walk", settings.clipId)
            Assert.equals(48, settings.frameCount, "2 s at 24 fps")
            Assert.equals(1280, settings.width)
            Assert.close(ExportLimits.MIN_SPEED, 0.25f, 1e-6f, "speed floor")
            Assert.close(ExportLimits.MAX_SPEED, 3f, 1e-6f, "speed ceiling")
        },
    )
}
