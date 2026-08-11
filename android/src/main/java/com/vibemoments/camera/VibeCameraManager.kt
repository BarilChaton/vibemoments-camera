package com.vibemoments.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import android.util.Log

class VibeCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK

    fun startPreview(
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        this.previewView = previewView

        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
                onReady()
            } catch (exception: Exception) {
                onError(exception)
            }
        }, executor)
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val view = previewView ?: return

        Log.d("VibeCameraManager", "Binding camera lens: ${currentLens()}")

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder()
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        preview.setSurfaceProvider(view.surfaceProvider)

        provider.unbindAll()

        provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCapture
        )

        Log.d("VibeCameraManager", "Camera bound successfully: ${currentLens()}")
    }

    private fun hasCamera(lensFacing: Int): Boolean {
        val provider = cameraProvider ?: return false

        return provider.hasCamera(
            CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
        )
    }

    fun stopPreview() {
        cameraProvider?.unbindAll()
        previewView = null
        imageCapture = null
    }

    fun switchCamera(): String {
        val targetLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        if (!hasCamera(targetLens)) {
            throw IllegalStateException("Requested camera is not available")
        }

        lensFacing = targetLens

        bindCameraUseCases()

        return currentLens()
    }

    fun currentLens(): String {
        return if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            "front"
        } else {
            "back"
        }
    }

    fun capturePhoto(
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.d("VibeCameraManager", "capturePhoto called")

        val capture = imageCapture

        if (capture == null) {
            Log.e("VibeCameraManager", "ImageCapture is null")
            onError(IllegalStateException("Camera is not active"))
            return
        }

        val outputDirectory = File(
            context.cacheDir,
            "vibemoments-camera"
        )

        if (!outputDirectory.exists()) {
            Log.d("VibeCameraManager", "Creating output directory")
            outputDirectory.mkdirs()
        }

        val photoFile = File(
            outputDirectory,
            "photo_${System.currentTimeMillis()}.jpg"
        )

        Log.d(
            "VibeCameraManager",
            "Saving photo to ${photoFile.absolutePath}"
        )

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(photoFile)
            .build()

        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    Log.d(
                        "VibeCameraManager",
                        "Image saved successfully: ${photoFile.absolutePath}"
                    )

                    onSuccess(photoFile)
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {
                    Log.e(
                        "VibeCameraManager",
                        "ImageCapture failed",
                        exception
                    )

                    onError(exception)
                }
            }
        )
    }
}