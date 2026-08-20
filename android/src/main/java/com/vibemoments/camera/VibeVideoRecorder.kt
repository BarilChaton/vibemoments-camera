package com.vibemoments.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.nio.ByteBuffer

class VibeVideoRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "VibeVideoRecorder"

        private const val VIDEO_MIME =
            MediaFormat.MIMETYPE_VIDEO_AVC

        private const val VIDEO_BITRATE =
            3_000_000

        private const val VIDEO_FPS =
            30

        private const val VIDEO_I_FRAME_INTERVAL =
            2

        private const val AUDIO_MIME =
            MediaFormat.MIMETYPE_AUDIO_AAC

        private const val AUDIO_SAMPLE_RATE =
            48_000

        private const val AUDIO_CHANNELS =
            1

        private const val AUDIO_BITRATE =
            128_000

        private const val MAX_DURATION_MS =
            30_000L
    }

    private val cameraManager =
        context.getSystemService(
            Context.CAMERA_SERVICE,
        ) as CameraManager

    private val cameraThread =
        HandlerThread(
            "VibeVideoCamera",
        ).apply {
            start()
        }

    private val cameraHandler =
        Handler(
            cameraThread.looper,
        )

    private val encoderThread =
        HandlerThread(
            "VibeVideoEncoder",
        ).apply {
            start()
        }

    private val encoderHandler =
        Handler(
            encoderThread.looper,
        )

    private val audioThread =
        HandlerThread(
            "VibeVideoAudio",
        ).apply {
            start()
        }

    private val audioHandler =
        Handler(
            audioThread.looper,
        )

    private var cameraDevice:
        CameraDevice? = null

    private var captureSession:
        CameraCaptureSession? = null

    /*
     * The repeating Camera2 request used while recording.
     *
     * We retain this builder so zoom can update SCALER_CROP_REGION
     * without restarting the camera session or encoder.
     */
    private var recordingRequestBuilder:
        CaptureRequest.Builder? = null

    /*
     * Physical active sensor area used to calculate Camera2 crop regions.
     */
    private var sensorActiveArray:
        Rect? = null

    /*
     * Maximum digital zoom reported by the current Camera2 device.
     */
    private var maxDigitalZoom =
        1.0f

    @Volatile
    private var currentZoomRatio =
        1.0f

    private var videoEncoder:
        MediaCodec? = null

    private var videoEncoderSurface:
        Surface? = null

    private var audioEncoder:
        MediaCodec? = null

    private var audioRecord:
        AudioRecord? = null

    private var muxer:
        MediaMuxer? = null

    private var muxerStarted =
        false

    private var videoTrackIndex =
        -1

    private var audioTrackIndex =
        -1

    private var outputFile:
        File? = null

    @Volatile
    private var starting =
        false

    @Volatile
    private var recording =
        false

    @Volatile
    private var stopping =
        false

    private var recordingStartedAt =
        0L

    private var recordingStartNs =
        0L

    private var videoEncoderFinished =
        false

    private var audioEncoderFinished =
        false

    private var audioInputEnded =
        false

    private var finalized =
        false

    private var firstVideoPtsUs =
        -1L

    private var firstAudioPtsUs =
        -1L

    private var audioSamplesSubmitted =
        0L

    private var onFinished:
        ((File, Long) -> Unit)? = null

    private var onError:
        ((Exception) -> Unit)? = null

    private val muxerLock =
        Any()

    private val finishLock =
        Any()

    private data class PendingSample(
        val track: String,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private val pendingSamples =
        mutableListOf<PendingSample>()

    @SuppressLint("MissingPermission")
    fun start(
        previewSurface: Surface,
        lens: String,
        onStarted: () -> Unit,
        onFinished: (File, Long) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (
            starting ||
            recording ||
            stopping
        ) {
            Log.w(
                TAG,
                "Recording start ignored because recorder is already busy",
            )

            onError(
                IllegalStateException(
                    "Recording already starting or in progress",
                ),
            )

            return
        }

        starting =
            true

        resetRecordingState()

        this.onFinished =
            onFinished

        this.onError =
            onError

        try {
            val cameraId =
                findCameraId(
                    lens,
                )

            /*
             * Read Camera2 zoom capabilities before opening the device.
             */
            val characteristics =
                cameraManager
                    .getCameraCharacteristics(
                        cameraId,
                    )

            sensorActiveArray =
                characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                )

            maxDigitalZoom =
                characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                ) ?: 1.0f

            currentZoomRatio =
                1.0f

            Log.d(
                TAG,
                "Video zoom range: 1.0x - ${maxDigitalZoom}x",
            )

            val videoSize =
                chooseVideoSize(
                    cameraId,
                )

            Log.d(
                TAG,
                "Starting video recorder",
            )

            Log.d(
                TAG,
                "Camera ID: $cameraId",
            )

            Log.d(
                TAG,
                "Video size: ${videoSize.width}x${videoSize.height}",
            )

            createMuxer(
                cameraId,
            )

            createVideoEncoder(
                videoSize.width,
                videoSize.height,
            )

            createAudioEncoder()
            createAudioRecorder()

            cameraManager.openCamera(
                cameraId,
                object :
                    CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.d(
                            TAG,
                            "Camera2 device opened",
                        )

                        cameraDevice =
                            camera

                        try {
                            createVideoSession(
                                camera,
                                previewSurface,
                                onStarted,
                            )
                        } catch (
                            exception: Exception,
                        ) {
                            fail(
                                exception,
                            )
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.e(
                            TAG,
                            "Camera2 disconnected",
                        )

                        camera.close()

                        cameraDevice =
                            null

                        if (
                            recording &&
                            !stopping
                        ) {
                            Log.w(
                                TAG,
                                "Camera disconnected during recording, finalizing partial video",
                            )

                            stopAfterCameraLoss()

                            return
                        }

                        fail(
                            IllegalStateException(
                                "Camera disconnected",
                            ),
                        )
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        val message =
                            cameraErrorMessage(
                                error,
                            )

                        Log.e(
                            TAG,
                            "Camera2 error $error: $message",
                        )

                        camera.close()

                        cameraDevice =
                            null

                        if (
                            error ==
                            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED &&
                            recording &&
                            !stopping
                        ) {
                            Log.w(
                                TAG,
                                "Camera disabled during recording, finalizing partial video",
                            )

                            stopAfterCameraLoss()

                            return
                        }

                        fail(
                            IllegalStateException(
                                message,
                            ),
                        )
                    }
                },
                cameraHandler,
            )
        } catch (
            exception: Exception,
        ) {
            fail(
                exception,
            )
        }
    }

    private fun cameraErrorMessage(error: Int): String =
        when (
            error
        ) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                "Camera is already in use"

            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                "Maximum number of cameras already in use"

            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                "Camera was disabled by the system"

            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
                "Camera device error"

            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                "Camera service error"

            else ->
                "Unknown camera error: $error"
        }

    fun stopIfRecording() {
        if (
            !recording ||
            stopping
        ) {
            return
        }

        Log.d(
            TAG,
            "Stopping recording because app entered background",
        )

        stop()
    }

    fun isRecording(): Boolean =
        recording

    fun isBusy(): Boolean =
        (
            starting ||
                recording ||
                stopping
        )

    // -------------------------------------------------------------------------
    // Video zoom
    // -------------------------------------------------------------------------

    fun setZoomRatio(ratio: Float): Float {
        if (
            !recording ||
            stopping
        ) {
            throw IllegalStateException(
                "Video recording is not active",
            )
        }

        val clampedRatio =
            ratio.coerceIn(
                1.0f,
                maxDigitalZoom,
            )

        currentZoomRatio =
            clampedRatio

        /*
         * CameraCaptureSession and its request are owned by the camera thread.
         * Post the update there rather than changing them from Capacitor's
         * plugin thread.
         */
        cameraHandler.post {
            try {
                val session =
                    captureSession
                        ?: return@post

                val builder =
                    recordingRequestBuilder
                        ?: return@post

                applyZoomToRequest(
                    builder,
                    clampedRatio,
                )

                session.setRepeatingRequest(
                    builder.build(),
                    null,
                    cameraHandler,
                )

                Log.d(
                    TAG,
                    "Video zoom ratio set to: ${"%.2f".format(clampedRatio)}x",
                )
            } catch (
                exception: Exception,
            ) {
                /*
                 * A failed zoom operation should not destroy an otherwise
                 * valid recording.
                 */
                Log.e(
                    TAG,
                    "Unable to change video zoom",
                    exception,
                )
            }
        }

        return clampedRatio
    }

    fun getZoomRatio(): Float =
        currentZoomRatio

    fun getMinZoomRatio(): Float =
        1.0f

    fun getMaxZoomRatio(): Float =
        maxDigitalZoom

    private fun applyZoomToRequest(
        builder: CaptureRequest.Builder,
        requestedRatio: Float,
    ) {
        val sensorRect =
            sensorActiveArray
                ?: return

        val ratio =
            requestedRatio.coerceIn(
                1.0f,
                maxDigitalZoom,
            )

        if (
            ratio <=
            1.0f
        ) {
            /*
             * Explicitly restore the full active sensor region at 1x.
             */
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                sensorRect,
            )

            return
        }

        val cropWidth =
            (
                sensorRect.width()
                    .toFloat() /
                    ratio
            ).toInt()

        val cropHeight =
            (
                sensorRect.height()
                    .toFloat() /
                    ratio
            ).toInt()

        val left =
            sensorRect.left +
                (
                    sensorRect.width() -
                        cropWidth
                ) / 2

        val top =
            sensorRect.top +
                (
                    sensorRect.height() -
                        cropHeight
                ) / 2

        val cropRegion =
            Rect(
                left,
                top,
                left + cropWidth,
                top + cropHeight,
            )

        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            cropRegion,
        )
    }

    private fun resetRecordingState() {
        recording =
            false

        stopping =
            false

        finalized =
            false

        videoEncoderFinished =
            false

        audioEncoderFinished =
            false

        audioInputEnded =
            false

        videoTrackIndex =
            -1

        audioTrackIndex =
            -1

        muxerStarted =
            false

        firstVideoPtsUs =
            -1L

        firstAudioPtsUs =
            -1L

        audioSamplesSubmitted =
            0L

        recordingStartNs =
            0L

        recordingRequestBuilder =
            null

        sensorActiveArray =
            null

        maxDigitalZoom =
            1.0f

        currentZoomRatio =
            1.0f

        synchronized(
            muxerLock,
        ) {
            pendingSamples.clear()
        }
    }

    private fun getDeviceRotationDegrees(): Int {
        val windowManager =
            context.getSystemService(
                Context.WINDOW_SERVICE,
            ) as WindowManager

        return when (
            windowManager.defaultDisplay.rotation
        ) {
            Surface.ROTATION_90 ->
                90

            Surface.ROTATION_180 ->
                180

            Surface.ROTATION_270 ->
                270

            else ->
                0
        }
    }

    private fun getVideoOrientation(cameraId: String): Int {
        val characteristics =
            cameraManager
                .getCameraCharacteristics(
                    cameraId,
                )

        val sensorOrientation =
            characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ) ?: 0

        val lensFacing =
            characteristics.get(
                CameraCharacteristics.LENS_FACING,
            )

        val deviceRotation =
            getDeviceRotationDegrees()

        val sign =
            if (
                lensFacing ==
                CameraCharacteristics.LENS_FACING_FRONT
            ) {
                1
            } else {
                -1
            }

        return (
            sensorOrientation -
                deviceRotation * sign +
                360
        ) % 360
    }

    private fun createMuxer(cameraId: String) {
        val directory =
            File(
                context.cacheDir,
                "vibemoments-camera",
            )

        if (
            !directory.exists()
        ) {
            directory.mkdirs()
        }

        outputFile =
            File(
                directory,
                "video_${System.currentTimeMillis()}.mp4",
            )

        Log.d(
            TAG,
            "Creating MP4: ${outputFile!!.absolutePath}",
        )

        val orientation =
            getVideoOrientation(
                cameraId,
            )

        muxer =
            MediaMuxer(
                outputFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            ).apply {
                setOrientationHint(
                    orientation,
                )
            }

        Log.d(
            TAG,
            "Video orientation hint: $orientation°",
        )
    }

    private fun createVideoEncoder(
        width: Int,
        height: Int,
    ) {
        Log.d(
            TAG,
            "Creating H.264 encoder ${width}x$height @ $VIDEO_BITRATE bps",
        )

        val format =
            MediaFormat
                .createVideoFormat(
                    VIDEO_MIME,
                    width,
                    height,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        VIDEO_BITRATE,
                    )

                    setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        VIDEO_FPS,
                    )

                    setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        VIDEO_I_FRAME_INTERVAL,
                    )
                }

        videoEncoder =
            MediaCodec
                .createEncoderByType(
                    VIDEO_MIME,
                ).apply {
                    configure(
                        format,
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )

                    videoEncoderSurface =
                        createInputSurface()

                    start()
                }
    }

    private fun createAudioEncoder() {
        Log.d(
            TAG,
            "Creating AAC encoder @ $AUDIO_BITRATE bps",
        )

        val format =
            MediaFormat
                .createAudioFormat(
                    AUDIO_MIME,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        AUDIO_BITRATE,
                    )

                    setInteger(
                        MediaFormat.KEY_MAX_INPUT_SIZE,
                        16 * 1024,
                    )
                }

        audioEncoder =
            MediaCodec
                .createEncoderByType(
                    AUDIO_MIME,
                ).apply {
                    configure(
                        format,
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )

                    start()
                }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecorder() {
        val minimumBufferSize =
            AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (
            minimumBufferSize <=
            0
        ) {
            throw IllegalStateException(
                "Unable to determine audio buffer size",
            )
        }

        audioRecord =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBufferSize * 2,
            )

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {
            throw IllegalStateException(
                "AudioRecord failed to initialize",
            )
        }

        Log.d(
            TAG,
            "AudioRecord initialized",
        )
    }

    private fun findCameraId(lens: String): String {
        val wantedFacing =
            if (
                lens ==
                "front"
            ) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

        for (
        cameraId in
        cameraManager.cameraIdList
        ) {
            val characteristics =
                cameraManager
                    .getCameraCharacteristics(
                        cameraId,
                    )

            val facing =
                characteristics.get(
                    CameraCharacteristics.LENS_FACING,
                )

            if (
                facing ==
                wantedFacing
            ) {
                return cameraId
            }
        }

        throw IllegalStateException(
            "No $lens camera available",
        )
    }

    private fun chooseVideoSize(cameraId: String): Size {
        val characteristics =
            cameraManager
                .getCameraCharacteristics(
                    cameraId,
                )

        val map =
            characteristics.get(
                CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP,
            )
                ?: throw IllegalStateException(
                    "Camera has no stream configuration map",
                )

        val sizes =
            map.getOutputSizes(
                MediaCodec::class.java,
            )
                ?: throw IllegalStateException(
                    "Camera provides no MediaCodec output sizes",
                )

        sizes
            .firstOrNull {
                it.width ==
                    1280 &&
                    it.height ==
                    720
            }?.let {
                return it
            }

        sizes
            .firstOrNull {
                it.width ==
                    1920 &&
                    it.height ==
                    1080
            }?.let {
                return it
            }

        val targetPixels =
            1280L * 720L

        val closest16By9 =
            sizes
                .filter { size ->
                    val ratio =
                        size.width.toDouble() /
                            size.height.toDouble()

                    kotlin.math.abs(
                        ratio -
                            (16.0 / 9.0),
                    ) < 0.05
                }.minByOrNull { size ->
                    kotlin.math.abs(
                        size.width.toLong() *
                            size.height.toLong() -
                            targetPixels,
                    )
                }

        if (
            closest16By9 !=
            null
        ) {
            Log.d(
                TAG,
                "720p unavailable, using closest 16:9 size: ${closest16By9.width}x${closest16By9.height}",
            )

            return closest16By9
        }

        val closest =
            sizes.minByOrNull { size ->
                kotlin.math.abs(
                    size.width.toLong() *
                        size.height.toLong() -
                        targetPixels,
                )
            }
                ?: throw IllegalStateException(
                    "No supported video size",
                )

        Log.d(
            TAG,
            "No 16:9 size available, using closest size: ${closest.width}x${closest.height}",
        )

        return closest
    }

    private fun createVideoSession(
        camera: CameraDevice,
        previewSurface: Surface,
        onStarted: () -> Unit,
    ) {
        val encodeSurface =
            videoEncoderSurface
                ?: throw IllegalStateException(
                    "Video encoder surface unavailable",
                )

        val surfaces =
            listOf(
                previewSurface,
                encodeSurface,
            )

        camera.createCaptureSession(
            surfaces,
            object :
                CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(
                        TAG,
                        "Camera2 video session configured",
                    )

                    captureSession =
                        session

                    try {
                        val request =
                            camera
                                .createCaptureRequest(
                                    CameraDevice.TEMPLATE_RECORD,
                                ).apply {
                                    addTarget(
                                        previewSurface,
                                    )

                                    addTarget(
                                        encodeSurface,
                                    )

                                    set(
                                        CaptureRequest.CONTROL_MODE,
                                        CameraMetadata.CONTROL_MODE_AUTO,
                                    )

                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                    )
                                }

                        recordingRequestBuilder =
                            request

                        applyZoomToRequest(
                            request,
                            currentZoomRatio,
                        )

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            cameraHandler,
                        )

                        recordingStartedAt =
                            System.currentTimeMillis()

                        recordingStartNs =
                            SystemClock.elapsedRealtimeNanos()

                        starting =
                            false

                        recording =
                            true

                        startVideoDrainLoop()
                        startAudioCapture()

                        cameraHandler.postDelayed(
                            {
                                if (
                                    recording
                                ) {
                                    Log.d(
                                        TAG,
                                        "30 second recording limit reached",
                                    )

                                    try {
                                        stop()
                                    } catch (
                                        exception: Exception,
                                    ) {
                                        fail(
                                            exception,
                                        )
                                    }
                                }
                            },
                            MAX_DURATION_MS,
                        )

                        Log.d(
                            TAG,
                            "Video recording started",
                        )

                        onStarted()
                    } catch (
                        exception: Exception,
                    ) {
                        fail(
                            exception,
                        )
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fail(
                        IllegalStateException(
                            "Unable to configure Camera2 video session",
                        ),
                    )
                }
            },
            cameraHandler,
        )
    }

    private fun startVideoDrainLoop() {
        encoderHandler.post(
            object :
                Runnable {
                override fun run() {
                    try {
                        drainVideoEncoder(
                            false,
                        )
                    } catch (
                        exception: Exception,
                    ) {
                        fail(
                            exception,
                        )

                        return
                    }

                    if (
                        recording
                    ) {
                        encoderHandler
                            .postDelayed(
                                this,
                                5,
                            )
                    }
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val recorder =
            audioRecord
                ?: throw IllegalStateException(
                    "AudioRecord unavailable",
                )

        val encoder =
            audioEncoder
                ?: throw IllegalStateException(
                    "Audio encoder unavailable",
                )

        recorder.startRecording()

        Log.d(
            TAG,
            "Microphone recording started",
        )

        audioHandler.post(
            object :
                Runnable {
                override fun run() {
                    if (
                        !recording
                    ) {
                        queueAudioEndOfStream()

                        return
                    }

                    try {
                        val inputIndex =
                            encoder
                                .dequeueInputBuffer(
                                    10_000,
                                )

                        if (
                            inputIndex >=
                            0
                        ) {
                            val inputBuffer =
                                encoder
                                    .getInputBuffer(
                                        inputIndex,
                                    )

                            if (
                                inputBuffer !=
                                null
                            ) {
                                inputBuffer.clear()

                                val bytesRead =
                                    recorder.read(
                                        inputBuffer,
                                        inputBuffer.remaining(),
                                    )

                                if (
                                    bytesRead >
                                    0
                                ) {
                                    val bytesPerSample =
                                        2

                                    val samplesRead =
                                        bytesRead /
                                            bytesPerSample /
                                            AUDIO_CHANNELS

                                    val timestamp =
                                        AudioTimestamp()

                                    val timestampResult =
                                        recorder
                                            .getTimestamp(
                                                timestamp,
                                                AudioTimestamp.TIMEBASE_MONOTONIC,
                                            )

                                    val ptsUs =
                                        if (
                                            timestampResult ==
                                            AudioRecord.SUCCESS
                                        ) {
                                            val bufferDurationNs =
                                                samplesRead *
                                                    1_000_000_000L /
                                                    AUDIO_SAMPLE_RATE

                                            val bufferStartNs =
                                                timestamp.nanoTime -
                                                    bufferDurationNs

                                            (
                                                bufferStartNs -
                                                    recordingStartNs
                                            ).coerceAtLeast(
                                                0L,
                                            ) /
                                                1_000L
                                        } else {
                                            audioSamplesSubmitted *
                                                1_000_000L /
                                                AUDIO_SAMPLE_RATE
                                        }

                                    encoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        bytesRead,
                                        ptsUs,
                                        0,
                                    )

                                    audioSamplesSubmitted +=
                                        samplesRead
                                }
                            }
                        }

                        drainAudioEncoder(
                            false,
                        )

                        if (
                            recording
                        ) {
                            audioHandler.post(
                                this,
                            )
                        }
                    } catch (
                        exception: Exception,
                    ) {
                        fail(
                            exception,
                        )
                    }
                }
            },
        )
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder =
            videoEncoder
                ?: return

        val bufferInfo =
            MediaCodec.BufferInfo()

        if (
            endOfStream
        ) {
            encoder
                .signalEndOfInputStream()
        }

        while (
            true
        ) {
            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    if (
                        endOfStream
                    ) {
                        10_000
                    } else {
                        0
                    },
                )

            when {
                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (
                        !endOfStream
                    ) {
                        break
                    }
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(
                        muxerLock,
                    ) {
                        if (
                            videoTrackIndex >=
                            0
                        ) {
                            throw IllegalStateException(
                                "Video encoder format changed twice",
                            )
                        }

                        val format =
                            encoder.outputFormat

                        Log.d(
                            TAG,
                            "Video encoder format ready: $format",
                        )

                        videoTrackIndex =
                            muxer!!
                                .addTrack(
                                    format,
                                )

                        Log.d(
                            TAG,
                            "Video track added: $videoTrackIndex",
                        )

                        tryStartMuxer()
                    }
                }

                outputIndex >=
                    0 -> {
                    val outputBuffer =
                        encoder
                            .getOutputBuffer(
                                outputIndex,
                            )

                    if (
                        outputBuffer !=
                        null
                    ) {
                        if (
                            bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG !=
                            0
                        ) {
                            bufferInfo.size =
                                0
                        }

                        if (
                            bufferInfo.size >
                            0
                        ) {
                            if (
                                firstVideoPtsUs <
                                0
                            ) {
                                firstVideoPtsUs =
                                    bufferInfo.presentationTimeUs
                            }

                            val normalizedPts =
                                (
                                    bufferInfo.presentationTimeUs -
                                        firstVideoPtsUs
                                ).coerceAtLeast(
                                    0,
                                )

                            writeOrBufferSample(
                                track =
                                    "video",
                                buffer =
                                    outputBuffer,
                                info =
                                    bufferInfo,
                                presentationTimeUs =
                                    normalizedPts,
                            )
                        }
                    }

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false,
                    )

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM !=
                        0
                    ) {
                        Log.d(
                            TAG,
                            "Video encoder EOS reached",
                        )

                        videoEncoderFinished =
                            true

                        maybeFinishRecording()

                        break
                    }
                }
            }
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val encoder =
            audioEncoder
                ?: return

        val bufferInfo =
            MediaCodec.BufferInfo()

        while (
            true
        ) {
            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    if (
                        endOfStream
                    ) {
                        10_000
                    } else {
                        0
                    },
                )

            when {
                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (
                        !endOfStream
                    ) {
                        break
                    }
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(
                        muxerLock,
                    ) {
                        if (
                            audioTrackIndex >=
                            0
                        ) {
                            throw IllegalStateException(
                                "Audio encoder format changed twice",
                            )
                        }

                        val format =
                            encoder.outputFormat

                        Log.d(
                            TAG,
                            "Audio encoder format ready: $format",
                        )

                        audioTrackIndex =
                            muxer!!
                                .addTrack(
                                    format,
                                )

                        Log.d(
                            TAG,
                            "Audio track added: $audioTrackIndex",
                        )

                        tryStartMuxer()
                    }
                }

                outputIndex >=
                    0 -> {
                    val outputBuffer =
                        encoder
                            .getOutputBuffer(
                                outputIndex,
                            )

                    if (
                        outputBuffer !=
                        null &&
                        bufferInfo.size >
                        0
                    ) {
                        if (
                            firstAudioPtsUs <
                            0
                        ) {
                            firstAudioPtsUs =
                                bufferInfo.presentationTimeUs
                        }

                        val normalizedPts =
                            (
                                bufferInfo.presentationTimeUs -
                                    firstAudioPtsUs
                            ).coerceAtLeast(
                                0,
                            )

                        writeOrBufferSample(
                            track =
                                "audio",
                            buffer =
                                outputBuffer,
                            info =
                                bufferInfo,
                            presentationTimeUs =
                                normalizedPts,
                        )
                    }

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false,
                    )

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM !=
                        0
                    ) {
                        Log.d(
                            TAG,
                            "Audio encoder EOS reached",
                        )

                        audioEncoderFinished =
                            true

                        maybeFinishRecording()

                        break
                    }
                }
            }
        }
    }

    private fun writeOrBufferSample(
        track: String,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        presentationTimeUs: Long,
    ) {
        synchronized(
            muxerLock,
        ) {
            buffer.position(
                info.offset,
            )

            buffer.limit(
                info.offset +
                    info.size,
            )

            if (
                !muxerStarted
            ) {
                val bytes =
                    ByteArray(
                        info.size,
                    )

                buffer.get(
                    bytes,
                )

                pendingSamples.add(
                    PendingSample(
                        track =
                            track,
                        data =
                            bytes,
                        presentationTimeUs =
                            presentationTimeUs,
                        flags =
                            info.flags,
                    ),
                )

                return
            }

            val trackIndex =
                if (
                    track ==
                    "video"
                ) {
                    videoTrackIndex
                } else {
                    audioTrackIndex
                }

            val writeInfo =
                MediaCodec
                    .BufferInfo()
                    .apply {
                        set(
                            0,
                            info.size,
                            presentationTimeUs,
                            info.flags,
                        )
                    }

            muxer!!
                .writeSampleData(
                    trackIndex,
                    buffer,
                    writeInfo,
                )
        }
    }

    private fun tryStartMuxer() {
        if (
            muxerStarted
        ) {
            return
        }

        if (
            videoTrackIndex <
            0 ||
            audioTrackIndex <
            0
        ) {
            return
        }

        muxer!!.start()

        muxerStarted =
            true

        Log.d(
            TAG,
            "MediaMuxer started with video + audio",
        )

        flushPendingSamples()
    }

    private fun flushPendingSamples() {
        if (
            !muxerStarted
        ) {
            return
        }

        val samples =
            pendingSamples
                .toList()

        pendingSamples.clear()

        samples.forEach { sample ->

            val trackIndex =
                if (
                    sample.track ==
                    "video"
                ) {
                    videoTrackIndex
                } else {
                    audioTrackIndex
                }

            val info =
                MediaCodec
                    .BufferInfo()
                    .apply {
                        set(
                            0,
                            sample.data.size,
                            sample.presentationTimeUs,
                            sample.flags,
                        )
                    }

            muxer!!
                .writeSampleData(
                    trackIndex,
                    ByteBuffer.wrap(
                        sample.data,
                    ),
                    info,
                )
        }
    }

    fun stop() {
        if (
            !recording
        ) {
            throw IllegalStateException(
                "No recording in progress",
            )
        }

        if (
            stopping
        ) {
            return
        }

        Log.d(
            TAG,
            "Stopping video recording",
        )

        stopping =
            true

        recording =
            false

        cameraHandler
            .removeCallbacksAndMessages(
                null,
            )

        closeCameraSession()

        audioHandler.post {
            try {
                queueAudioEndOfStream()
            } catch (
                exception: Exception,
            ) {
                fail(
                    exception,
                )
            }
        }

        encoderHandler.post {
            try {
                drainVideoEncoder(
                    true,
                )
            } catch (
                exception: Exception,
            ) {
                fail(
                    exception,
                )
            }
        }
    }

    private fun stopAfterCameraLoss() {
        if (
            !recording ||
            stopping
        ) {
            return
        }

        Log.d(
            TAG,
            "Gracefully stopping after camera loss",
        )

        stopping =
            true

        recording =
            false

        cameraHandler
            .removeCallbacksAndMessages(
                null,
            )

        closeCameraSession()

        audioHandler.post {
            try {
                queueAudioEndOfStream()
            } catch (
                exception: Exception,
            ) {
                fail(
                    exception,
                )
            }
        }

        encoderHandler.post {
            try {
                drainVideoEncoder(
                    true,
                )
            } catch (
                exception: Exception,
            ) {
                fail(
                    exception,
                )
            }
        }
    }

    private fun closeCameraSession() {
        try {
            captureSession
                ?.stopRepeating()
        } catch (
            _: Exception,
        ) {
        }

        try {
            captureSession
                ?.abortCaptures()
        } catch (
            _: Exception,
        ) {
        }

        try {
            captureSession
                ?.close()
        } catch (
            _: Exception,
        ) {
        }

        captureSession =
            null

        recordingRequestBuilder =
            null

        try {
            cameraDevice
                ?.close()
        } catch (
            _: Exception,
        ) {
        }

        cameraDevice =
            null
    }

    private fun queueAudioEndOfStream() {
        if (
            audioInputEnded
        ) {
            return
        }

        audioInputEnded =
            true

        try {
            if (
                audioRecord
                    ?.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                audioRecord
                    ?.stop()
            }
        } catch (
            _: Exception,
        ) {
        }

        val encoder =
            audioEncoder
                ?: run {
                    audioEncoderFinished =
                        true

                    maybeFinishRecording()

                    return
                }

        var eosQueued =
            false

        while (
            !eosQueued
        ) {
            val inputIndex =
                encoder.dequeueInputBuffer(
                    10_000,
                )

            if (
                inputIndex >=
                0
            ) {
                val ptsUs =
                    audioSamplesSubmitted *
                        1_000_000L /
                        AUDIO_SAMPLE_RATE

                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )

                eosQueued =
                    true
            }
        }

        drainAudioEncoder(
            true,
        )
    }

    private fun maybeFinishRecording() {
        synchronized(
            finishLock,
        ) {
            if (
                finalized
            ) {
                return
            }

            if (
                !videoEncoderFinished
            ) {
                return
            }

            if (
                !audioEncoderFinished
            ) {
                return
            }

            finalized =
                true
        }

        val duration =
            System.currentTimeMillis() -
                recordingStartedAt

        finishRecording(
            duration,
        )
    }

    private fun finishRecording(durationMs: Long) {
        Log.d(
            TAG,
            "Finalizing video",
        )

        releaseRecordingResources(
            stopMuxer =
                true,
        )

        val file =
            outputFile

        outputFile =
            null

        if (
            file ==
            null ||
            !file.exists() ||
            file.length() ==
            0L
        ) {
            fail(
                IllegalStateException(
                    "Video output file was not created",
                ),
            )

            return
        }

        Log.d(
            TAG,
            "Video saved: ${file.absolutePath}",
        )

        Log.d(
            TAG,
            "Duration: ${durationMs}ms",
        )

        Log.d(
            TAG,
            "File size: ${file.length()} bytes",
        )

        starting =
            false

        stopping =
            false

        val finishedCallback =
            onFinished

        onFinished =
            null

        onError =
            null

        finishedCallback?.invoke(
            file,
            durationMs,
        )
    }

    private fun releaseRecordingResources(stopMuxer: Boolean) {
        try {
            videoEncoder
                ?.stop()
        } catch (
            _: Exception,
        ) {
        }

        try {
            videoEncoder
                ?.release()
        } catch (
            _: Exception,
        ) {
        }

        videoEncoder =
            null

        try {
            videoEncoderSurface
                ?.release()
        } catch (
            _: Exception,
        ) {
        }

        videoEncoderSurface =
            null

        try {
            if (
                audioRecord
                    ?.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                audioRecord
                    ?.stop()
            }
        } catch (
            _: Exception,
        ) {
        }

        try {
            audioRecord
                ?.release()
        } catch (
            _: Exception,
        ) {
        }

        audioRecord =
            null

        try {
            audioEncoder
                ?.stop()
        } catch (
            _: Exception,
        ) {
        }

        try {
            audioEncoder
                ?.release()
        } catch (
            _: Exception,
        ) {
        }

        audioEncoder =
            null

        synchronized(
            muxerLock,
        ) {
            if (
                stopMuxer
            ) {
                try {
                    if (
                        muxerStarted
                    ) {
                        muxer
                            ?.stop()
                    }
                } catch (
                    exception: Exception,
                ) {
                    Log.e(
                        TAG,
                        "Unable to stop muxer cleanly",
                        exception,
                    )
                }
            }

            try {
                muxer
                    ?.release()
            } catch (
                _: Exception,
            ) {
            }

            muxer =
                null

            muxerStarted =
                false
        }
    }

    private fun fail(exception: Exception) {
        Log.e(
            TAG,
            "Video recorder failed",
            exception,
        )

        starting =
            false

        recording =
            false

        stopping =
            false

        closeCameraSession()

        releaseRecordingResources(
            stopMuxer =
                true,
        )

        synchronized(
            muxerLock,
        ) {
            pendingSamples.clear()
        }

        outputFile?.let {
            if (
                it.exists()
            ) {
                try {
                    it.delete()
                } catch (
                    _: Exception,
                ) {
                }
            }
        }

        outputFile =
            null

        val errorCallback =
            onError

        onFinished =
            null

        onError =
            null

        errorCallback?.invoke(
            exception,
        )
    }
}