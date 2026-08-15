package com.vibemoments.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.view.Surface
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

@CapacitorPlugin(
    name = "VibeCamera",
    permissions = [
        Permission(
            alias = "camera",
            strings = [Manifest.permission.CAMERA]
        ),
        Permission(
            alias = "microphone",
            strings = [Manifest.permission.RECORD_AUDIO]
        )
    ]
)
class VibeCameraPlugin : Plugin() {

    private val logTag = "VibeCamera"

    private lateinit var cameraManager: VibeCameraManager

    private var previewView: PreviewView? = null
    private var previewContainer: FrameLayout? = null

    private var videoPreviewView: android.view.TextureView? = null
    private var videoPreviewSurface: Surface? = null

    private var videoRecorder: VibeVideoRecorder? = null

    private var pendingVideoCall: PluginCall? = null

    override fun load() {
        super.load()

        Log.d(
            logTag,
            "Plugin loaded"
        )

        cameraManager = VibeCameraManager(
            context,
            activity,
            ContextCompat.getMainExecutor(context)
        )

        videoRecorder =
            VibeVideoRecorder(context)
    }

    @PluginMethod
    fun startPreview(call: PluginCall) {
        Log.d(
            logTag,
            "startPreview called"
        )

        if (previewView != null) {
            Log.d(
                logTag,
                "Camera preview already active"
            )

            val result = JSObject()

            result.put(
                "active",
                true
            )

            result.put(
                "lens",
                cameraManager.currentLens()
            )

            call.resolve(result)
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(
                logTag,
                "Camera permission missing, requesting"
            )

            requestPermissionForAlias(
                "camera",
                call,
                "cameraPermissionCallback"
            )

            return
        }

        openPreview(call)
    }

