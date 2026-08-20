package com.vibemoments.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File

@CapacitorPlugin(
    name = "VibeCamera",
    permissions = [
        Permission(
            alias = "camera",
            strings = [Manifest.permission.CAMERA],
        ),
        Permission(
            alias = "microphone",
            strings = [Manifest.permission.RECORD_AUDIO],
        ),
    ],
)
class VibeCameraPlugin : Plugin() {
    companion object {
        /*
         * This matches the primary recording resolution currently used by
         * VibeVideoRecorder.
         *
         * The SurfaceTexture needs to know the source buffer dimensions so
         * Android does not simply stretch a camera frame into the dimensions
         * of the full-screen TextureView.
         */
        private const val VIDEO_PREVIEW_WIDTH = 1280
        private const val VIDEO_PREVIEW_HEIGHT = 720

        private const val CAPTURE_PROOF_VERSION = "vibemoments-capture-v1"
        private const val CAPTURE_SIGNATURE_ALGORITHM = "ECDSA_P256_SHA256"
    }

    private val logTag = "VibeCamera"

    private lateinit var cameraManager: VibeCameraManager
    private lateinit var captureSigner: CaptureSigner

    private var previewView: PreviewView? = null
    private var previewContainer: FrameLayout? = null

    private var videoPreviewView: android.view.TextureView? = null
    private var videoPreviewSurface: Surface? = null

    private var videoRecorder: VibeVideoRecorder? = null

    private var pendingVideoCall: PluginCall? = null
    private var recordingStartPending = false

    /*
     * Capture proof associated with the currently active video recording.
     *
     * Video recording is asynchronous, so these values must survive from
     * startRecording() until the MP4 has been completely finalized.
     */
    private var activeVideoCaptureSessionId: String? = null
    private var activeVideoNonce: String? = null

    private var appPaused = false
    private var cameraXNeedsRestore = false

    override fun load() {
        super.load()

        Log.d(
            logTag,
            "Plugin loaded",
        )

        cameraManager =
            VibeCameraManager(
                context,
                activity,
                ContextCompat.getMainExecutor(context),
            )

        videoRecorder =
            VibeVideoRecorder(
                context,
            )

        captureSigner =
            CaptureSigner(
                context,
            )

        Log.d(
            logTag,
            "Capture signer initialized",
        )
    }

    override fun handleOnPause() {
        super.handleOnPause()

        appPaused = true

        Log.d(
            logTag,
            "App paused",
        )

        try {
            videoRecorder?.stopIfRecording()
        } catch (exception: Exception) {
            Log.e(
                logTag,
                "Unable to stop recording while app is pausing",
                exception,
            )
        }
    }

    override fun handleOnResume() {
        super.handleOnResume()

        appPaused = false

        Log.d(
            logTag,
            "App resumed",
        )

        if (
            cameraXNeedsRestore &&
            previewView != null
        ) {
            restoreCameraXPreview()
        }
    }

    // -------------------------------------------------------------------------
    // Capture identity
    // -------------------------------------------------------------------------

