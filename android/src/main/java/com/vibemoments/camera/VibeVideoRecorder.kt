package com.vibemoments.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class VibeVideoRecorder(
    private val context: Context
) {
    companion object {
        private const val TAG = "VibeVideoRecorder"

        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val VIDEO_BITRATE = 5_000_000
        private const val VIDEO_FPS = 30
        private const val VIDEO_I_FRAME_INTERVAL = 2
        private const val MAX_DURATION_MS = 30_000L
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("VibeVideoCamera").apply {
        start()
    }

    private val cameraHandler = Handler(cameraThread.looper)

    private val encoderThread = HandlerThread("VibeVideoEncoder").apply {
        start()
    }

    private val encoderHandler = Handler(encoderThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1

    private var outputFile: File? = null

    private var recording = false
    private var recordingStartedAt = 0L

    private var onFinished: ((File, Long) -> Unit)? = null
    private var onError: ((Exception) -> Unit)? = null


    private fun createVideoEncoder(
        width: Int,
        height: Int
    ) {
        Log.d(
            TAG,
            "Creating H.264 encoder ${width}x${height} @ $VIDEO_BITRATE bps"
        )

        val format = MediaFormat.createVideoFormat(
            VIDEO_MIME,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            setInteger(
                MediaFormat.KEY_BIT_RATE,
                VIDEO_BITRATE
            )

            setInteger(
                MediaFormat.KEY_FRAME_RATE,
                VIDEO_FPS
            )

            setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                VIDEO_I_FRAME_INTERVAL
            )
        }

        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
            configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            encoderSurface = createInputSurface()

            start()
        }
    }

    private fun createMuxer() {
        val directory = File(
            context.cacheDir,
            "vibemoments-camera"
        )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        outputFile = File(
            directory,
            "video_${System.currentTimeMillis()}.mp4"
        )

        Log.d(
            TAG,
            "Creating MP4: ${outputFile!!.absolutePath}"
        )

        muxer = MediaMuxer(
            outputFile!!.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        muxerStarted = false
        videoTrackIndex = -1
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) 10_000 else 0
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        break
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException(
                            "Video encoder format changed twice"
                        )
                    }

                    val newFormat = encoder.outputFormat

                    Log.d(
                        TAG,
                        "Video encoder format ready: $newFormat"
                    )

                    videoTrackIndex =
                        muxer!!.addTrack(newFormat)

                    muxer!!.start()

                    muxerStarted = true

                    Log.d(TAG, "MediaMuxer started")
                }

                outputIndex >= 0 -> {
                    val outputBuffer: ByteBuffer =
                        encoder.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException(
                                "Encoder output buffer was null"
                            )

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    ) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            throw IllegalStateException(
                                "Muxer has not started"
                            )
                        }

                        outputBuffer.position(
                            bufferInfo.offset
                        )

                        outputBuffer.limit(
                            bufferInfo.offset + bufferInfo.size
                        )

                        muxer!!.writeSampleData(
                            videoTrackIndex,
                            outputBuffer,
                            bufferInfo
                        )
                    }

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ) {
                        Log.d(TAG, "Video encoder EOS reached")
                        break
                    }
                }
            }
        }
    }

    private fun findCameraId(
        lens: String
    ): String {
        val wantedFacing =
            if (lens == "front") {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            } else {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics =
                cameraManager.getCameraCharacteristics(cameraId)

            val facing =
                characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                )

            if (facing == wantedFacing) {
                return cameraId
            }
        }

        throw IllegalStateException(
            "No $lens camera available"
        )
    }

    private fun chooseVideoSize(
        cameraId: String
    ): Size {
        val characteristics =
            cameraManager.getCameraCharacteristics(cameraId)

        val map =
            characteristics.get(
                android.hardware.camera2.CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP
            ) ?: throw IllegalStateException(
                "Camera has no stream configuration map"
            )

        val sizes =
            map.getOutputSizes(MediaCodec::class.java)
                ?: throw IllegalStateException(
                    "Camera provides no MediaCodec output sizes"
                )

        val preferred = sizes.firstOrNull {
            it.width == 1920 &&
            it.height == 1080
        }

        if (preferred != null) {
            return preferred
        }

        val fallback720 = sizes.firstOrNull {
            it.width == 1280 &&
            it.height == 720
        }

        if (fallback720 != null) {
            return fallback720
        }

        return sizes.maxByOrNull {
            it.width * it.height
        } ?: throw IllegalStateException(
            "No supported video size"
        )
    }
}