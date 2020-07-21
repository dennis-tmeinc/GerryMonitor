package com.tmeinc.gerrymonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// application wide variables

// main thread (UI) handler
val mainHandler = Handler(Looper.getMainLooper())
val executorService = Executors.newCachedThreadPool()!!
var gerryAppContext: Context? = null

class GerryService : Service() {

    // all singleton var
    companion object {
        // main thread handler
        val mainHandler = Handler(Looper.getMainLooper())
        const val RUN_STOP = 0                  // not running
        const val RUN_START = 1                 // starting service
        const val RUN_USERLOGIN = 2             // require use login screen
        const val RUN_CONNECT = 3               // connecting
        const val RUN_LOGINSUCCESS = 4          // login succeed
        const val RUN_RUN = 5                   // service running

        // Async msg
        const val MSG_GERRY_INIT = 1000
        const val MSG_GERRY_KEEP_ALIVE = 1002
        const val MSG_GERRY_LOGIN = 1003            // request new gerry server login
        const val MSG_GERRY_LOGOUT = 1004            // logout server
        const val MSG_GERRY_GET_MDU_LIST = 1005
        const val MSG_GERRY_EVENT_START = 1006      // never stop
        const val MSG_GERRY_STATUS_START = 1007
        const val MSG_GERRY_STATUS_STOP = 1008
        const val MSG_GERRY_LIVE_START = 1009
        const val MSG_GERRY_LIVE_STOP = 1010
        const val MSG_GERRY_GET_EVENTS = 1011       // get events or alerts (what's difference?)
        const val MSG_GERRY_READ_FILE = 1012
        const val MSG_GERRY_READ_FILE1 = 1013       // read through http file service

        const val GERRY_KEEP_ALIVE_TIME = 600000L       // every 10m

        const val serviceName = "gerryService"
        const val gerryClientUri = "https://tme-marcus.firebaseio.com/gerryclients.json"

        var gerryRun = RUN_STOP
        var instance: GerryService? = null
        var gerryClient = emptyMap<String, Any>()
        var clientID = ""

        // list of gerry mdu
        val gerryMDUs = mutableMapOf<String, Any>()
    }

    var username = ""
    var password = ""

    lateinit var gerryHandler: Handler
    private var socket = GerrySocket()

