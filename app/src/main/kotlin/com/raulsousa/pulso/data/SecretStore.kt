package com.raulsousa.pulso.data

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
 * Guarda a senha do broker cifrada com uma chave que nunca sai do hardware.
 *
 * A chave é gerada dentro do Android Keystore (respaldado por TEE/StrongBox nos
 * aparelhos que têm), então o material bruto não é exportável nem por um app com
 * root: o que fica em disco é apenas o texto cifrado. Escolhi implementar sobre
 * o Keystore direto em vez de usar o Jetpack Security (`EncryptedSharedPreferences`),
 * que está descontinuado — são umas trinta linhas e elimina uma dependência
 * morta do projeto.
 *
 * Limite honesto do modelo: isto protege a senha em repouso. Contra um aparelho
 * comprometido com o app rodando, nada em espaço de usuário protege.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readPassword(): String? = decrypt(prefs.getString(KEY_PASSWORD, null))

    fun writePassword(password: String?) {
        if (password.isNullOrEmpty()) {
            prefs.edit().remove(KEY_PASSWORD).apply()
            return
        }
        prefs.edit().putString(KEY_PASSWORD, encrypt(password)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // O IV é público por definição em GCM; vai junto, prefixado.
        val payload = cipher.iv + encrypted
        Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val body = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        // Nome referenciado em res/xml/backup_rules.xml para ficar fora do backup.
        const val PREFS_NAME = "pulso_secrets"
        const val KEY_PASSWORD = "broker_password"
        const val KEY_ALIAS = "pulso.broker.v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
