package com.tmeinc.gerrymonitor

import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLConnection
import java.security.cert.X509Certificate
import javax.net.ssl.*

fun JSONArray.toList(): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until length()) {
        when (val v = get(i)) {
            is JSONObject -> {
                list.add(v.toMap())
            }
            is JSONArray -> {
                list.add(v.toList())
            }
            JSONObject.NULL -> {        // don't support JSON NULL, just skip it
                // list.add( null )
            }
            else -> {
                list.add(v)
            }
        }
    }
    return list
}

fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    for (k in keys()) {
        when (val v = get(k)) {
            is JSONObject -> {
                map[k] = v.toMap()
            }
            is JSONArray -> {
                map[k] = v.toList()
            }
            JSONObject.NULL -> {
                // map[k]=null, just skip it
            }
            else -> {
                map[k] = v
            }
        }
    }
    return map
}

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

/*
allow self signed https
    ref: https://developer.android.com/training/articles/security-ssl
    ref: https://stackoverflow.com/questions/3761737/https-get-ssl-with-android-and-self-signed-server-certificate
 */
fun setUnsafeHttps(connection: URLConnection) {
    if (connection is HttpsURLConnection) {

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(
                    chain: Array<X509Certificate?>?,
                    authType: String?
                ) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(
                    chain: Array<X509Certificate?>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate?>? {
                    return arrayOf()
                }
            }
        ), null)

        connection.sslSocketFactory = ctx.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }     // all true
    }
}

// get self-signed https contents, only used for connection to my own server
fun getHttpContentUnSafe(url: String): String {
    try {
        val c = URI(url).toURL().openConnection()
        setUnsafeHttps(c)
        val s = BufferedInputStream(c.getInputStream())
        val bufStream = ByteArrayOutputStream()
        var r = s.read()
        while (r >= 0) {
            bufStream.write(r)
            r = s.read()
        }
        s.close()
        return bufStream.toString()
    } catch (e: Exception) {
        println("Failed to establish SSL connection to server: $e")
    }
    return ""
}

fun getHttpContent(url: String): String {
    try {
        val c = URI(url).toURL().openConnection()
        val s = BufferedInputStream(c.getInputStream())
        val bufStream = ByteArrayOutputStream()
        var r = s.read()
        while (r >= 0) {
            bufStream.write(r)
            r = s.read()
        }
        s.close()
        return bufStream.toString()
    } catch (e: Exception) {
        println("Failed to establish SSL connection to server: $e")
    }
    return ""
}