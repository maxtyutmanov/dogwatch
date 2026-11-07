package dogwatch.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dogwatch.core.auth.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private SharedPreferences whose values are encrypted with an AES-GCM key held in the
 * Android Keystore (the key never leaves secure hardware where the phone has it).
 *
 * If the key is lost (e.g. data restored to another device) values can't be decrypted and read as
 * null: a new pairing token is generated and viewers simply pair again.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Can't decrypt '$key', discarding it", e)
            prefs.edit().remove(key).apply()
            null
        }
    }

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit(), not apply(): a lost pairing token or session would log viewers out.
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreSecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dogwatch-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
