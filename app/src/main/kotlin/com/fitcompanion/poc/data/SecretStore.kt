package com.fitcompanion.poc.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the OpenRouter API key encrypted with a key that lives in the Android Keystore
 * (hardware-backed on most phones, never exportable). Only the encrypted bytes are written to
 * app-private preferences, which are excluded from backup (allowBackup=false).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "fitcompanion_secrets_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$name.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("$name.ct", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun get(name: String): String? {
        val iv = prefs.getString("$name.iv", null) ?: return null
        val ct = prefs.getString("$name.ct", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // key rotated or data corrupted: treat as not set
        }
    }

    fun remove(name: String) = prefs.edit().remove("$name.iv").remove("$name.ct").apply()

    fun has(name: String) = prefs.contains("$name.ct")
}