    // Gerry server command callback
    fun cbGerryCommand(msg: GerryMsg): Boolean {
        when (msg.command) {
            GerryMsg.MDU_POSE_DATA -> {
                gerryPose(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.setData()
            }
            GerryMsg.MDU_STATUS_DATA -> {
                gerryStatus(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.setData()
            }
            GerryMsg.MDU_EVENT_DATA -> {
                gerryEvent(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.setData()
            }
            GerryMsg.NOTIFY_ALERT -> {
                gerryAlert(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.setData()
            }
            GerryMsg.CLIENT_KEEPALIVE -> {
                // keep alive echo
                msg.ack = GerryMsg.ACK_SUCCESS
            }
            else -> {
                // response, unknown cmd
                msg.ack = GerryMsg.ACK_FAIL
                msg.reason = GerryMsg.REASON_UNKNOWN_COMMAND
                msg.setData()
            }
        }
        return true
    }

    fun showNotification(text: String, title: String = "Gerry Event", bigIcon: Int = 0) {
        mainHandler.post {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val notificationChannelId = getString(R.string.notification_channel_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val descriptionText = "Gerry Events Notification"
                val channel =
                    NotificationChannel(
                        notificationChannelId,
                        notificationChannelId,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = descriptionText
                        enableLights(true)
                        enableVibration(true)
                    }
                // Register the channel with the system
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                    channel
                )
            }

            val intent = Intent(this, GerryMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

            val builder = NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setDefaults(Notification.DEFAULT_ALL)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Set the intent that will fire when the user taps the notification
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            try {
                val drawable =
                    if (bigIcon == 0)
                        ResourcesCompat.getDrawable(resources, R.drawable.g_large, null)
                    else
                        ResourcesCompat.getDrawable(resources, bigIcon, null)

                val bigIconBmp = drawable?.toBitmap()
                if (bigIconBmp != null)
                    builder.setLargeIcon(bigIconBmp)
            } catch (e: Exception) {
            }

            with(NotificationManagerCompat.from(this)) {
                // notificationId is a unique int for each notification that you must define
                notify(bigIcon, builder.build())
            }
        }
    }

    fun showNotification(gerryEventType: Int, alert: Boolean = false) {
        showNotification(
            if (gerryEventType > 0 && gerryEventType < event_texts.size)
                resources.getText(event_texts[gerryEventType]).toString()
            else
                resources.getText(event_texts[0]).toString()
            ,
            if (alert)
                "ALERT"
            else
                "Event"
            ,
            if (gerryEventType > 0 && gerryEventType < event_icons.size)
                event_icons[gerryEventType]
            else
                event_icons[0]
        )
    }

    // all gerry service receiving process

    val fakeStatusXml = """
        	<mdup>
        		<unitsub>5</unitsub>
        		<rooms>
        			<room>
        				<name>Tom's bed room</name>
        				<subs>
                            <sub>1,0,20200702101010</sub>
                            <sub>2,0,20200702101010</sub>
                            <sub>3,0,20200702101010</sub>
        				</subs>
        			</room>
        			<room>
        				<name>washroom</name>
        				<subs>
                            <sub>4,0,20200702101010</sub>
                            <sub>5,0,20200702101010</sub>
        				</subs>
        			</room>
        		</rooms>
        	</mdup>
    """.trimIndent()

    // MDU_STATUS_DATA (205)
    private fun gerryStatus(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mduId = xmlData.getLeafString("mclient/mdu")
        synchronized(gerryMDUs) {
            val mdu = gerryMDUs[mduId]
            if (mdu is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (mdu as MutableMap<String, Any?>).apply {
                    this["status_mdup"] = xmlData.getLeaf("mclient/mdup")

                    // fake data for debugging
                    // this["status_mdup"] = objGetLeaf(xmlToMap(fakeStatusXml), "mdup")

                    val cb = this["status_callback"]
                    if (this["status"] == "Run" && cb != null) {
                        mainHandler.post {
                            @Suppress("UNCHECKED_CAST")
                            (cb as () -> Unit)()
                        }
                    }
                }
            }
        }
    }

    // MDU_POSE_DATA (202)
    private fun gerryPose(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mdu = xmlData.getLeafString("mclient/mdu")
        val camera = xmlData.getLeafInt("mclient/mdup/cam")
        val pose = xmlData.getLeafArray("mclient/mdup/poses/pose")
        synchronized(gerryMDUs) {
            val live = gerryMDUs.getLeafString("${mdu}/pose/${camera}/live")
            val cb = gerryMDUs.getLeaf("${mdu}/pose/${camera}/callback")
            if (live == "Run" && cb != null) {
                mainHandler.post {
                    @Suppress("UNCHECKED_CAST")
                    (cb as (List<Any?>) -> Unit)(pose)
                }
            }
        }
    }

    // NOTIFY_ALERT (13)
    private fun gerryAlert(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mduId = xmlData.getLeafString("mclient/ai/mdu")
        val mdu = gerryMDUs[mduId]
        if (mdu is Map<*, *>) {
            val eventType = xmlData.getLeafInt("mclient/ai/type")
            val room = xmlData.getLeafString("mclient/ai/room")
            val ts = xmlData.getLeafLong("mclient/ai/ts")
            val unit = mdu.getLeafString("info/unit")
            val loc = mdu.getLeafString("info/loc")

            @Suppress("UNCHECKED_CAST")
            (mdu as MutableMap<String, Any?>)["alert_ai"] = xmlData.getLeaf("mclient/ai")
            if (eventType >= 0 && eventType < event_icons.size) {
                showNotification(
                    "in $room, ${unit}, $loc",           // room, at time
                    resources.getText(event_texts[eventType]).toString(),
                    event_icons[eventType]
                )
            }
        }
    }

    // MDU_EVENT_DATA (206), should never happen as in Marcus_Client Document from Tongrui, use gerryAlert()
    private fun gerryEvent(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mduId = xmlData.getLeafString("mclient/mdu")
        synchronized(gerryMDUs) {
            val mdu = gerryMDUs[mduId]
            if (mdu is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (mdu as MutableMap<String, Any?>).apply {
                    this["event_mdup"] = xmlData.getLeaf("mclient/mdup")
                }
            }
        }
    }

    // Create a new connection to gerry server with login
    // return null if failed
    private fun gerryConnect(): GerrySocket? {
        // get server/port from gerryClient
        val server = gerryClient.getLeafString("clients/${clientID}/server")
        val port = gerryClient.getLeafInt("clients/${clientID}/port")
        val client = gerryClient.getLeafString("clients/${clientID}/clientid")

        // username , server ip are must
        if (username.isBlank() || server.isBlank() || port == 0) {
            return null
        }

        val socket = GerrySocket()
        if (!socket.connect(server, port)) {
            return null
        }

        // login request
        val ack = socket.gerryCmd(
            GerryMsg.CLIENT_LOGIN_REQUEST,
            mapOf(
                "mclient" to mapOf(
                    "cid" to client,
                    "uid" to username
                )
            )
        )
        if (ack != null) {
            val ackData = ack.dataObj()
            val challenge = ackData.getLeafString("mclient/challenge")
            val salt = ackData.getLeafString("mclient/salt")
            // digest md5
            val md = MessageDigest.getInstance("MD5")
            val m1 = "${username}:${salt}:${password}"
            val m2 = md.digest(m1.toByteArray())
            val m3 = m2.toHexString()
            val m4 = "${challenge}${m3}"
            md.reset()
            val digest = md.digest(m4.toByteArray()).toHexString()
            if (socket.gerryCmd(
                    GerryMsg.CLIENT_LOGIN_PASSWORD, mapOf(
                        "mclient" to mapOf(
                            "cid" to client,
                            "uid" to username,
                            "digest" to digest
                        )
                    )
                ) != null
            ) {
                return socket
            }
        }
        socket.close()
        return null
    }

    // Handler that receives messages from the thread
    private inner class GerryServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (gerryRun > 0) {
                when (msg.what) {
                    MSG_GERRY_INIT -> {
                        // init gerry service , in service thread

                        if (socket.isConnected && gerryRun == RUN_RUN)        // already successfully connected
                            return

                        // read gerry clients info from firebase
                        for (loop in 0..10) {
                            val jsonStr = getHttpContent(gerryClientUri)
                            if (jsonStr.length > 50) {
                                gerryClient = JSONObject(jsonStr).toMap()
                                // save client settings
                                mainHandler.post {
                                    // save login info
                                    getSharedPreferences(serviceName, MODE_PRIVATE).edit().apply {
                                        putString("gerryClient", jsonStr)
                                        apply()
                                    }
                                }
                                break
                            }
                            Thread.sleep(500)
                        }

                        // try login
                        if (gerryRun > 0)
                            gerryRun = RUN_START
                        gerryHandler.sendEmptyMessage(MSG_GERRY_LOGIN)

                    }

                    MSG_GERRY_LOGIN -> {                        // request gerry server login
                        if (socket.isConnected && gerryRun == RUN_RUN)        // already successfully connected
                            return

                        socket.close()

                        if (gerryRun > 0)
                            gerryRun = RUN_CONNECT

                        if (msg.obj != null) {
                            clientID = msg.obj.getLeafString("clientId")
                            username = msg.obj.getLeafString("username")
                            password = msg.obj.getLeafString("password")
                        }

                        val s = gerryConnect()
                        if (s == null) {
                            gerryRun = RUN_USERLOGIN        // wrong clientId
                            mainHandler.post {
                                Toast.makeText(
                                    this@GerryService,
                                    "Connection to Gerry server failed!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return
                        }

                        socket = s

                        if (gerryRun > 0) {
                            gerryRun >= RUN_LOGINSUCCESS
                            // setup gerry command callback
                            socket.commandListener = {
                                if (it == null) {
                                    // I use null message to indicate connection lost
                                    gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
                                    false
                                } else
                                    cbGerryCommand(it)
                            }
                            // will set gerryRun to RUN_RUN
                            gerryHandler.sendEmptyMessage(MSG_GERRY_GET_MDU_LIST)

                            // save success login info
                            mainHandler.post {
                                getSharedPreferences(serviceName, MODE_PRIVATE).edit().apply {
                                    putString("clientID", clientID)
                                    putString("username", username)
                                    putString("password", password)

                                    val vInfo = packageManager.getPackageInfo(
                                        packageName,
                                        0
                                    ) as PackageInfo
                                    @Suppress("DEPRECATION")
                                    putInt("versionCode", vInfo.versionCode)
                                    apply()
                                }
                                Toast.makeText(
                                    this@GerryService,
                                    "Gerry Service Started!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    MSG_GERRY_LOGOUT -> {
                        if (gerryRun > 0) {
                            // close socket, try reconnect
                            if (socket.isOpen)
                                socket.close()
                            username = ""
                            password = ""
                            gerryRun = RUN_USERLOGIN
                            // clear saved login info
                            mainHandler.post {
                                getSharedPreferences(serviceName, MODE_PRIVATE).edit().apply {
                                    putString("username", username)
                                    putString("password", password)
                                    apply()
                                }
                            }
                        }
                    }

                    MSG_GERRY_KEEP_ALIVE -> {
                        if (!socket.isConnected || socket.gerryCmd(GerryMsg.CLIENT_KEEPALIVE) == null) {
                            // connection failed, try reconnect
                            gerryRun = RUN_CONNECT
                            gerryHandler.sendEmptyMessageDelayed(MSG_GERRY_LOGIN, 5000)
                        }
                        gerryHandler.removeMessages(MSG_GERRY_KEEP_ALIVE)
                        gerryHandler.sendEmptyMessageDelayed(
                            MSG_GERRY_KEEP_ALIVE,
                            GERRY_KEEP_ALIVE_TIME
                        )
                    }

                    MSG_GERRY_GET_MDU_LIST -> {
                        val ack = socket.gerryCmd(GerryMsg.CLIENT_GET_LOC_UNIT_LIST)
                        if (ack != null) {
                            val l = ack.dataObj().getLeafArray("mclient/item")
                            synchronized(gerryMDUs) {
                                for (m in l) {
                                    val mdu = m.getLeaf("mdu")
                                    if (mdu is String) {
                                        if (!gerryMDUs.containsKey(mdu)) {
                                            gerryMDUs[mdu] = mutableMapOf<String, Any?>()
                                        }
                                        @Suppress("UNCHECKED_CAST")
                                        (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                            this["info"] = m
                                        }
                                    }
                                }
                            }

                            // set RUN_RUN here
                            if (gerryRun > 0)
                                gerryRun = RUN_RUN

                            gerryHandler.sendEmptyMessage(MSG_GERRY_EVENT_START)         // start receiving alerts

                        } else {
                            // let keep alive to check if connection is broken
                            gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
                        }
                    }

                    // start events/alerts
                    MSG_GERRY_EVENT_START -> {
                        for (mdu in gerryMDUs.keys) {
                            // send  CLIENT_SUBJECT_STATUS_START, for events, as Togonrui email, date: 2020-07-02
                            if (socket.gerryCmd(
                                    GerryMsg.CLIENT_SUBJECT_STATUS_START,
                                    mapOf(
                                        "mclient" to mapOf(
                                            "mdu" to mdu
                                        )
                                    )
                                ) != null
                            ) {
                                @Suppress("UNCHECKED_CAST")
                                (gerryMDUs[mdu] as MutableMap<String, Any?>)["event"] =
                                    "Run"  // event always running!
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                (gerryMDUs[mdu] as MutableMap<String, Any?>)["event"] = "Failed"
                            }
                        }

                    }

                    MSG_GERRY_STATUS_START -> {
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeafString("mdu")
                            val ack = socket.gerryCmd(
                                GerryMsg.CLIENT_SUBJECT_STATUS_START, mapOf(
                                    "mclient" to mapOf(
                                        "mdu" to mdu
                                    )
                                )
                            )
                            synchronized(gerryMDUs) {
                                if (gerryMDUs.containsKey(mdu)) {
                                    @Suppress("UNCHECKED_CAST")
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        val cb = msg.obj.getLeaf("callback")
                                        if (ack != null) {
                                            this["status"] = "Run"
                                            this["status_callback"] = cb
                                        } else {
                                            // failed
                                            this["status"] = "Failed"
                                            remove("status_mdup")
                                            remove("status_callback")
                                        }
                                        if (cb != null) {
                                            mainHandler.post {
                                                @Suppress("UNCHECKED_CAST")
                                                (cb as () -> Unit)()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MSG_GERRY_STATUS_STOP -> {
                        // stop all status reports, never send CLIENT_SUBJECT_STATUS_STOP
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeafString("mdu")
                            synchronized(gerryMDUs) {
                                if (gerryMDUs.containsKey(mdu)) {
                                    @Suppress("UNCHECKED_CAST")
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        this["status"] = "Stopped"
                                        remove("status_mdup")
                                        remove("status_callback")
                                    }
                                }
                            }
                        }
                    }

                    MSG_GERRY_LIVE_START -> {
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeafString("mdu")
                            if (gerryMDUs.containsKey(mdu)) {
                                val camera = msg.obj.getLeafInt("camera")
                                val pose = synchronized(gerryMDUs) {
                                    @Suppress("UNCHECKED_CAST")
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        if (!this.containsKey("pose"))
                                            this["pose"] = mutableMapOf<String, Any>()
                                    }["pose"]
                                }
                                @Suppress("UNCHECKED_CAST")
                                (pose as MutableMap<String, Any>).remove("$camera")

                                if (socket.gerryCmd(
                                        GerryMsg.CLIENT_LIVE_METAVIEW_START, mapOf(
                                            "mclient" to mapOf(
                                                "mdu" to mdu,
                                                "camera" to camera
                                            )
                                        )
                                    ) != null
                                ) {
                                    pose["$camera"] = mapOf(
                                        "live" to "Run",
                                        "callback" to msg.obj.getLeaf("callback")
                                    )
                                }
                            }
                        }
                    }

                    MSG_GERRY_LIVE_STOP -> {
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeafString("mdu")
                            if (gerryMDUs.containsKey(mdu)) {
                                val camera = msg.obj.getLeafInt("camera")
                                val camPose = synchronized(gerryMDUs) {
                                    gerryMDUs.getLeaf("${mdu}/pose/${camera}")
                                }
                                if (camPose.getLeafString("live") == "Run") {
                                    @Suppress("UNCHECKED_CAST")
                                    (camPose as MutableMap<String, Any?>).apply {
                                        remove("live")
                                        remove("callback")
                                    }

                                    socket.gerryCmd(
                                        GerryMsg.CLIENT_LIVE_METAVIEW_STOP, mapOf(
                                            "mclient" to mapOf(
                                                "mdu" to mdu,
                                                "camera" to camera
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }


                    MSG_GERRY_GET_EVENTS -> {
                        if (msg.obj is Map<*, *>) {
                            var cmd = msg.obj.getLeafInt("command")
                            if (cmd != GerryMsg.CLIENT_GET_ALERTS)
                                cmd = GerryMsg.CLIENT_GET_EVENTS
                            val cbUpdateList = msg.obj.getLeaf("cbUpdateList")
                            val cbCompleteList = msg.obj.getLeaf("cbCompleteList")

                            val keys = msg.obj.getLeaf("mduSet")
                            if (keys is Collection<*>)
                                for (mdu in keys) {
                                    if (mdu is String) {
                                        val ack = socket.gerryCmd(
                                            cmd,
                                            mapOf(
                                                "mclient" to mapOf(
                                                    "mdu" to mdu,
                                                    "start" to msg.obj.getLeafLong("start"),
                                                    "end" to msg.obj.getLeafLong("end")
                                                )
                                            )
                                        )
                                        if (ack != null) {
                                            val obj = ack.dataObj()
                                            if (cbUpdateList != null) {
                                                @Suppress("UNCHECKED_CAST")
                                                mainHandler.post {
                                                    (cbUpdateList as (String, List<*>) -> Unit)(
                                                        mdu,
                                                        obj.getLeafArray("mclient/ai")
                                                    )
                                                    (cbUpdateList as (String, List<*>) -> Unit)(
                                                        mdu,
                                                        obj.getLeafArray("mclient/ei")
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            if (cbCompleteList != null) {
                                mainHandler.post {
                                    @Suppress("UNCHECKED_CAST")
                                    (cbCompleteList as () -> Unit)()
                                }
                            }
                        }
                    }

                    MSG_GERRY_READ_FILE1 -> {
                        val filename = msg.obj.getLeaf("filename")
                        if (filename is String) {
                            var ack = socket.gerryCmd(
                                GerryMsg.CLIENT_OPEN_READFILE, mapOf(
                                    "mclient" to mapOf(
                                        "filename" to filename
                                    )
                                )
                            )
                            if (ack != null) {
                                var offset = 0L
                                val bufStream = ByteArrayOutputStream()
                                while (true) {
                                    val readMsg = GerryMsg(GerryMsg.CLIENT_READFILE)
                                    readMsg.qwData = offset
                                    readMsg.dwData = 8192
                                    socket.sendGerryMsg(readMsg)
                                    ack = socket.gerryAck(readMsg.command)
                                    if (ack != null) {
                                        val rs = ack.extSize
                                        if (rs > 0) {
                                            bufStream.write(ack.xData.array())
                                            offset += rs
                                        } else {
                                            break
                                        }
                                    } else {
                                        break           // error
                                    }
                                }
                                // close read file
                                socket.gerryCmd(GerryMsg.CLIENT_CLOSE_READFILE)
                                val fileBuf = bufStream.toByteArray()
                                val cb = msg.obj.getLeaf("callback")
                                if (cb != null) {
                                    mainHandler.post {
                                        @Suppress("UNCHECKED_CAST")
                                        (cb as (ByteArray) -> Unit)(fileBuf)
                                    }
                                }
                            }
                        }
                    }

                    MSG_GERRY_READ_FILE -> {
                        val filename = msg.obj.getLeaf("filename")
                        if (filename is String) {
                            val buf = gerryGetFile(filename)
                            val file = File(getExternalFilesDir(null), "readfile1.gen")
                            val fOut = FileOutputStream(file)
                            fOut.write(buf)
                        }
                    }

                    else -> {
                        gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
                    }
                }
            } else {
                looper.quitSafely()
            }
        }
    }

    // Periodic background work to keep connection alive
    class KeepAliveWork(appContext: Context, workerParams: WorkerParameters) :
        Worker(appContext, workerParams) {

        init {
            gerryAppContext = appContext
        }

        override fun doWork(): Result {
            if (gerryRun == RUN_RUN) {
                instance
                    ?.gerryHandler
                    ?.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
            } else {
                // GerryService killed? try start it
                applicationContext.startService(
                    Intent(
                        applicationContext,
                        GerryService::class.java
                    )
                )
            }

            return Result.success()
        }
    }

    private fun backgroundStart() {
        // Create a WorkRequest for your Worker and sending it input
        val keepAliveWorkRequest = PeriodicWorkRequest.Builder(
            KeepAliveWork::class.java,
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS * 4, TimeUnit.MILLISECONDS,
            PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS * 4, TimeUnit.MILLISECONDS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager
            .getInstance(this)
            .enqueueUniquePeriodicWork(
                "GerryKeeper",
                ExistingPeriodicWorkPolicy.REPLACE,
                keepAliveWorkRequest
            )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSharedPreferences(serviceName, MODE_PRIVATE).also {
            clientID = it.getString("clientID", "gerry")!!
            username = it.getString("username", "")!!
            password = it.getString("password", "")!!
            val gerryClientJSON = it.getString("gerryClient", "{}")!!
            gerryClient = JSONObject(gerryClientJSON).toMap()

            val versionCode = it.getInt("versionCode", 0)
            val vInfo = packageManager.getPackageInfo(packageName, 0) as PackageInfo
            @Suppress("DEPRECATION")
            if (vInfo.versionCode != versionCode) {
                username = ""   // ask for user login
            }
        }

        gerryRun = RUN_START
        HandlerThread(serviceName, Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            gerryHandler = GerryServiceHandler(looper)
            gerryHandler.sendEmptyMessage(MSG_GERRY_INIT)
        }

        gerryAppContext = applicationContext

        // start background periodic works (keep alive)
        backgroundStart()

        // testing thread for notifications
        /*
        thread {
            Thread.currentThread().name = "testRead"
            while (GerryService.instance != null) {
                try {
                    val testSocket = SocketChannel.open()
                    testSocket.connect(InetSocketAddress("192.168.119.50", 6666))
                    if (testSocket.isConnected) {
                        val str = "Connect from gerry!\n"
                        var buf = ByteBuffer.wrap(str.toByteArray())
                        testSocket.write(buf)
                        buf = ByteBuffer.allocate(1024)
                        while (testSocket.read(buf) > 0) {
                            val rdata = String(buf.array(), buf.arrayOffset(), buf.position())
                            val i = try {
                                Integer.parseInt(rdata.trim())
                            } catch (e: Exception) {
                                0
                            }
                            showNotification(i)
                            buf.rewind()
                        }
                        buf.clear()
                        showNotification("Connection Closed!", bigIcon = R.drawable.g_large)

                    }
                    testSocket.close()
                } catch (e: Exception) {
                    val ee = e
                }
            }
        }
        */

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        gerryRun = RUN_STOP
        instance = null
        socket.close()
        gerryHandler.looper.quit()
        Toast.makeText(this, "Gerry Service Stopped!", Toast.LENGTH_LONG).show()
    }
}

// Gerry http services
// return content in ByteBuffer form pos to limit
fun gerryFileService(req: String): ByteArray {
    val fileService =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/fileservice")
    try {
        val bufStream = ByteArrayOutputStream()
        val uri = URI("${fileService}?${req}")
        val c = uri.toURL().openConnection()
        setUnsafeHttps(c)
        val s = c.getInputStream()
        var r = s.read()
        while (r >= 0) {
            bufStream.write(r)
            r = s.read()
        }
        s.close()
        return bufStream.toByteArray()
    } catch (e: Exception) {
        Log.d(GerryService.serviceName, e.toString())
    }

    return byteArrayOf()
}

fun gerryGetFile(path: String): ByteArray {
    val encPath = URLEncoder.encode(path, "UTF-8")
    return gerryFileService("c=r&n=$encPath")
}

fun gerryDB(sql: String): JSONObject {
    val host = GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/db/host")
    val username =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/db/username")
    val password =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/db/password")
    val dbname =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/db/dbname")
    val encDb = URLEncoder.encode("$host:$username:$password:$dbname", "UTF-8")
    val encSql = URLEncoder.encode(sql, "UTF-8")
    val res = gerryFileService("c=db&n=${encSql}&l=$encDb")
    return JSONObject(String(res))
}
