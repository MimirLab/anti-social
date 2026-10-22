package app.antisocial.android

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable manual backup; device-keystore keys never leave the device. */
object BackupCrypto {
    private val magic="ANTIS01".toByteArray()
    fun encrypt(bytes: ByteArray,password: CharArray): ByteArray {
        require(password.size>=8){"Use at least eight characters"}
        val random=SecureRandom();val salt=ByteArray(16).also {random.nextBytes(it)};val iv=ByteArray(12).also {random.nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));updateAAD(magic)}
        return magic+salt+iv+cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray,password: CharArray): ByteArray {
        require(bytes.size in 51..(16*1024*1024+128) && bytes.copyOfRange(0,7).contentEquals(magic)){"This is not an anti social backup"}
        val salt=bytes.copyOfRange(7,23);val iv=bytes.copyOfRange(23,35)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));updateAAD(magic)}
        return cipher.doFinal(bytes.copyOfRange(35,bytes.size))
    }
    private fun key(password: CharArray,salt: ByteArray): SecretKeySpec {
        val spec=PBEKeySpec(password,salt,210_000,256)
        return try {SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}finally {spec.clearPassword()}
    }
}