    @PluginMethod
    fun getCaptureIdentity(call: PluginCall) {
        try {
            val deviceId =
                captureSigner.getDeviceId()

            val publicKey =
                captureSigner.getPublicKeyBase64()

            val result =
                JSObject()

            result.put(
                "deviceId",
                deviceId,
            )

            result.put(
                "publicKey",
                publicKey,
            )

            result.put(
                "algorithm",
                CAPTURE_SIGNATURE_ALGORITHM,
            )

            result.put(
                "proofVersion",
                CAPTURE_PROOF_VERSION,
            )

            Log.d(
                logTag,
                "Returning capture identity for device $deviceId",
            )

            call.resolve(
                result,
            )
        } catch (
            exception: Exception,
        ) {
            Log.e(
                logTag,
                "Unable to get capture identity",
                exception,
            )

            call.reject(
                "Unable to get capture identity",
                exception,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------

    @PluginMethod
    fun startPreview(call: PluginCall) {
        Log.d(
            logTag,
            "startPreview called",
        )

        if (previewView != null) {
            Log.d(
                logTag,
                "Camera preview already active",
            )

            val result =
                JSObject()

            result.put(
                "active",
                true,
            )

            result.put(
                "lens",
                cameraManager.currentLens(),
            )

            call.resolve(
                result,
            )

            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(
                logTag,
                "Camera permission missing, requesting",
            )

            requestPermissionForAlias(
                "camera",
                call,
                "cameraPermissionCallback",
            )

            return
        }

        openPreview(
            call,
        )
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                logTag,
                "Camera permission denied",
            )

            call.reject(
                "Camera permission denied",
            )

            return
        }

        Log.d(
            logTag,
            "Camera permission granted",
        )

        openPreview(
            call,
        )
    }

    private fun openPreview(call: PluginCall) {
        Log.d(
            logTag,
            "openPreview called",
        )

        activity.runOnUiThread {
            try {
                val webView =
                    bridge.webView

                val webViewParent =
                    webView.parent as? ViewGroup

                if (
                    webViewParent == null
                ) {
                    call.reject(
                        "Unable to access WebView parent",
                    )

                    return@runOnUiThread
                }

                if (
                    previewContainer == null
                ) {
                    Log.d(
                        logTag,
                        "Creating camera preview behind WebView",
                    )

                    previewContainer =
                        FrameLayout(context).apply {
                            layoutParams =
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                        }

                    previewView =
                        PreviewView(context).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                )

                            scaleType =
                                PreviewView.ScaleType.FILL_CENTER

                            implementationMode =
                                PreviewView.ImplementationMode.COMPATIBLE
                        }

                    previewContainer!!
                        .addView(
                            previewView,
                        )

                    val webViewIndex =
                        webViewParent.indexOfChild(
                            webView,
                        )

                    webViewParent.addView(
                        previewContainer,
                        webViewIndex,
                    )

                    webView.setBackgroundColor(
                        Color.TRANSPARENT,
                    )

                    webViewParent.setBackgroundColor(
                        Color.TRANSPARENT,
                    )

                    webView.bringToFront()
                }

                Log.d(
                    logTag,
                    "Starting CameraX preview",
                )

                cameraManager.startPreview(
                    previewView!!,
                    onReady = {
                        Log.d(
                            logTag,
                            "Camera preview ready",
                        )

                        val result =
                            JSObject()

                        result.put(
                            "active",
                            true,
                        )

                        result.put(
                            "lens",
                            cameraManager.currentLens(),
                        )

                        call.resolve(
                            result,
                        )
                    },
                    onError = { exception ->
                        Log.e(
                            logTag,
                            "Unable to start camera",
                            exception,
                        )

                        call.reject(
                            "Unable to start camera",
                            exception,
                        )
                    },
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to create camera preview",
                    exception,
                )

                call.reject(
                    "Unable to create camera preview",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun stopPreview(call: PluginCall) {
        Log.d(
            logTag,
            "stopPreview called",
        )

        activity.runOnUiThread {
            try {
                videoRecorder?.stopIfRecording()
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to stop active recording",
                    exception,
                )
            }

            cameraXNeedsRestore = false

            cameraManager.stopPreview()

            clearVideoPreview()

            previewContainer?.let {
                (it.parent as? ViewGroup)
                    ?.removeView(it)
            }

            previewView =
                null

            previewContainer =
                null

            bridge.webView.setBackgroundColor(
                Color.WHITE,
            )

            call.resolve()
        }
    }

    // -------------------------------------------------------------------------
    // Photo
    // -------------------------------------------------------------------------

    @PluginMethod
    fun capturePhoto(call: PluginCall) {
        Log.d(
            logTag,
            "capturePhoto called",
        )

        val captureSessionId =
            call.getString(
                "captureSessionId",
            )

        val nonce =
            call.getString(
                "nonce",
            )

        if (
            captureSessionId.isNullOrBlank() ||
            nonce.isNullOrBlank()
        ) {
            Log.e(
                logTag,
                "Photo capture rejected because capture proof is missing",
            )

            call.reject(
                "Missing capture session proof",
            )

            return
        }

        Log.d(
            logTag,
            "Capture session ID: $captureSessionId",
        )

        cameraManager.capturePhoto(
            onSuccess = { file ->
                Log.d(
                    logTag,
                    "Photo captured: ${file.absolutePath}",
                )

                try {
                    val sha256 =
                        CaptureHasher.sha256(
                            file,
                        )

                    Log.d(
                        logTag,
                        "Photo SHA-256: $sha256",
                    )

                    val deviceId =
                        captureSigner.getDeviceId()

                    val captureSignature =
                        captureSigner.signCapture(
                            captureSessionId = captureSessionId,
                            nonce = nonce,
                            mediaType = "photo",
                            sha256 = sha256,
                        )

                    Log.d(
                        logTag,
                        "Photo capture signature created for device $deviceId",
                    )

                    val result =
                        JSObject()

                    result.put(
                        "type",
                        "photo",
                    )

                    result.put(
                        "path",
                        file.absolutePath,
                    )

                    result.put(
                        "mimeType",
                        "image/jpeg",
                    )

                    result.put(
                        "lens",
                        cameraManager.currentLens(),
                    )

                    result.put(
                        "sha256",
                        sha256,
                    )

                    result.put(
                        "captureSessionId",
                        captureSessionId,
                    )

                    result.put(
                        "nonce",
                        nonce,
                    )

                    result.put(
                        "deviceId",
                        deviceId,
                    )

                    result.put(
                        "captureSignature",
                        captureSignature,
                    )

                    result.put(
                        "proofVersion",
                        CAPTURE_PROOF_VERSION,
                    )

                    result.put(
                        "signatureAlgorithm",
                        CAPTURE_SIGNATURE_ALGORITHM,
                    )

                    Log.d(
                        logTag,
                        "Returning signed photo capture proof: session=$captureSessionId",
                    )

                    call.resolve(
                        result,
                    )
                } catch (
                    exception: Exception,
                ) {
                    Log.e(
                        logTag,
                        "Unable to process captured photo",
                        exception,
                    )

                    call.reject(
                        "Unable to process captured photo",
                        exception,
                    )
                }
            },
            onError = { exception ->
                Log.e(
                    logTag,
                    "Photo capture failed",
                    exception,
                )

                call.reject(
                    "Unable to capture photo",
                    exception,
                )
            },
        )
    }

    // -------------------------------------------------------------------------
    // Camera controls
    // -------------------------------------------------------------------------

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        Log.d(
            logTag,
            "switchCamera called",
        )

        if (
            videoRecorder?.isRecording() == true
        ) {
            call.reject(
                "Cannot switch camera while recording",
            )

            return
        }

        activity.runOnUiThread {
            try {
                val lens =
                    cameraManager.switchCamera()

                Log.d(
                    logTag,
                    "Camera switched to $lens",
                )

                val result =
                    JSObject()

                result.put(
                    "lens",
                    lens,
                )

                result.put(
                    "zoomRatio",
                    cameraManager.currentZoomRatio(),
                )

                result.put(
                    "minZoomRatio",
                    cameraManager.minZoomRatio(),
                )

                result.put(
                    "maxZoomRatio",
                    cameraManager.maxZoomRatio(),
                )

                call.resolve(
                    result,
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to switch camera",
                    exception,
                )

                call.reject(
                    "Unable to switch camera",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun getCameraState(call: PluginCall) {
        val recorder =
            videoRecorder

        val recording =
            recorder?.isRecording() == true

        val result =
            JSObject()

        result.put(
            "active",
            previewView != null,
        )

        result.put(
            "lens",
            cameraManager.currentLens(),
        )

        result.put(
            "recording",
            recording,
        )

        result.put(
            "zoomRatio",
            if (
                recording
            ) {
                recorder!!.getZoomRatio()
            } else {
                cameraManager.currentZoomRatio()
            },
        )

        call.resolve(
            result,
        )
    }

    // -------------------------------------------------------------------------
    // Zoom
    // -------------------------------------------------------------------------

    @PluginMethod
    fun setZoomRatio(call: PluginCall) {
        val ratio =
            call.getDouble(
                "ratio",
            )

        if (
            ratio == null ||
            ratio <= 0.0
        ) {
            call.reject(
                "Invalid zoom ratio",
            )

            return
        }

        Log.d(
            logTag,
            "setZoomRatio called: $ratio",
        )

        activity.runOnUiThread {
            try {
                val recorder =
                    videoRecorder

                val recording =
                    recorder?.isRecording() == true

                val appliedRatio =
                    if (
                        recording
                    ) {
                        Log.d(
                            logTag,
                            "Routing zoom to Camera2 video recorder",
                        )

                        recorder!!.setZoomRatio(
                            ratio.toFloat(),
                        )
                    } else {
                        Log.d(
                            logTag,
                            "Routing zoom to CameraX preview",
                        )

                        cameraManager.setZoomRatio(
                            ratio.toFloat(),
                        )
                    }

                val result =
                    JSObject()

                result.put(
                    "ratio",
                    appliedRatio,
                )

                result.put(
                    "minRatio",
                    if (
                        recording
                    ) {
                        recorder!!.getMinZoomRatio()
                    } else {
                        cameraManager.minZoomRatio()
                    },
                )

                result.put(
                    "maxRatio",
                    if (
                        recording
                    ) {
                        recorder!!.getMaxZoomRatio()
                    } else {
                        cameraManager.maxZoomRatio()
                    },
                )

                result.put(
                    "recording",
                    recording,
                )

                call.resolve(
                    result,
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to set zoom ratio",
                    exception,
                )

                call.reject(
                    "Unable to set zoom ratio",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun getZoomState(call: PluginCall) {
        Log.d(
            logTag,
            "getZoomState called",
        )

        activity.runOnUiThread {
            try {
                val recorder =
                    videoRecorder

                val recording =
                    recorder?.isRecording() == true

                val result =
                    JSObject()

                if (
                    recording
                ) {
                    result.put(
                        "ratio",
                        recorder!!.getZoomRatio(),
                    )

                    result.put(
                        "minRatio",
                        recorder.getMinZoomRatio(),
                    )

                    result.put(
                        "maxRatio",
                        recorder.getMaxZoomRatio(),
                    )
                } else {
                    result.put(
                        "ratio",
                        cameraManager.currentZoomRatio(),
                    )

                    result.put(
                        "minRatio",
                        cameraManager.minZoomRatio(),
                    )

                    result.put(
                        "maxRatio",
                        cameraManager.maxZoomRatio(),
                    )
                }

                result.put(
                    "recording",
                    recording,
                )

                call.resolve(
                    result,
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to get zoom state",
                    exception,
                )

                call.reject(
                    "Unable to get zoom state",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun setTorch(call: PluginCall) {
        val enabled =
            call.getBoolean(
                "enabled",
            ) ?: false

        Log.d(
            logTag,
            "setTorch called: $enabled",
        )

        activity.runOnUiThread {
            try {
                cameraManager.setTorch(
                    enabled,
                )

                val result =
                    JSObject()

                result.put(
                    "enabled",
                    enabled,
                )

                call.resolve(
                    result,
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to set torch",
                    exception,
                )

                call.reject(
                    "Unable to set torch",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode =
            call.getString(
                "mode",
            ) ?: "off"

        Log.d(
            logTag,
            "setFlashMode called: $mode",
        )

        activity.runOnUiThread {
            try {
                val resultMode =
                    cameraManager.setFlashMode(
                        mode,
                    )

                val result =
                    JSObject()

                result.put(
                    "mode",
                    resultMode,
                )

                call.resolve(
                    result,
                )
            } catch (
                exception: Exception,
            ) {
                Log.e(
                    logTag,
                    "Unable to set flash mode",
                    exception,
                )

                call.reject(
                    "Unable to set flash mode",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun getCapabilities(call: PluginCall) {
        val recorder =
            videoRecorder

        val recording =
            recorder?.isRecording() == true

        val result =
            JSObject()

        result.put(
            "hasFlash",
            cameraManager.hasFlash(),
        )

        result.put(
            "lens",
            cameraManager.currentLens(),
        )

        result.put(
            "recording",
            recording,
        )

        result.put(
            "zoomRatio",
            if (
                recording
            ) {
                recorder!!.getZoomRatio()
            } else {
                cameraManager.currentZoomRatio()
            },
        )

        result.put(
            "minZoomRatio",
            if (
                recording
            ) {
                recorder!!.getMinZoomRatio()
            } else {
                cameraManager.minZoomRatio()
            },
        )

        result.put(
            "maxZoomRatio",
            if (
                recording
            ) {
                recorder!!.getMaxZoomRatio()
            } else {
                cameraManager.maxZoomRatio()
            },
        )

        call.resolve(
            result,
        )
    }

    // -------------------------------------------------------------------------
    // Temporary capture cleanup
    // -------------------------------------------------------------------------

    @PluginMethod
    fun deleteCapture(call: PluginCall) {
        val path =
            call.getString(
                "path",
            )
                ?: run {
                    call.reject(
                        "Missing capture path",
                    )

                    return
                }

        try {
            val file =
                if (
                    path.startsWith(
                        "file://",
                    )
                ) {
                    val parsedPath =
                        Uri
                            .parse(
                                path,
                            ).path
                            ?: throw IllegalArgumentException(
                                "Invalid capture URI",
                            )

                    File(
                        parsedPath,
                    )
                } else {
                    File(
                        path,
                    )
                }

            val cameraCacheDirectory =
                File(
                    context.cacheDir,
                    "vibemoments-camera",
                ).canonicalFile

            val captureFile =
                file.canonicalFile

            val parent =
                captureFile.parentFile

            if (
                parent == null ||
                parent != cameraCacheDirectory
            ) {
                Log.w(
                    logTag,
                    "Refusing to delete file outside camera cache: ${captureFile.absolutePath}",
                )

                call.reject(
                    "Capture is not inside the VibeCamera cache",
                )

                return
            }

            if (
                !captureFile.exists()
            ) {
                Log.d(
                    logTag,
                    "Capture already deleted: ${captureFile.absolutePath}",
                )

                val result =
                    JSObject()

                result.put(
                    "deleted",
                    false,
                )

                result.put(
                    "alreadyDeleted",
                    true,
                )

                call.resolve(
                    result,
                )

                return
            }

            val deleted =
                captureFile.delete()

            if (
                !deleted
            ) {
                call.reject(
                    "Unable to delete capture",
                )

                return
            }

            Log.d(
                logTag,
                "Deleted temporary capture: ${captureFile.absolutePath}",
            )

            val result =
                JSObject()

            result.put(
                "deleted",
                true,
            )

            result.put(
                "alreadyDeleted",
                false,
            )

            call.resolve(
                result,
            )
        } catch (
            exception: Exception,
        ) {
            Log.e(
                logTag,
                "Unable to delete temporary capture",
                exception,
            )

            call.reject(
                "Unable to delete capture",
                exception,
            )
        }
    }

    @PluginMethod
    fun clearCache(call: PluginCall) {
        try {
            val directory =
                File(
                    context.cacheDir,
                    "vibemoments-camera",
                )

            if (
                !directory.exists()
            ) {
                val result =
                    JSObject()

                result.put(
                    "deleted",
                    0,
                )

                call.resolve(
                    result,
                )

                return
            }

            var deletedCount =
                0

            directory
                .listFiles()
                ?.forEach { file ->
                    if (
                        file.isFile &&
                        file.delete()
                    ) {
                        deletedCount++
                    }
                }

            Log.d(
                logTag,
                "Cleared $deletedCount temporary camera files",
            )

            val result =
                JSObject()

            result.put(
                "deleted",
                deletedCount,
            )

            call.resolve(
                result,
            )
        } catch (
            exception: Exception,
        ) {
            Log.e(
                logTag,
                "Unable to clear camera cache",
                exception,
            )

            call.reject(
                "Unable to clear camera cache",
                exception,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Video preview
    // -------------------------------------------------------------------------

    private fun configureVideoPreviewTransform(
        textureView: android.view.TextureView,
    ) {
        textureView.post {
            val viewWidth =
                textureView.width.toFloat()

            val viewHeight =
                textureView.height.toFloat()

            if (
                viewWidth <= 0f ||
                viewHeight <= 0f
            ) {
                return@post
            }

            val sourceWidth: Float
            val sourceHeight: Float

            if (
                viewHeight >=
                viewWidth
            ) {
                sourceWidth =
                    VIDEO_PREVIEW_HEIGHT.toFloat()

                sourceHeight =
                    VIDEO_PREVIEW_WIDTH.toFloat()
            } else {
                sourceWidth =
                    VIDEO_PREVIEW_WIDTH.toFloat()

                sourceHeight =
                    VIDEO_PREVIEW_HEIGHT.toFloat()
            }

            val fillScale =
                maxOf(
                    viewWidth /
                        sourceWidth,
                    viewHeight /
                        sourceHeight,
                )

            val desiredWidth =
                sourceWidth *
                    fillScale

            val desiredHeight =
                sourceHeight *
                    fillScale

            val scaleX =
                desiredWidth /
                    viewWidth

            val scaleY =
                desiredHeight /
                    viewHeight

            val matrix =
                Matrix()

            matrix.setScale(
                scaleX,
                scaleY,
                viewWidth / 2f,
                viewHeight / 2f,
            )

            textureView.setTransform(
                matrix,
            )

            Log.d(
                logTag,
                "Video preview transform applied: view=${viewWidth.toInt()}x${viewHeight.toInt()}, source=${sourceWidth.toInt()}x${sourceHeight.toInt()}, scaleX=$scaleX, scaleY=$scaleY",
            )
        }
    }

    private fun createVideoPreview(
        onReady: (Surface) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        activity.runOnUiThread {
            try {
                val container =
                    previewContainer
                        ?: throw IllegalStateException(
                            "Preview container is unavailable",
                        )

                previewView?.visibility =
                    View.GONE

                videoPreviewView =
                    android.view
                        .TextureView(
                            context,
                        ).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                )

                            surfaceTextureListener =
                                object :
                                    android.view.TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        surfaceTexture: android.graphics.SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        Log.d(
                                            logTag,
                                            "Video TextureView ready: ${width}x$height",
                                        )

                                        surfaceTexture
                                            .setDefaultBufferSize(
                                                VIDEO_PREVIEW_WIDTH,
                                                VIDEO_PREVIEW_HEIGHT,
                                            )

                                        configureVideoPreviewTransform(
                                            this@apply,
                                        )

                                        videoPreviewSurface =
                                            Surface(
                                                surfaceTexture,
                                            )

                                        onReady(
                                            videoPreviewSurface!!,
                                        )
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        surfaceTexture: android.graphics.SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        Log.d(
                                            logTag,
                                            "Video TextureView size changed: ${width}x$height",
                                        )

                                        configureVideoPreviewTransform(
                                            this@apply,
                                        )
                                    }

                                    override fun onSurfaceTextureDestroyed(
                                        surfaceTexture: android.graphics.SurfaceTexture,
                                    ): Boolean {
                                        Log.d(
                                            logTag,
                                            "Video TextureView destroyed",
                                        )

                                        videoPreviewSurface
                                            ?.release()

                                        videoPreviewSurface =
                                            null

                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(
                                        surfaceTexture: android.graphics.SurfaceTexture,
                                    ) {
                                    }
                                }
                        }

                container.addView(
                    videoPreviewView,
                    0,
                )
            } catch (
                exception: Exception,
            ) {
                onError(
                    exception,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Video capture proof
    // -------------------------------------------------------------------------

    private fun clearVideoCaptureProof() {
        activeVideoCaptureSessionId =
            null

        activeVideoNonce =
            null
    }

    // -------------------------------------------------------------------------
    // Video recording
    // -------------------------------------------------------------------------

    @PluginMethod
    fun startRecording(call: PluginCall) {
        Log.d(
            logTag,
            "startRecording called",
        )

        if (
            recordingStartPending
        ) {
            Log.w(
                logTag,
                "Ignoring duplicate startRecording call",
            )

            call.reject(
                "Recording is already starting",
            )

            return
        }

        val captureSessionId =
            call.getString(
                "captureSessionId",
            )

        val nonce =
            call.getString(
                "nonce",
            )

        if (
            captureSessionId.isNullOrBlank() ||
            nonce.isNullOrBlank()
        ) {
            Log.e(
                logTag,
                "Video recording rejected because capture proof is missing",
            )

            call.reject(
                "Missing capture session proof",
            )

            return
        }

        activeVideoCaptureSessionId =
            captureSessionId

        activeVideoNonce =
            nonce

        Log.d(
            logTag,
            "Video capture session ID: $captureSessionId",
        )

        recordingStartPending =
            true

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(
                logTag,
                "Microphone permission missing, requesting",
            )

            requestPermissionForAlias(
                "microphone",
                call,
                "microphonePermissionCallback",
            )

            return
        }

        beginVideoRecording(
            call,
        )
    }

    @PermissionCallback
    private fun microphonePermissionCallback(call: PluginCall) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            recordingStartPending =
                false

            clearVideoCaptureProof()

            Log.e(
                logTag,
                "Microphone permission denied",
            )

            call.reject(
                "Microphone permission denied",
            )

            return
        }

        Log.d(
            logTag,
            "Microphone permission granted",
        )

        beginVideoRecording(
            call,
        )
    }

    private fun beginVideoRecording(call: PluginCall) {
        activity.runOnUiThread {
            try {
                val currentLens: String =
                    cameraManager.currentLens()

                Log.d(
                    logTag,
                    "Beginning video recording with $currentLens camera",
                )

                cameraXNeedsRestore =
                    true

                cameraManager.pauseForVideo()

                createVideoPreview(
                    onReady = { previewSurface ->
                        videoRecorder?.start(
                            previewSurface =
                                previewSurface,
                            lens =
                                currentLens,
                            onStarted = {
                                recordingStartPending =
                                    false

                                Log.d(
                                    logTag,
                                    "Video recording started",
                                )

                                val result =
                                    JSObject()

                                result.put(
                                    "recording",
                                    true,
                                )

                                result.put(
                                    "zoomRatio",
                                    videoRecorder?.getZoomRatio()
                                        ?: 1.0f,
                                )

                                result.put(
                                    "minZoomRatio",
                                    videoRecorder?.getMinZoomRatio()
                                        ?: 1.0f,
                                )

                                result.put(
                                    "maxZoomRatio",
                                    videoRecorder?.getMaxZoomRatio()
                                        ?: 1.0f,
                                )

                                call.resolve(
                                    result,
                                )
                            },
                            onFinished = { file, duration ->
                                recordingStartPending =
                                    false

                                Log.d(
                                    logTag,
                                    "Video finished: ${file.absolutePath}",
                                )

                                try {
                                    /*
                                     * The MP4 is finalized before this callback.
                                     * Hashing here means we sign the exact file
                                     * bytes that VibeMoments will later upload.
                                     */
                                    val sha256 =
                                        CaptureHasher.sha256(
                                            file,
                                        )

                                    Log.d(
                                        logTag,
                                        "Video SHA-256: $sha256",
                                    )

                                    val captureSessionId =
                                        activeVideoCaptureSessionId

                                    val nonce =
                                        activeVideoNonce

                                    if (
                                        captureSessionId.isNullOrBlank() ||
                                        nonce.isNullOrBlank()
                                    ) {
                                        throw IllegalStateException(
                                            "Video capture proof is unavailable",
                                        )
                                    }

                                    val deviceId =
                                        captureSigner.getDeviceId()

                                    val captureSignature =
                                        captureSigner.signCapture(
                                            captureSessionId = captureSessionId,
                                            nonce = nonce,
                                            mediaType = "video",
                                            sha256 = sha256,
                                        )

                                    Log.d(
                                        logTag,
                                        "Video capture signature created for device $deviceId",
                                    )

                                    restoreCameraXPreview()

                                    val result =
                                        JSObject()

                                    result.put(
                                        "type",
                                        "video",
                                    )

                                    result.put(
                                        "path",
                                        file.absolutePath,
                                    )

                                    result.put(
                                        "mimeType",
                                        "video/mp4",
                                    )

                                    result.put(
                                        "durationMs",
                                        duration,
                                    )

                                    result.put(
                                        "videoBitrate",
                                        3_000_000,
                                    )

                                    result.put(
                                        "audioBitrate",
                                        128_000,
                                    )

                                    result.put(
                                        "lens",
                                        currentLens,
                                    )

                                    result.put(
                                        "sha256",
                                        sha256,
                                    )

                                    result.put(
                                        "captureSessionId",
                                        captureSessionId,
                                    )

                                    result.put(
                                        "nonce",
                                        nonce,
                                    )

                                    result.put(
                                        "deviceId",
                                        deviceId,
                                    )

                                    result.put(
                                        "captureSignature",
                                        captureSignature,
                                    )

                                    result.put(
                                        "proofVersion",
                                        CAPTURE_PROOF_VERSION,
                                    )

                                    result.put(
                                        "signatureAlgorithm",
                                        CAPTURE_SIGNATURE_ALGORITHM,
                                    )

                                    Log.d(
                                        logTag,
                                        "Returning signed video capture proof: session=$captureSessionId",
                                    )

                                    clearVideoCaptureProof()

                                    Log.d(
                                        logTag,
                                        "Sending videoRecordingFinished event",
                                    )

                                    notifyListeners(
                                        "videoRecordingFinished",
                                        result,
                                    )

                                    pendingVideoCall
                                        ?.resolve(
                                            result,
                                        )

                                    pendingVideoCall =
                                        null
                                } catch (
                                    exception: Exception,
                                ) {
                                    Log.e(
                                        logTag,
                                        "Unable to process recorded video",
                                        exception,
                                    )

                                    clearVideoCaptureProof()

                                    restoreCameraXPreview()

                                    val error =
                                        JSObject()

                                    error.put(
                                        "message",
                                        exception.message
                                            ?: "Unable to process recorded video",
                                    )

                                    notifyListeners(
                                        "videoRecordingError",
                                        error,
                                    )

                                    pendingVideoCall
                                        ?.reject(
                                            "Unable to process recorded video",
                                            exception,
                                        )

                                    pendingVideoCall =
                                        null
                                }
                            },
                            onError = { exception ->
                                val failedDuringStart =
                                    recordingStartPending

                                recordingStartPending =
                                    false

                                clearVideoCaptureProof()

                                Log.e(
                                    logTag,
                                    "Video recording failed",
                                    exception,
                                )

                                restoreCameraXPreview()

                                val error =
                                    JSObject()

                                error.put(
                                    "message",
                                    exception.message
                                        ?: "Video recording failed",
                                )

                                notifyListeners(
                                    "videoRecordingError",
                                    error,
                                )

                                pendingVideoCall
                                    ?.reject(
                                        "Video recording failed",
                                        exception,
                                    )

                                pendingVideoCall =
                                    null

                                if (
                                    failedDuringStart
                                ) {
                                    call.reject(
                                        "Unable to start recording",
                                        exception,
                                    )
                                }
                            },
                        ) ?: run {
                            recordingStartPending =
                                false

                            clearVideoCaptureProof()

                            restoreCameraXPreview()

                            call.reject(
                                "Video recorder unavailable",
                            )
                        }
                    },
                    onError = { exception ->
                        recordingStartPending =
                            false

                        clearVideoCaptureProof()

                        restoreCameraXPreview()

                        call.reject(
                            "Unable to create video preview",
                            exception,
                        )
                    },
                )
            } catch (
                exception: Exception,
            ) {
                recordingStartPending =
                    false

                clearVideoCaptureProof()

                Log.e(
                    logTag,
                    "Unable to start recording",
                    exception,
                )

                restoreCameraXPreview()

                call.reject(
                    "Unable to start recording",
                    exception,
                )
            }
        }
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        Log.d(
            logTag,
            "stopRecording called",
        )

        activity.runOnUiThread {
            try {
                pendingVideoCall =
                    call

                videoRecorder?.stop()
                    ?: throw IllegalStateException(
                        "Video recorder unavailable",
                    )
            } catch (
                exception: Exception,
            ) {
                pendingVideoCall =
                    null

                Log.e(
                    logTag,
                    "Unable to stop recording",
                    exception,
                )

                call.reject(
                    "Unable to stop recording",
                    exception,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Video preview cleanup
    // -------------------------------------------------------------------------

    private fun clearVideoPreview() {
        videoPreviewSurface
            ?.release()

        videoPreviewSurface =
            null

        videoPreviewView?.let {
            (it.parent as? ViewGroup)
                ?.removeView(it)
        }

        videoPreviewView =
            null

        previewView?.visibility =
            View.VISIBLE
    }

    private fun restoreCameraXPreview() {
        activity.runOnUiThread {
            clearVideoPreview()

            if (
                !cameraXNeedsRestore
            ) {
                return@runOnUiThread
            }

            if (
                appPaused
            ) {
                Log.d(
                    logTag,
                    "CameraX restore deferred until app resumes",
                )

                return@runOnUiThread
            }

            val view =
                previewView
                    ?: run {
                        cameraXNeedsRestore =
                            false

                        return@runOnUiThread
                    }

            cameraManager.resumeAfterVideo(
                onReady = {
                    cameraXNeedsRestore =
                        false

                    Log.d(
                        logTag,
                        "CameraX preview restored",
                    )
                },
                onError = { exception ->
                    Log.e(
                        logTag,
                        "Unable to restore CameraX preview",
                        exception,
                    )
                },
            )
        }
    }
}