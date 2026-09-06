package com.splitice.searchcard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.splitice.searchcard.core.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AccountSettings(val dashboard: String = "", val selected: CardSelection? = null)

class Storage(context: Context) {
    private val preferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.noBackupFilesDir, "snapshot.json"))
    var settings: AccountSettings
        get() = runCatching { JsonCodec.decodeFromString<AccountSettings>(preferences.getString("settings", "{}")!!) }
            .getOrDefault(AccountSettings())
        set(value) { preferences.edit().putString("settings", JsonCodec.encodeToString(value)).commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("search-card-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("search-card-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    val hasSavedSession: Boolean get() = preferences.contains("refreshToken")

    fun refreshToken(): String? {
        val encoded = preferences.getString("refreshToken", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
    fun saveRefreshToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray())
        preferences.edit().putString("refreshToken", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    }
    @Synchronized fun loadSnapshot(): Snapshot? = runCatching {
        cache.openRead().bufferedReader().use { JsonCodec.decodeFromString<Snapshot>(it.readText()) }
    }.getOrNull()
    @Synchronized fun saveSnapshot(value: Snapshot) {
        val stream = cache.startWrite()
        try { stream.write(JsonCodec.encodeToString(value).toByteArray()); cache.finishWrite(stream) }
        catch (error: Exception) { cache.failWrite(stream); throw error }
    }
    @Synchronized fun clearAccount() {
        preferences.edit().remove("refreshToken").commit()
        settings = settings.copy(selected = null)
        cache.delete()
    }
}
