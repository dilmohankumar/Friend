package com.situationship.app.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(name = "session")

data class Tokens(val accessToken: String, val refreshToken: String)

/**
 * AES-256-GCM key that lives in the Android Keystore (hardware-backed where available).
 * The key never leaves the secure element; we only store ciphertext in DataStore.
 */
object KeystoreCipher {
    private const val ALIAS = "situationship_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/** Session tokens: encrypted with the Keystore key, persisted in DataStore, cached in memory. */
@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val ACCESS = stringPreferencesKey("access")
    private val REFRESH = stringPreferencesKey("refresh")
    private val mutex = Mutex()

    private val _tokens = MutableStateFlow<Tokens?>(null)
    val tokens: StateFlow<Tokens?> = _tokens.asStateFlow()

    @Volatile private var loaded = false

    /** Loads tokens once from disk (blocking is fine: called on a background thread by OkHttp, or at startup). */
    fun current(): Tokens? {
        if (!loaded) runBlocking { load() }
        return _tokens.value
    }

    suspend fun load(): Tokens? = mutex.withLock {
        if (!loaded) {
            val prefs = context.sessionStore.data.first()
            val a = prefs[ACCESS]?.let(KeystoreCipher::decrypt)
            val r = prefs[REFRESH]?.let(KeystoreCipher::decrypt)
            _tokens.value = if (a != null && r != null) Tokens(a, r) else null
            loaded = true
        }
        _tokens.value
    }

    suspend fun save(t: Tokens) = mutex.withLock {
        context.sessionStore.edit {
            it[ACCESS] = KeystoreCipher.encrypt(t.accessToken)
            it[REFRESH] = KeystoreCipher.encrypt(t.refreshToken)
        }
        _tokens.value = t
        loaded = true
    }

    suspend fun clear() = mutex.withLock {
        context.sessionStore.edit { it.clear() }
        _tokens.value = null
        loaded = true
    }
}
