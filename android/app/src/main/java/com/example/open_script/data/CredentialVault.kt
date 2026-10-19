package com.example.open_script.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** AES-GCM ciphertext in private preferences; the encryption key never leaves AndroidKeyStore. */
class CredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("native-credentials-v1", Context.MODE_PRIVATE)
    private val associatedData = "${context.packageName}:native-credentials-v1".toByteArray(Charsets.UTF_8)

    @Synchronized
    fun load(): AuthSession? = try {
        val value = preferences.getString(ENTRY, null)
        if (value == null) null else {
            val parts = value.split(':')
            require(parts.size == 3 && parts[0] == "1")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            cipher.updateAAD(associatedData)
            val plaintext = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
            try {
                JsonCodec.session(JSONObject(plaintext.toString(Charsets.UTF_8))).also {
                    require(it.accessToken.matches(Regex("[A-Za-z0-9_-]{43}")))
                    require(ApiContract.isUuid(it.user.id))
                }
            } finally { plaintext.fill(0) }
        }
    } catch (_: Exception) {
        // Key invalidation or corrupted ciphertext asks for login without exposing any secret.
        null
    }

    @Synchronized
    fun save(session: AuthSession) {
        require(session.accessToken.matches(Regex("[A-Za-z0-9_-]{43}")) && ApiContract.isUuid(session.user.id)) {
            "O servidor retornou um acesso inválido."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(associatedData)
        val plaintext = JsonCodec.session(session).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val value = "1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        if (!preferences.edit().putString(ENTRY, value).commit()) throw IOException("Não foi possível guardar o acesso com segurança.")
    }

    @Synchronized
    fun clear() {
        if (!preferences.edit().remove(ENTRY).commit()) throw IOException("Não foi possível encerrar o acesso local.")
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw IOException("A chave de acesso não está disponível.")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ENTRY = "encrypted_session"
        const val ALIAS = "openscript.native.credentials.v1"
    }
}
