package io.freehand.selection.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Only the encrypted key is persisted. Account keys are never shipped in the APK. */
internal class AiProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-profiles", Context.MODE_PRIVATE)

    fun profiles(): List<AiProfile> {
        val raw = preferences.getString("profiles", null) ?: return AiProfile.presets()
        return JSONArray(raw).let { array -> (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val encrypted = item.optString("key")
            val key = runCatching { if (encrypted.isBlank()) "" else decrypt(encrypted) }
            AiProfile(id = item.getString("id"), name = item.getString("name"),
                protocol = AiProtocol.valueOf(item.getString("protocol")), baseUrl = item.getString("baseUrl"),
                model = item.getString("model"), apiKey = key.getOrDefault(""),
                keyHeader = item.optString("keyHeader"),
                stream = item.optBoolean("stream", true), timeoutSeconds = item.optInt("timeoutSeconds", 120),
                exactEndpoint = item.optBoolean("exactEndpoint"), allowHttp = item.optBoolean("allowHttp"),
                extraJson = item.optString("extraJson"), keyUnavailable = key.isFailure)
        } }
    }

    fun active(): AiProfile {
        val profiles = profiles()
        return profiles.firstOrNull { it.id == preferences.getString("active", "zhipu-free") }
            ?: profiles.firstOrNull() ?: AiProfile.presets().first()
    }

    fun select(id: String) {
        if (profiles().any { it.id == id }) preferences.edit().putString("active", id).apply()
    }

    @Synchronized fun save(profile: AiProfile) {
        profile.validate()
        val current = profiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index < 0) current.add(profile) else current[index] = profile
        persist(current, profile.id)
    }

    @Synchronized fun delete(id: String) {
        val current = profiles().filterNot { it.id == id }
        require(current.isNotEmpty()) { "请至少保留一份配置" }
        persist(current, if (active().id == id) current.first().id else active().id)
    }

    private fun persist(profiles: List<AiProfile>, activeId: String) {
        val array = JSONArray()
        // Preserve unreadable ciphertext in unrelated profiles until that profile is explicitly edited.
        val old = preferences.getString("profiles", null)?.let(::JSONArray)
        for (profile in profiles) {
            val oldKey = old?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optString("id") == profile.id }?.optString("key") }
            val encrypted = if (profile.keyUnavailable) oldKey.orEmpty()
                else if (profile.apiKey.isBlank()) "" else encrypt(profile.apiKey)
            array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("protocol", profile.protocol.name)
                .put("baseUrl", profile.baseUrl).put("model", profile.model).put("key", encrypted)
                .put("keyHeader", profile.keyHeader)
                .put("stream", profile.stream).put("timeoutSeconds", profile.timeoutSeconds)
                .put("exactEndpoint", profile.exactEndpoint).put("allowHttp", profile.allowHttp).put("extraJson", profile.extraJson))
        }
        check(preferences.edit().putString("profiles", array.toString()).putString("active", activeId).commit()) { "配置保存失败，请重试" }
    }

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "freehand-ai-profile-key-v1"
        private val keyLock = Any()
    }
}
