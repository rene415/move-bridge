package com.renelabs.movebridge.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Connection to this phone's own ADB daemon (Wireless debugging), used once per boot to start
 * Move Bridge's shell helper. The key pair is generated once and kept in the app's private
 * storage; pairing authorizes it with adbd, like "Always allow from this computer".
 */
class AdbManager private constructor(dir: File) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=Move Bridge")
            val now = Date()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE),
                now, Date(now.time + 10L * 365 * 86_400_000), name, kp.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            key = kp.private
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Move Bridge"

    companion object {
        @Volatile private var instance: AdbManager? = null

        fun get(c: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(c.applicationContext.noBackupFilesDir).also { instance = it }
            }
    }
}
