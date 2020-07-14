package com.tmeinc.gerrymonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GerryService : Service() {

    // all singleton var
    companion object {

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
        val gerryAckQueue =
            LinkedBlockingQueue<GerryMsg>()           // for ack of client commands
    }

    var username = ""
    var password = ""

    // main thread handler
    val mainHandler = Handler(Looper.getMainLooper())
    var gerryHandler: Handler? = null
    private var socket = SocketChannel.open()

    // gerry receiving thread
    val readThread = Thread {
        Thread.currentThread().name = "GerryReadThread"
        // gerry reading thread
        while (gerryRun > 0) {
            while (socket.isConnected) {
                try {
                    val msg = socket.recvGerryMsg()
                    if (msg != null) {
                        if (msg.ack == GerryMsg.ACK_NONE) {
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
                            socket.sendGerryMsg(msg)
                        } else if (msg.ack > 0) {
                            gerryAckQueue.offer(msg)
                        }
                    } else {
                        if (socket.isOpen)
                            socket.close()
                        break
                    }
                } catch (e: IOException) {
                    break
                }
            }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                // Restore interrupt status.
                Thread.currentThread().interrupt()
            }
        }
        gerryAckQueue.clear()
    }

    fun showNotification(text: String, title: String = "Gerry Event", bigIcon: Int = 0) {
        mainHandler.post {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val notificationChannelId = getString(R.string.notification_channel_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = notificationChannelId
                val descriptionText = "Gerry Events Notification"
                val channel =
                    NotificationChannel(
                        notificationChannelId,
                        name,
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
    fun gerryStatus(msg: GerryMsg) {
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
    fun gerryPose(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mdu = xmlData.getLeafString("mclient/mdu")
        val camera = xmlData.getLeafInt("mclient/mdup/cam")
        val pose = xmlData.getLeafArray("mclient/mdup/poses/pose")
        synchronized(gerryMDUs) {
            val live = gerryMDUs.getLeafString("${mdu}/pose/${camera}/live")
            val cb = gerryMDUs.getLeaf("${mdu}/pose/${camera}/callback")
            if (live == "Run" && cb != null) {
                mainHandler.post {
                    (cb as (List<Any?>?) -> Unit)(pose)
                }
            }
        }
    }

    // NOTIFY_ALERT (13)
    fun gerryAlert(msg: GerryMsg) {
        val xmlData = msg.dataObj()
        val mduId = xmlData.getLeafString("mclient/ai/mdu")
        val eventType = xmlData.getLeafInt("mclient/ai/type")
        val room = xmlData.getLeafString("mclient/ai/room")
        val tm = xmlData.getLeafString("mclient/ai/tm")
        synchronized(gerryMDUs) {
            val mdu = gerryMDUs[mduId]
            if (mdu is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (mdu as MutableMap<String, Any?>).apply {
                    this["alert_ai"] = xmlData.getLeaf("mclient/ai")
                    // show notification
                    if (eventType >= 0 && eventType < event_icons.size) {
                        showNotification(
                            "in $room, at $tm",           // room, at time
                            resources.getText(event_texts[eventType]).toString(),
                            event_icons[eventType]
                        )
                    }
                }
            }
        }
    }

    // MDU_EVENT_DATA (206), should never happen as in Marcus_Client Document from Tongrui, use gerryAlert()
    fun gerryEvent(msg: GerryMsg) {
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

    // Handler that receives messages from the thread
    private inner class gerryServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (gerryRun > 0) {
                when (msg.what) {
                    MSG_GERRY_INIT -> {
                        if (gerryRun == RUN_RUN)        // already successfully connected
                            return
                        // init gerry service , in service thread

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
                        }

                        if (!readThread.isAlive)
                            readThread.start()
                        // try login
                        if (gerryRun > 0)
                            gerryRun = RUN_START
                        gerryHandler?.sendEmptyMessage(MSG_GERRY_LOGIN)

                    }

                    MSG_GERRY_LOGIN -> {                        // request gerry server login
                        if (socket.isConnected && gerryRun == RUN_RUN)        // already successfully connected
                            return

                        if (socket.isOpen)
                            socket.close()

                        if (gerryRun > 0)
                            gerryRun = RUN_CONNECT

                        if (msg.obj != null) {
                            clientID = msg.obj.getLeafString("clientId")
                            username = msg.obj.getLeafString("username")
                            password = msg.obj.getLeafString("password")
                        }

                        if (username.isBlank()) {
                            gerryRun = RUN_USERLOGIN        // wait user login input
                            return
                        }

                        // get server/port from gerryClient
                        val server = gerryClient.getLeafString("clients/${clientID}/server")
                        val port = gerryClient.getLeafInt("clients/${clientID}/port")
                        val client = gerryClient.getLeafString("clients/${clientID}/clientid")

                        if (server.isBlank() || port == 0) {
                            gerryRun = RUN_USERLOGIN        // wrong clientId
                            return
                        }

                        try {
                            socket = SocketChannel.open(InetSocketAddress(server, port))
                        } catch (e: Exception) {
                            mainHandler.post {
                                Toast.makeText(
                                    this@GerryService,
                                    "Gerry Service: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            gerryRun = RUN_USERLOGIN        // wait user login input
                            return
                        }

                        if (!socket.isConnected) {
                            gerryRun = RUN_USERLOGIN        // wait user login input
                            return
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
                                if (gerryRun > 0)
                                    gerryRun = RUN_LOGINSUCCESS        // login success
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
                                        putInt("versionCode", vInfo.versionCode)
                                        apply()
                                    }
                                }
                            }

                        }

                        if (gerryRun >= RUN_LOGINSUCCESS && socket.isConnected) {
                            gerryHandler?.sendEmptyMessage(MSG_GERRY_GET_MDU_LIST)

                            //val obj = mapOf(
                            //    "filename" to
                            //            "d:\\Marcus1\\alertPose\\00012E795D63\\pos_20200625153504_room1.gen"
                            //)
                            //gerryHandler?.obtainMessage(MSG_GERRY_READ_FILE1, obj)?.sendToTarget()

                            mainHandler.post {
                                Toast.makeText(
                                    this@GerryService,
                                    "Gerry Service Started!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                        } else {
                            // login failed!
                            if (gerryRun > 0)
                                gerryRun =
                                    RUN_USERLOGIN        // login faile, wait for user login screen
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
                            gerryHandler?.sendEmptyMessage(MSG_GERRY_LOGIN)
                        }
                        gerryHandler?.removeMessages(MSG_GERRY_KEEP_ALIVE)
                        gerryHandler?.sendEmptyMessageDelayed(
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

                            gerryHandler?.sendEmptyMessage(MSG_GERRY_EVENT_START)         // start receiving alerts

                        } else {
                            // let keep alive to check if connection is broken
                            gerryHandler?.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
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
                            if (gerryMDUs.containsKey(mdu)) {
                                if (socket.gerryCmd(
                                        GerryMsg.CLIENT_SUBJECT_STATUS_START, mapOf(
                                            "mclient" to mapOf(
                                                "mdu" to mdu
                                            )
                                        )
                                    ) == null
                                ) {
                                    // failed?
                                    synchronized(gerryMDUs) {
                                        (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                            this["status"] = "Failed"
                                            remove("status_callback")
                                        }
                                    }
                                } else {
                                    synchronized(gerryMDUs) {
                                        (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                            this["status"] = "Run"
                                            this["status_callback"] =
                                                msg.obj.getLeaf("callback")

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
                        }
                    }

                    MSG_GERRY_STATUS_STOP -> {
                        // stop all status reports, never send CLIENT_SUBJECT_STATUS_STOP
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeaf("mdu")
                            if (mdu is String && gerryMDUs.containsKey(mdu)) {
                                synchronized(gerryMDUs) {
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        this["status"] = "Stopped"
                                        this.remove("status_callback")
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
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        if (!this.containsKey("pose"))
                                            this["pose"] = mutableMapOf<String, Any>()
                                    }["pose"]
                                }
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
                                    pose.apply {
                                        this["$camera"] = mapOf(
                                            "live" to "Run",
                                            "callback" to msg.obj.getLeaf("callback")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MSG_GERRY_LIVE_STOP -> {
                        if (msg.obj is Map<*, *>) {
                            val mdu = msg.obj.getLeafString("mdu")
                            if (gerryMDUs.containsKey(mdu)) {
                                val camera = msg.obj.getLeafInt("camera")
                                val pose = synchronized(gerryMDUs) {
                                    (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                                        if (!this.containsKey("pose"))
                                            this["pose"] = mutableMapOf<String, Any>()
                                    }["pose"]
                                }
                                (pose as MutableMap<String, Any>).remove("$camera")
                                if (socket.isConnected)
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
                                            var eList = mutableListOf<Any?>()
                                            val obj = ack.dataObj()
                                            eList.addAll(obj.getLeafArray("mclient/ai"))
                                            eList.addAll(obj.getLeafArray("mclient/ei"))
                                            if (cbUpdateList != null) {
                                                mainHandler.post {
                                                    (cbUpdateList as (String, List<*>) -> Unit)(
                                                        mdu,
                                                        eList
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
                        val buf = ByteBuffer.allocate(10000000)
                        if (filename is String) {
                            var xmlObj = mapOf(
                                "mclient" to mapOf(
                                    "filename" to filename
                                )
                            )
                            var ack = socket.gerryCmd(GerryMsg.CLIENT_OPEN_READFILE, xmlObj)
                            var offset = 0
                            if (ack != null) {
                                while (buf.hasRemaining()) {
                                    val rsize = buf.remaining()
                                    val readfileMsg = GerryMsg(GerryMsg.CLIENT_READFILE)
                                    readfileMsg.qwData = offset.toLong()
                                    readfileMsg.dwData = rsize
                                    socket.sendGerryMsg(readfileMsg)
                                    ack = socket.gerryAck(readfileMsg.command)
                                    if (ack != null) {
                                        val s = ack.xData.remaining()
                                        if (s < rsize) {
                                            break       // end of file
                                        }
                                    } else {
                                        break           // error
                                    }
                                }
                            }
                            if (ack != null) {
                                socket.gerryCmd(GerryMsg.CLIENT_CLOSE_READFILE)
                            }

                        }
                    }

                    MSG_GERRY_READ_FILE -> {
                        val filename = msg.obj.getLeaf("filename")
                        if (filename is String) {
                            val buf = gerryGetFile(filename)
                            val file = File(getExternalFilesDir(null), "readfile1.gen")
                            val fOut = FileOutputStream(file)
                            fOut.write(buf.array(), buf.arrayOffset(), buf.limit())
                        }
                    }

                    else -> {
                        gerryHandler?.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
                    }
                }
            } else {
                looper.quitSafely()
            }
        }
    }

    // Periodic background work to keep connection alive
    // Define the Worker requiring input
    class KeepAliveWork(appContext: Context, workerParams: WorkerParameters) :
        Worker(appContext, workerParams) {

        override fun doWork(): Result {
            if (instance?.gerryHandler != null) {
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

    fun backgroundStart() {
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
            if (vInfo.versionCode != versionCode) {
                username = ""   // ask for user login
            }
        }

        gerryRun = RUN_START
        HandlerThread(serviceName, Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            gerryHandler = gerryServiceHandler(looper)
            gerryHandler?.sendEmptyMessage(MSG_GERRY_INIT)
        }

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
        if (readThread.isAlive)
            readThread.interrupt()
        instance = null
        gerryHandler?.looper?.quit()
        gerryHandler = null
        Toast.makeText(this, "Gerry Service Stopped!", Toast.LENGTH_LONG).show()
    }
}

// Gerry http services

// return content in ByteBuffer form pos to limit
fun gerryFileService(req: String): ByteBuffer {
    val fileService =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/fileservice")
    try {
        val rb = ByteBuffer.allocate(200000000)     // 200M buffer?
        val uri = URI("${fileService}?${req}")
        val c = uri.toURL().openConnection()
        val s = c.getInputStream()
        while (rb.hasRemaining()) {
            val r = s.read()
            if (r >= 0) {
                rb.put(r.toByte())
            } else {
                break
            }
        }
        s.close()
        rb.flip()
        return rb
    } catch (e: Exception) {
        e.toString()
    }

    return ByteBuffer.allocate(0)
}

fun gerryGetFile(path: String): ByteBuffer {
    val enc = URLEncoder.encode(path, "UTF-8")
    return gerryFileService("c=r&n=$enc")
}

fun gerryDB(sql: String): JSONObject {

    return JSONObject()
}
