package com.tmeinc.gerrymonitor

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.StringReader
import java.io.StringWriter

private fun XmlPullParser.parseXMLtoJson():
        Any {
    var s = ""
    val o = JSONObject()
    while (next() != XmlPullParser.END_TAG) {
        if (eventType == XmlPullParser.START_TAG) {
            val key = name
            val value = parseXMLtoJson()
            if (o.has(key)) {
                if (o[key] is JSONArray) {
                    (o[key] as JSONArray).put(value)
                } else {
                    o.put(key, JSONArray().put(o[key]).put(value))
                }
            } else {
                o.put(key, value)
            }
        } else if (eventType == XmlPullParser.TEXT) {
            if (!isWhitespace) {
                s = text
            }
        } else if (eventType == XmlPullParser.END_DOCUMENT) {
            break
        }
    }
    return if (o.length() > 0) {
        o
    } else {
        s.trim()
    }
}

fun xmlToJson(xml: String): JSONObject {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))
    val j = parser.parseXMLtoJson()
    return if (j is JSONObject) {
        j
    } else {
        JSONObject()
    }
}

private fun XmlPullParser.parseXML(): Any {
    var s = ""
    val o = mutableMapOf<String, Any>()
    while (next() != XmlPullParser.END_TAG) {
        if (eventType == XmlPullParser.START_TAG) {
            val tag = name
            val value = parseXML()
            if (o.containsKey(tag)) {
                if (o[tag] is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (o[tag] as MutableList<Any>).add(value)
                } else {
                    o[tag] = mutableListOf(o[tag], value)
                }
            } else {
                o[tag] = value
            }
        } else if (eventType == XmlPullParser.TEXT) {
            if (!isWhitespace) {
                s = text
            }
        } else if (eventType == XmlPullParser.END_DOCUMENT) {
            break
        }
    }
    return if (o.isEmpty()) {
        s.trim()
    } else {
        o
    }
}

// XML string to Map
fun String.xmlObj(): Any {
    val parser = Xml.newPullParser()
    return try {
        parser.setInput(StringReader(this))
        parser.parseXML()
    } catch (e: Exception) {
        emptyMap<String, Any>()
    }
}

private fun StringWriter.writeXML(obj: Any?, depth: Int = 0, tag: String = "i") {
    when (obj) {
        is Map<*, *> -> {
            for (p in obj) {
                val value = p.value
                if (value is List<*>) {
                    writeXML(value, depth + 1, "${p.key}")
                } else {
                    write("<${p.key}>")
                    writeXML(value, depth + 1)
                    write("</${p.key}>")
                }
            }
        }
        is JSONObject -> {
            for (key in obj.keys()) {
                val value = obj[key]
                if (value is JSONArray) {
                    writeXML(value, depth + 1, key)
                } else {
                    write("<$key>")
                    writeXML(value, depth + 1)
                    write("</$key>")
                }
            }
        }
        is List<*> -> {
            for (o in obj) {
                write("<$tag>")
                writeXML(o)
                write("</$tag>")
            }
        }
        is JSONArray -> {
            for (i in 0 until obj.length()) {
                write("<$tag>")
                writeXML(obj[i])
                write("</$tag>")
            }
        }
        else -> {
            write("$obj")
        }
    }
}

/* convert Json obj or map to String */
// this is my own version without using XmlSerializer
fun objToXml(obj: Any?): String {
    val xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    return StringWriter().apply {
        write(xmlHeader)
        writeXML(obj)
    }.toString()
}

private fun XmlSerializer.tagXML(tag: String, obj: Any?) {
    startTag(null, tag)
    serializeXML(obj)
    endTag(null, tag)
}

private fun XmlSerializer.serializeXML(obj: Any?) {
    when (obj) {
        is Map<*, *> -> {
            for (key in obj.keys) {
                if (key is String) {
                    val value = obj[key]
                    if (value is List<*>) {
                        for (v in value) {
                            tagXML(key, v)
                        }
                    } else {
                        tagXML(key, value)
                    }
                }
            }
        }
        is JSONObject -> {
            for (key in obj.keys()) {
                val value = obj[key]
                if (value is JSONArray) {
                    for (i in 0 until value.length()) {
                        tagXML(key, value[i])
                    }
                } else {
                    tagXML(key, value)
                }
            }
        }
        else -> {
            text("$obj")
        }
    }
}

/* Top level 'Any' here should only be a Map or JSONObject, so I make this function private */
private fun Any.toXml(): String {
    val writer = StringWriter()
    Xml.newSerializer().let {
        it.setOutput(writer)
        it.startDocument("UTF-8", null)
        it.serializeXML(this)
        it.endDocument()
    }
    return writer.toString()
}

fun Map<*, *>.toXml(): String {
    return (this as Any).toXml()
}

fun JSONObject.toXml(): String {
    return (this as Any).toXml()
}

// get a leaf obj from Any,  ex: "resourceSets/0/resources/0/address/formattedAddress"
fun Any?.getLeaf(leafPath: String, separator: String = "/"): Any? {
    if (this == null) {
        return null
    }
    val items = leafPath.split(separator, limit = 2)
    if (items.isNotEmpty()) {
        val child =
            try {
                when (this) {
                    is Map<*, *> -> {
                        this[items[0]]
                    }
                    is JSONObject -> {
                        this[items[0]]
                    }
                    is List<*> -> {
                        this[items[0].toInt()]
                    }
                    is JSONArray -> {
                        this[items[0].toInt()]
                    }
                    else -> {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        return if (items.size == 1) {
            child
        } else {
            child?.getLeaf(items[1], separator)
        }
    }
    return null
}

fun Any?.getLeafArray(leafPath: String, separator: String = "/"): List<Any?> {
    return when (val leaf = getLeaf(leafPath, separator)) {
        is List<*> -> {
            leaf
        }
        is JSONArray -> {
            leaf.toList()
        }
        null -> {
            emptyList()
        }
        else -> {
            listOf(leaf)
        }
    }
}

fun Any?.getLeafString(leafPath: String, separator: String = "/"): String {
    return getLeaf(leafPath, separator)?.toString() ?: ""
}

fun Any?.getLeafInt(leafPath: String, separator: String = "/"): Int {
    return when (val leaf = getLeaf(leafPath, separator)) {
        is Number -> {
            leaf.toInt()
        }
        is String -> {
            try {
                leaf.trim().toInt()
            } catch (e: Exception) {
                0
            }
        }
        else -> {
            0
        }
    }
}

fun Any?.getLeafLong(leafPath: String, separator: String = "/"): Long {
    return when (val leaf = getLeaf(leafPath, separator)) {
        is Number -> {
            leaf.toLong()
        }
        is String -> {
            try {
                leaf.trim().toLong()
            } catch (e: Exception) {
                0L
            }
        }
        else -> {
            0L
        }
    }
}

fun Any?.getLeafDouble(leafPath: String, separator: String = "/"): Double {
    return when (val leaf = getLeaf(leafPath, separator)) {
        is Number -> {
            leaf.toDouble()
        }
        is String -> {
            try {
                leaf.trim().toDouble()
            } catch (e: Exception) {
                0.0
            }
        }
        else -> {
            0.0
        }
    }
}