    @PermissionCallback
    private fun cameraPermissionCallback(
        call: PluginCall
    ) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                logTag,
                "Camera permission denied"
            )

            call.reject(
                "Camera permission denied"
            )

            return
        }

        Log.d(
            logTag,
            "Camera permission granted"
        )

        openPreview(call)
    }

    private fun openPreview(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "openPreview called"
        )

        activity.runOnUiThread {
            try {
                val webView =
                    bridge.webView

                val webViewParent =
                    webView.parent as? ViewGroup

                if (webViewParent == null) {
                    call.reject(
                        "Unable to access WebView parent"
                    )

                    return@runOnUiThread
                }

                if (previewContainer == null) {
                    Log.d(
                        logTag,
                        "Creating camera preview behind WebView"
                    )

                    previewContainer =
                        FrameLayout(context).apply {
                            layoutParams =
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                        }

                    previewView =
                        PreviewView(context).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )

                            scaleType =
                                PreviewView.ScaleType.FILL_CENTER
                        }

                    previewContainer!!.addView(
                        previewView
                    )

                    val webViewIndex =
                        webViewParent.indexOfChild(
                            webView
                        )

                    webViewParent.addView(
                        previewContainer,
                        webViewIndex
                    )

                    webView.setBackgroundColor(
                        Color.TRANSPARENT
                    )

                    webViewParent.setBackgroundColor(
                        Color.TRANSPARENT
                    )

                    webView.bringToFront()
                }

                Log.d(
                    logTag,
                    "Starting CameraX preview"
                )

                cameraManager.startPreview(
                    previewView!!,

                    onReady = {
                        Log.d(
                            logTag,
                            "Camera preview ready"
                        )

                        val result =
                            JSObject()

                        result.put(
                            "active",
                            true
                        )

                        result.put(
                            "lens",
                            cameraManager.currentLens()
                        )

                        call.resolve(
                            result
                        )
                    },

                    onError = { exception ->
                        Log.e(
                            logTag,
                            "Unable to start camera",
                            exception
                        )

                        call.reject(
                            "Unable to start camera",
                            exception
                        )
                    }
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    logTag,
                    "Unable to create camera preview",
                    exception
                )

                call.reject(
                    "Unable to create camera preview",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun stopPreview(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "stopPreview called"
        )

        activity.runOnUiThread {
            cameraManager.stopPreview()

            videoPreviewSurface?.release()
            videoPreviewSurface = null

            videoPreviewView?.let {
                (it.parent as? ViewGroup)
                    ?.removeView(it)
            }

            videoPreviewView = null

            previewContainer?.let {
                (it.parent as? ViewGroup)
                    ?.removeView(it)
            }

            previewView = null
            previewContainer = null

            bridge.webView.setBackgroundColor(
                Color.WHITE
            )

            call.resolve()
        }
    }

    @PluginMethod
    fun capturePhoto(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "capturePhoto called"
        )

        cameraManager.capturePhoto(
            onSuccess = { file ->
                Log.d(
                    logTag,
                    "Photo captured: ${file.absolutePath}"
                )

                val result =
                    JSObject()

                result.put(
                    "type",
                    "photo"
                )

                result.put(
                    "path",
                    file.toURI().toString()
                )

                result.put(
                    "mimeType",
                    "image/jpeg"
                )

                result.put(
                    "lens",
                    cameraManager.currentLens()
                )

                call.resolve(
                    result
                )
            },

            onError = { exception ->
                Log.e(
                    logTag,
                    "Photo capture failed",
                    exception
                )

                call.reject(
                    "Unable to capture photo",
                    exception
                )
            }
        )
    }

    @PluginMethod
    fun switchCamera(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "switchCamera called"
        )

        activity.runOnUiThread {
            try {
                val lens =
                    cameraManager.switchCamera()

                Log.d(
                    logTag,
                    "Camera switched to $lens"
                )

                val result =
                    JSObject()

                result.put(
                    "lens",
                    lens
                )

                call.resolve(
                    result
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    logTag,
                    "Unable to switch camera",
                    exception
                )

                call.reject(
                    "Unable to switch camera",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun getCameraState(
        call: PluginCall
    ) {
        val result =
            JSObject()

        result.put(
            "active",
            previewView != null
        )

        result.put(
            "lens",
            cameraManager.currentLens()
        )

        call.resolve(
            result
        )
    }

    @PluginMethod
    fun setTorch(
        call: PluginCall
    ) {
        val enabled =
            call.getBoolean(
                "enabled"
            ) ?: false

        Log.d(
            logTag,
            "setTorch called: $enabled"
        )

        activity.runOnUiThread {
            try {
                cameraManager.setTorch(
                    enabled
                )

                val result =
                    JSObject()

                result.put(
                    "enabled",
                    enabled
                )

                call.resolve(
                    result
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    logTag,
                    "Unable to set torch",
                    exception
                )

                call.reject(
                    "Unable to set torch",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun setFlashMode(
        call: PluginCall
    ) {
        val mode =
            call.getString(
                "mode"
            ) ?: "off"

        Log.d(
            logTag,
            "setFlashMode called: $mode"
        )

        activity.runOnUiThread {
            try {
                val resultMode =
                    cameraManager.setFlashMode(
                        mode
                    )

                val result =
                    JSObject()

                result.put(
                    "mode",
                    resultMode
                )

                call.resolve(
                    result
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    logTag,
                    "Unable to set flash mode",
                    exception
                )

                call.reject(
                    "Unable to set flash mode",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun getCapabilities(
        call: PluginCall
    ) {
        val result =
            JSObject()

        result.put(
            "hasFlash",
            cameraManager.hasFlash()
        )

        result.put(
            "lens",
            cameraManager.currentLens()
        )

        call.resolve(
            result
        )
    }

    private fun createVideoPreview(
        onReady: (Surface) -> Unit,
        onError: (Exception) -> Unit
    ) {
        activity.runOnUiThread {
            try {
                val container =
                    previewContainer
                        ?: throw IllegalStateException(
                            "Preview container is unavailable"
                        )

                previewView?.visibility =
                    android.view.View.GONE

                videoPreviewView =
                    android.view.TextureView(
                        context
                    ).apply {
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )

                        surfaceTextureListener =
                            object :
                                android.view.TextureView.SurfaceTextureListener {

                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture:
                                        android.graphics.SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    Log.d(
                                        logTag,
                                        "Video TextureView ready"
                                    )

                                    videoPreviewSurface =
                                        Surface(
                                            surfaceTexture
                                        )

                                    onReady(
                                        videoPreviewSurface!!
                                    )
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture:
                                        android.graphics.SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {}

                                override fun onSurfaceTextureDestroyed(
                                    surfaceTexture:
                                        android.graphics.SurfaceTexture
                                ): Boolean {
                                    videoPreviewSurface
                                        ?.release()

                                    videoPreviewSurface =
                                        null

                                    return true
                                }

                                override fun onSurfaceTextureUpdated(
                                    surfaceTexture:
                                        android.graphics.SurfaceTexture
                                ) {}
                            }
                    }

                container.addView(
                    videoPreviewView,
                    0
                )
            } catch (
                exception: Exception
            ) {
                onError(
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun startRecording(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "startRecording called"
        )

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(
                logTag,
                "Microphone permission missing, requesting"
            )

            requestPermissionForAlias(
                "microphone",
                call,
                "microphonePermissionCallback"
            )

            return
        }

        beginVideoRecording(
            call
        )
    }

    @PermissionCallback
    private fun microphonePermissionCallback(
        call: PluginCall
    ) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                logTag,
                "Microphone permission denied"
            )

            call.reject(
                "Microphone permission denied"
            )

            return
        }

        Log.d(
            logTag,
            "Microphone permission granted"
        )

        beginVideoRecording(
            call
        )
    }

    private fun beginVideoRecording(
        call: PluginCall
    ) {
        activity.runOnUiThread {
            try {
                val currentLens: String =
                    cameraManager.currentLens()

                Log.d(
                    logTag,
                    "Beginning video recording with $currentLens camera"
                )

                cameraManager.pauseForVideo()

                createVideoPreview(
                    onReady = { previewSurface ->

                        videoRecorder?.start(
                            previewSurface =
                                previewSurface,

                            lens =
                                currentLens,

                            onStarted = {
                                Log.d(
                                    logTag,
                                    "Video recording started"
                                )

                                val result =
                                    JSObject()

                                result.put(
                                    "recording",
                                    true
                                )

                                call.resolve(
                                    result
                                )
                            },

                            onFinished = { file, duration ->
                                Log.d(
                                    logTag,
                                    "Video finished: ${file.absolutePath}"
                                )

                                restoreCameraXPreview()

                                val result = JSObject()

                                result.put(
                                    "type",
                                    "video"
                                )

                                result.put(
                                    "path",
                                    file.toURI().toString()
                                )

                                result.put(
                                    "mimeType",
                                    "video/mp4"
                                )

                                result.put(
                                    "durationMs",
                                    duration
                                )

                                result.put(
                                    "videoBitrate",
                                    5_000_000
                                )

                                result.put(
                                    "audioBitrate",
                                    128_000
                                )

                                result.put(
                                    "lens",
                                    currentLens
                                )

                                Log.d(
                                    logTag,
                                    "Sending videoRecordingFinished event"
                                )

                                notifyListeners(
                                    "videoRecordingFinished",
                                    result
                                )

                                pendingVideoCall?.resolve(
                                    result
                                )

                                pendingVideoCall = null
                            },

                            onError = { exception ->
                                Log.e(
                                    logTag,
                                    "Video recording failed",
                                    exception
                                )

                                restoreCameraXPreview()

                                val error = JSObject()

                                error.put(
                                    "message",
                                    exception.message
                                        ?: "Video recording failed"
                                )

                                notifyListeners(
                                    "videoRecordingError",
                                    error
                                )

                                pendingVideoCall?.reject(
                                    "Video recording failed",
                                    exception
                                )

                                pendingVideoCall = null
                            }
                        ) ?: run {
                            restoreCameraXPreview()

                            call.reject(
                                "Video recorder unavailable"
                            )
                        }
                    },

                    onError = { exception ->
                        restoreCameraXPreview()

                        call.reject(
                            "Unable to create video preview",
                            exception
                        )
                    }
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    logTag,
                    "Unable to start recording",
                    exception
                )

                restoreCameraXPreview()

                call.reject(
                    "Unable to start recording",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun stopRecording(
        call: PluginCall
    ) {
        Log.d(
            logTag,
            "stopRecording called"
        )

        activity.runOnUiThread {
            try {
                pendingVideoCall =
                    call

                videoRecorder?.stop()
                    ?: throw IllegalStateException(
                        "Video recorder unavailable"
                    )
            } catch (
                exception: Exception
            ) {
                pendingVideoCall =
                    null

                Log.e(
                    logTag,
                    "Unable to stop recording",
                    exception
                )

                call.reject(
                    "Unable to stop recording",
                    exception
                )
            }
        }
    }

    private fun restoreCameraXPreview() {
        activity.runOnUiThread {
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
                android.view.View.VISIBLE

            cameraManager.resumeAfterVideo(
                onReady = {
                    Log.d(
                        logTag,
                        "CameraX preview restored"
                    )
                },

                onError = { exception ->
                    Log.e(
                        logTag,
                        "Unable to restore CameraX preview",
                        exception
                    )
                }
            )
        }
    }
}