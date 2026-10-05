package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** CARTO checks the installed package and its current signing certificate. */
internal class CartoAuthentication(private val context: Context) {
    val headers: Map<String, String> by lazy {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty()
        }
        // apkContentsSigners describes the current signer, unlike signingCertificateHistory.
        val certificate = signatures.firstOrNull()?.let {
            MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
        }.orEmpty()
        mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to certificate)
    }

    companion object {
        fun isCartoHost(host: String): Boolean =
            host == "basemaps.cartocdn.com" || host.endsWith(".basemaps.cartocdn.com")
    }
}
