package com.vibemoments.camera

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object CaptureHasher {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        FileInputStream(file).use { input ->
            while (true) {
                val bytesRead = input.read(buffer)

                if (bytesRead <= 0) {
                    break
                }

                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }
}
