package com.packpeek.app.engine

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.packpeek.app.model.*
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.UUID
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.*

/**
 * Turns base + split APKs into one installable APK. The merged file is no longer the developer's
 * build: it is re-signed with a key PackPeek keeps in Android Keystore, so it cannot update the
 * original install and apps that check their own signature may refuse to run.
 */
class ApkMerger(private val context: Context) {
    /** Writes the signed result to a temporary file the caller must delete. */
    suspend fun merge(app: AppRecord, progress: suspend (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val total = app.paths.sumOf { File(it).length() }
            val directory =
                File(context.cacheDir, "merge/${UUID.randomUUID()}").apply { mkdirs() }
            // Unsigned and signed copies both live in the cache until the result is copied out.
            if (freeBytes(directory) < total * 2 + 64L * 1024 * 1024) {
                directory.deleteRecursively()
                throw AppFailure(ErrorCode.STORAGE)
            }
            val unsigned = File(directory, "unsigned.apk")
            val signed = File(directory, "merged.apk")
            try {
                ApkBundle().use { bundle ->
                    app.paths.forEachIndexed { index, path ->
                        ensureActive()
                        bundle.addModule(ApkModule.loadApkFile(File(path), "module_$index"))
                    }
                    progress(0.1f)
                    val merged = bundle.mergeModules()
                    // The developer's signing block no longer matches the merged content.
                    merged.setApkSignatureBlock(null)
                    ensureActive()
                    merged.writeApk(unsigned)
                    merged.close()
                }
                progress(0.6f)
                ensureActive()
                val (key, certificate) = MergeKey.get()
                ApkSigner.Builder(
                        listOf(
                            ApkSigner.SignerConfig.Builder("PackPeek", key, listOf(certificate))
                                .build()
                        )
                    )
                    .setInputApk(unsigned)
                    .setOutputApk(signed)
                    .setMinSdkVersion(app.minSdk.coerceAtLeast(1))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()
                    .sign()
                unsigned.delete()
                progress(1f)
                signed
            } catch (e: CancellationException) {
                directory.deleteRecursively()
                throw e
            } catch (e: OutOfMemoryError) {
                directory.deleteRecursively()
                throw AppFailure(ErrorCode.MERGE, e)
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw if (e is AppFailure) e else AppFailure(ErrorCode.MERGE, e)
            }
        }

    /** Counts cache the system may clear for us, unlike usableSpace alone. */
    private fun freeBytes(directory: File): Long =
        if (Build.VERSION.SDK_INT >= 26)
            runCatching {
                    val storage = context.getSystemService(StorageManager::class.java)
                    storage.getAllocatableBytes(storage.getUuidForPath(directory))
                }
                .getOrDefault(directory.usableSpace)
        else directory.usableSpace

    /** PackPeek's signing key for merged APKs, created once and kept in Android Keystore. */
    private object MergeKey {
        private const val ALIAS = "packpeek-merged-apk"

        @Synchronized
        fun get(): Pair<PrivateKey, X509Certificate> {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val from = Calendar.getInstance()
                val to = (from.clone() as Calendar).apply { add(Calendar.YEAR, 30) }
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                    .apply {
                        initialize(
                            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                                .setKeySize(2048)
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                                .setCertificateSubject(X500Principal("CN=PackPeek merged APK"))
                                .setCertificateSerialNumber(BigInteger.ONE)
                                .setCertificateNotBefore(from.time)
                                .setCertificateNotAfter(to.time)
                                .build()
                        )
                    }
                    .generateKeyPair()
            }
            val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return entry.privateKey to entry.certificate as X509Certificate
        }
    }
}
