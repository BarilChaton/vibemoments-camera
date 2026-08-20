package com.vibemoments.camera

import android.content.Context
import android.util.Log
import android.view.ScaleGestureDetector
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

class VibeCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK

    fun startPreview(
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        this.previewView = previewView

        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()

                bindCameraUseCases()
                setupPinchToZoom(previewView)

                onReady()
            } catch (exception: Exception) {
                onError(exception)
            }
        }, executor)
    }

    private fun setupPinchToZoom(view: PreviewView) {
        scaleGestureDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val activeCamera = camera ?: return false
                        val zoomState =
                            activeCamera.cameraInfo.zoomState.value
                                ?: return false

                        val currentZoom = zoomState.zoomRatio
                        val targetZoom = currentZoom * detector.scaleFactor

                        val clampedZoom =
                            targetZoom.coerceIn(
                                zoomState.minZoomRatio,
                                zoomState.maxZoomRatio,
                            )

                        activeCamera.cameraControl.setZoomRatio(clampedZoom)

                        Log.d(
                            "VibeCameraManager",
                            "Zoom: ${"%.2f".format(clampedZoom)}x",
                        )

                        return true
                    }
                },
            )

        view.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            true
        }
    }

    fun setZoomRatio(ratio: Float): Float {
        val activeCamera =
            camera
                ?: throw IllegalStateException("Camera is not active")

        val zoomState =
            activeCamera.cameraInfo.zoomState.value
                ?: throw IllegalStateException("Zoom state is unavailable")

        val clampedRatio =
            ratio.coerceIn(
                zoomState.minZoomRatio,
                zoomState.maxZoomRatio,
            )

        activeCamera.cameraControl.setZoomRatio(clampedRatio)

        Log.d(
            "VibeCameraManager",
            "Zoom ratio set to: ${"%.2f".format(clampedRatio)}x",
        )

        return clampedRatio
    }

    fun currentZoomRatio(): Float =
        camera
            ?.cameraInfo
            ?.zoomState
            ?.value
            ?.zoomRatio
            ?: 1f

    fun minZoomRatio(): Float =
        camera
            ?.cameraInfo
            ?.zoomState
            ?.value
            ?.minZoomRatio
            ?: 1f

    fun maxZoomRatio(): Float =
        camera
            ?.cameraInfo
            ?.zoomState
            ?.value
            ?.maxZoomRatio
            ?: 1f

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val view = previewView ?: return

        Log.d(
            "VibeCameraManager",
            "Binding camera lens: ${currentLens()}",
        )

        val cameraSelector =
            CameraSelector
                .Builder()
                .requireLensFacing(lensFacing)
                .build()

        val preview =
            Preview
                .Builder()
                .build()

        imageCapture =
            ImageCapture
                .Builder()
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                )
                .build()

        preview.setSurfaceProvider(view.surfaceProvider)

        provider.unbindAll()

        camera =
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
            )

        Log.d(
            "VibeCameraManager",
            "Camera bound successfully: ${currentLens()}",
        )

        val zoomState = camera?.cameraInfo?.zoomState?.value

        if (zoomState != null) {
            Log.d(
                "VibeCameraManager",
                "Zoom range: ${zoomState.minZoomRatio}x - ${zoomState.maxZoomRatio}x",
            )
        }
    }

    private fun hasCamera(lensFacing: Int): Boolean {
        val provider = cameraProvider ?: return false

        return provider.hasCamera(
            CameraSelector
                .Builder()
                .requireLensFacing(lensFacing)
                .build(),
        )
    }

    fun stopPreview() {
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()

        previewView?.setOnTouchListener(null)

        scaleGestureDetector = null
        camera = null
        previewView = null
        imageCapture = null
    }

    fun switchCamera(): String {
        val targetLens =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        if (!hasCamera(targetLens)) {
            throw IllegalStateException(
                "Requested camera is not available",
            )
        }

        lensFacing = targetLens

        bindCameraUseCases()

        return currentLens()
    }

    fun currentLens(): String =
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            "front"
        } else {
            "back"
        }

    fun capturePhoto(
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        Log.d(
            "VibeCameraManager",
            "capturePhoto called",
        )

        val capture = imageCapture

        if (capture == null) {
            Log.e(
                "VibeCameraManager",
                "ImageCapture is null",
            )

            onError(
                IllegalStateException(
                    "Camera is not active",
                ),
            )

            return
        }

        val outputDirectory =
            File(
                context.cacheDir,
                "vibemoments-camera",
            )

        if (!outputDirectory.exists()) {
            Log.d(
                "VibeCameraManager",
                "Creating output directory",
            )

            outputDirectory.mkdirs()
        }

        val photoFile =
            File(
                outputDirectory,
                "photo_${System.currentTimeMillis()}.jpg",
            )

        Log.d(
            "VibeCameraManager",
            "Saving photo to ${photoFile.absolutePath}",
        )

        val outputOptions =
            ImageCapture.OutputFileOptions
                .Builder(photoFile)
                .build()

        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults,
                ) {
                    Log.d(
                        "VibeCameraManager",
                        "Image saved successfully: ${photoFile.absolutePath}",
                    )

                    onSuccess(photoFile)
                }

                override fun onError(
                    exception: ImageCaptureException,
                ) {
                    Log.e(
                        "VibeCameraManager",
                        "ImageCapture failed",
                        exception,
                    )

                    onError(exception)
                }
            },
        )
    }

    fun hasFlash(): Boolean =
        camera
            ?.cameraInfo
            ?.hasFlashUnit() == true

    fun setTorch(enabled: Boolean) {
        val activeCamera =
            camera
                ?: throw IllegalStateException(
                    "Camera is not active",
                )

        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            throw IllegalStateException(
                "Current camera has no flash unit",
            )
        }

        Log.d(
            "VibeCameraManager",
            "Setting torch: $enabled",
        )

        activeCamera.cameraControl.enableTorch(enabled)
    }

    fun setFlashMode(mode: String): String {
        val capture =
            imageCapture
                ?: throw IllegalStateException(
                    "Camera is not active",
                )

        val flashMode =
            when (mode) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                "off" -> ImageCapture.FLASH_MODE_OFF
                else ->
                    throw IllegalArgumentException(
                        "Invalid flash mode: $mode",
                    )
            }

        capture.flashMode = flashMode

        Log.d(
            "VibeCameraManager",
            "Flash mode set to: $mode",
        )

        return mode
    }

    fun pauseForVideo() {
        Log.d(
            "VibeCameraManager",
            "Pausing CameraX for video recording",
        )

        cameraProvider?.unbindAll()

        camera = null
        imageCapture = null
    }

    fun resumeAfterVideo(
        onReady: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val view =
            previewView
                ?: run {
                    onError(
                        IllegalStateException(
                            "PreviewView is unavailable",
                        ),
                    )

                    return
                }

        startPreview(
            view,
            onReady,
            onError,
        )
    }
}