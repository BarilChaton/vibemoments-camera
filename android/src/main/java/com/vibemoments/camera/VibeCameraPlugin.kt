package com.vibemoments.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import android.graphics.Color
import android.view.View
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
        )
    ]
)
class VibeCameraPlugin : Plugin() {

    private val tag = "VibeCamera"
    private lateinit var cameraManager: VibeCameraManager
    private var previewView: PreviewView? = null
    private var previewContainer: FrameLayout? = null
    private var originalWebViewBackground: Int? = null

    override fun load() {
        super.load()

        Log.d(tag, "Plugin loaded")

        cameraManager = VibeCameraManager(
            context,
            activity,
            ContextCompat.getMainExecutor(context)
        )
    }

    @PluginMethod
    fun startPreview(call: PluginCall) {
        Log.d(tag, "startPreview called")

        if (previewView != null) {
            Log.d(tag, "Camera preview already active")

            val result = JSObject()
            result.put("active", true)
            result.put("lens", cameraManager.currentLens())

            call.resolve(result)
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
    private fun cameraPermissionCallback(call: PluginCall) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            call.reject("Camera permission denied")
            return
        }

        openPreview(call)
    }

    private fun openPreview(call: PluginCall) {
        Log.d(tag, "openPreview called")

        activity.runOnUiThread {
            try {
                val webView = bridge.webView
                val webViewParent = webView.parent as? ViewGroup

                if (webViewParent == null) {
                    call.reject("Unable to access WebView parent")
                    return@runOnUiThread
                }

                if (previewContainer == null) {
                    Log.d(tag, "Creating camera preview behind WebView")

                    previewContainer = FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    previewView = PreviewView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )

                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    previewContainer!!.addView(previewView)

                    val webViewIndex = webViewParent.indexOfChild(webView)

                    webViewParent.addView(
                        previewContainer,
                        webViewIndex
                    )

                    webView.setBackgroundColor(Color.TRANSPARENT)
                    webViewParent.setBackgroundColor(Color.TRANSPARENT)

                    webView.bringToFront()
                }

                Log.d(tag, "Starting CameraX preview")

                cameraManager.startPreview(
                    previewView!!,
                    onReady = {
                        Log.d(tag, "Camera preview ready")

                        val result = JSObject()
                        result.put("active", true)
                        result.put("lens", cameraManager.currentLens())

                        call.resolve(result)
                    },
                    onError = { exception ->
                        Log.e(tag, "Unable to start camera", exception)

                        call.reject(
                            "Unable to start camera",
                            exception
                        )
                    }
                )
            } catch (exception: Exception) {
                Log.e(tag, "Unable to create camera preview", exception)

                call.reject(
                    "Unable to create camera preview",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun stopPreview(call: PluginCall) {
        Log.d(tag, "stopPreview called")

        activity.runOnUiThread {
            cameraManager.stopPreview()

            previewContainer?.let {
                (it.parent as? ViewGroup)?.removeView(it)
            }

            previewView = null
            previewContainer = null

            bridge.webView.setBackgroundColor(Color.WHITE)

            call.resolve()
        }
    }

    @PluginMethod
    fun capturePhoto(call: PluginCall) {
        Log.d(tag, "capturePhoto called")

        cameraManager.capturePhoto(
            onSuccess = { file ->
                Log.d(tag, "Photo captured: ${file.absolutePath}")

                val result = JSObject()
                result.put("type", "photo")
                result.put("path", file.toURI().toString())
                result.put("mimeType", "image/jpeg")
                result.put("lens", cameraManager.currentLens())

                call.resolve(result)
            },
            onError = { exception ->
                Log.e(tag, "Photo capture failed", exception)

                call.reject(
                    "Unable to capture photo",
                    exception
                )
            }
        )
    }

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        Log.d(tag, "switchCamera called")

        activity.runOnUiThread {
            try {
                val lens = cameraManager.switchCamera()

                Log.d(tag, "Camera switched to $lens")

                val result = JSObject()
                result.put("lens", lens)

                call.resolve(result)
            } catch (exception: Exception) {
                Log.e(tag, "Unable to switch camera", exception)

                call.reject(
                    "Unable to switch camera",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun getCameraState(call: PluginCall) {
        val result = JSObject()

        result.put("active", previewView != null)
        result.put("lens", cameraManager.currentLens())

        call.resolve(result)
    }

    @PluginMethod
    fun setTorch(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false

        Log.d(tag, "setTorch called: $enabled")

        activity.runOnUiThread {
            try {
                cameraManager.setTorch(enabled)

                val result = JSObject()
                result.put("enabled", enabled)

                call.resolve(result)
            } catch (exception: Exception) {
                Log.e(tag, "Unable to set torch", exception)

                call.reject(
                    "Unable to set torch",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode = call.getString("mode") ?: "off"

        Log.d(tag, "setFlashMode called: $mode")

        activity.runOnUiThread {
            try {
                val resultMode = cameraManager.setFlashMode(mode)

                val result = JSObject()
                result.put("mode", resultMode)

                call.resolve(result)
            } catch (exception: Exception) {
                Log.e(tag, "Unable to set flash mode", exception)

                call.reject(
                    "Unable to set flash mode",
                    exception
                )
            }
        }
    }

    @PluginMethod
    fun getCapabilities(call: PluginCall) {
        val result = JSObject()

        result.put("hasFlash", cameraManager.hasFlash())
        result.put("lens", cameraManager.currentLens())

        call.resolve(result)
    }
}