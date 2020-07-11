package com.tmeinc.gerrymonitor

import android.util.Xml
import org.json.JSONArray
import org.json.JSONException
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject
import java.lang.StringBuffer
import java.io.StringReader
import java.lang.Exception
import kotlin.text.*

private fun XmlPullParser.parseXMLtoJson(): Any {
    var s = ""
    val o = JSONObject()
    while (next() != XmlPullParser.END_TAG) {
        if (eventType == XmlPullParser.START_TAG) {
            val key = name
            val v = parseXMLtoJson()
            if (o.has(key)) {
                if (o[key] is JSONArray) {
                    (o[key] as JSONArray).put(v)
                } else {
                    val a = JSONArray()
                    a.put(o[key])
                    a.put(v)
                    o.put(key, a)
                }
            } else {
                o.put(key, v)
            }
        } else if (eventType == XmlPullParser.TEXT) {
            if (!isWhitespace)
                s = text
        } else if (eventType == XmlPullParser.END_DOCUMENT) {
            break
        }
    }
    return if (o.length() > 0)
        o
    else
        s.trim()
}

fun xmlToJson(xml: String): JSONObject {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))
    val j = parser.parseXMLtoJson()
    return if (j is JSONObject)
        j
    else
        JSONObject()
}

private fun XmlPullParser.parseXMLtoMap(): Any {
    var s = ""
    val o = mutableMapOf<String, Any>()
    while (next() != XmlPullParser.END_TAG) {
        if (eventType == XmlPullParser.START_TAG) {
            val tag = name
            val vt = parseXMLtoMap()
            if (o.containsKey(tag)) {
                var l = mutableListOf<Any>()
                if (o[tag] is List<*>) {
                    l.addAll(o[tag] as List<Any>)
                } else {
                    l.add(o[tag]!!)
                }
                l.add(vt)
                o[tag] = l.toList()
            } else {
                o[tag] = vt
            }
        } else if (eventType == XmlPullParser.TEXT) {
            if (!isWhitespace)
                s = text
        } else if (eventType == XmlPullParser.END_DOCUMENT) {
            break
        }
    }
    return if (o.isEmpty())
        s.trim()
    else
        o.toMap()
}

fun xmlToMap(xml: String): Map<*, *> {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))
    return try {
        val m = parser.parseXMLtoMap()
        if (m is Map<*, *>)
            m
        else
            emptyMap<String, Any>()
    }
    catch (e:Exception){
        emptyMap<String, Any>()
    }
}

private fun StringBuffer.writeXML(obj: Any, depth: Int = 0, tag: String = "A") {
    when (obj) {
        is JSONObject -> {
            for (key in obj.keys()) {
                val o = obj[key]
                if (o is JSONArray) {
                    writeXML(obj[key], depth + 1, "$key")
                } else {
                    append("<${key}>")
                    writeXML(obj[key], depth + 1)
                    append("</${key}>")
                }
            }
        }
        is Map<*, *> -> {
            for (p in obj) {
                if (p.value is List<*>) {
                    writeXML(p.value!!, depth + 1, "${p.key}")
                } else {
                    append("<${p.key}>")
                    writeXML(p.value!!, depth + 1)
                    append("</${p.key}>")
                }
            }
        }
        is JSONArray -> {
            for (i in 0 until obj.length()) {
                append("<${tag}>")
                writeXML(obj[i])
                append("</${tag}>")
            }
        }
        is List<*> -> {
            for (o in obj) {
                append("<${tag}>")
                writeXML(o!!)
                append("</${tag}>")
            }
        }
        else -> {
            append("$obj")
        }
    }
}

/* convert Json obj or map to String */
fun objToXml(obj: Any): String {
    val buf = StringBuffer()
    buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    buf.writeXML(obj)
    return buf.toString()
}
