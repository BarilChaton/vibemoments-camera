package com.vibemoments.camera

import android.content.Context
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class CaptureSigner(
    private val context: Context,
) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "vibemoments_capture_key_v1"

        private const val PREFERENCES_NAME = "vibemoments_camera_security"
        private const val DEVICE_ID_KEY = "capture_device_id"

        private const val PROOF_VERSION = "vibemoments-capture-v1"
    }

    private val keyStore =
        KeyStore
            .getInstance(
                KEYSTORE_PROVIDER,
            ).apply {
                load(null)
            }

    init {
        ensureKeyPair()
    }

    fun getDeviceId(): String {
        val preferences =
            context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )

        val existing =
            preferences.getString(
                DEVICE_ID_KEY,
                null,
            )

        if (!existing.isNullOrBlank()) {
            return existing
        }

        val deviceId =
            UUID
                .randomUUID()
                .toString()

        preferences
            .edit()
            .putString(
                DEVICE_ID_KEY,
                deviceId,
            ).apply()

        return deviceId
    }

    fun getPublicKeyBase64(): String {
        val certificate =
            keyStore.getCertificate(
                KEY_ALIAS,
            )
                ?: throw IllegalStateException(
                    "Capture signing certificate is unavailable",
                )

        return Base64.encodeToString(
            certificate.publicKey.encoded,
            Base64.NO_WRAP,
        )
    }

    fun signCapture(
        captureSessionId: String,
        nonce: String,
        mediaType: String,
        sha256: String,
    ): String {
        val deviceId =
            getDeviceId()

        val payload =
            createPayload(
                captureSessionId = captureSessionId,
                nonce = nonce,
                mediaType = mediaType,
                sha256 = sha256,
                deviceId = deviceId,
            )

        val privateKey =
            keyStore.getKey(
                KEY_ALIAS,
                null,
            )
                ?: throw IllegalStateException(
                    "Capture signing key is unavailable",
                )

        val signature =
            Signature.getInstance(
                "SHA256withECDSA",
            )

        signature.initSign(
            privateKey as java.security.PrivateKey,
        )

        signature.update(
            payload.toByteArray(
                Charsets.UTF_8,
            ),
        )

        return Base64.encodeToString(
            signature.sign(),
            Base64.NO_WRAP,
        )
    }

    private fun ensureKeyPair() {
        if (
            keyStore.containsAlias(
                KEY_ALIAS,
            )
        ) {
            return
        }

        val generator =
            KeyPairGenerator.getInstance(
                "EC",
                KEYSTORE_PROVIDER,
            )

        val parameterSpec =
            android.security.keystore
                .KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN,
                ).setAlgorithmParameterSpec(
                    ECGenParameterSpec(
                        "secp256r1",
                    ),
                ).setDigests(
                    android.security.keystore.KeyProperties.DIGEST_SHA256,
                ).build()

        generator.initialize(
            parameterSpec,
        )

        generator.generateKeyPair()
    }

    private fun createPayload(
        captureSessionId: String,
        nonce: String,
        mediaType: String,
        sha256: String,
        deviceId: String,
    ): String {
        return listOf(
            PROOF_VERSION,
            captureSessionId,
            nonce,
            mediaType,
            sha256.lowercase(),
            deviceId,
        ).joinToString(
            "\n",
        )
    }
}