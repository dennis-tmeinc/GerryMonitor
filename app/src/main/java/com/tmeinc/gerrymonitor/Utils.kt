package com.tmeinc.gerrymonitor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

fun JSONArray.toList(): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until length()) {
        val v = get(i)
        when (v) {
            is JSONObject -> {
                list.add(v.toMap())
            }
            is JSONArray -> {
                list.add(v.toList())
            }
            JSONObject.NULL -> {
                // list.add( null )
            }
            else -> {
                list.add(v)
            }
        }
    }
    return list.toList()
}

fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    for (k in keys()) {
        val v = get(k)
        when (v) {
            is JSONObject -> {
                map[k] = v.toMap()
            }
            is JSONArray -> {
                map[k] = v.toList()
            }
            JSONObject.NULL -> {
                // map[k]=null
            }
            else -> {
                map[k] = v
            }
        }
    }
    return map.toMap()
}

// get a leaf JsonArray; ex: "0/resources/0/address/formattedAddress"
fun JSONArray.getLeaf(leaf: String, separator: String = "/"): Any {
    val items = leaf.split(separator, limit = 2)
    if (items.count() > 0) {
        val child: Any =
            try {
                this.get(items[0].toInt())
            } catch (e: JSONException) {
                return JSONObject.NULL
            }
        if (items.count() == 1) {
            return child
        }
        // count > 1
        if (child is JSONObject) {
            return child.getLeaf(items[1], separator)
        } else if (child is JSONArray) {
            return child.getLeaf(items[1], separator)
        }
    }
    return JSONObject.NULL
}

// get a leaf obj; ex: "resourceSets/0/resources/0/address/formattedAddress"
fun JSONObject.getLeaf(leaf: String, separator: String = "/"): Any {
    // val items = leaf.split(Regex(separator), 2)
    val items = leaf.split(separator, limit = 2)
    if (items.count() > 0) {
        var child: Any =
            try {
                this.get(items[0])
            } catch (e: JSONException) {
                return JSONObject.NULL
            }
        if (items.count() == 1) {
            return child
        }
        // count > 1
        if (child is JSONObject) {
            return child.getLeaf(items[1], separator)
        } else if (child is JSONArray) {
            return child.getLeaf(items[1], separator)
        }
    }
    return JSONObject.NULL
}

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

fun getHttpContent(url: String): String {
    var content = ""
    try {
        val c = URI(url).toURL().openConnection()
        val s = c.getInputStream()
        val buf = ByteBuffer.allocate(50000000)       // 50M buffer
        while (buf.hasRemaining()) {
            val r = s.read()
            if (r >= 0) {
                buf.put(r.toByte())
            } else {
                break
            }
        }
        s.close()
        content = String(buf.array(), buf.arrayOffset(), buf.position())
    } finally {
        return content
    }
}
