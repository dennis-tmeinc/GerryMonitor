package com.tmeinc.gerrymonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// application wide variables

// main thread (UI) handler
val mainHandler = Handler(Looper.getMainLooper())
// cached threads for all application
val executorService = Executors.newCachedThreadPool()!!

class GerryService : Service() {

    // all singleton var
    companion object {
        // main thread handler
        const val RUN_STOP = 0                  // not running
        const val RUN_START = 1                 // starting service
        const val RUN_NO_NETWORK = 2              // no internet
        const val RUN_USER_LOGIN = 3            // require use login screen
        const val RUN_CONNECT = 4               // connecting
        const val RUN_LOGIN_SUCCESS = 5         // login succeed
        const val RUN_RUN = 6                   // service running

        const val ID_GERRY_SERVICE = 100
        const val ID_GERRY_ALERTS = 101


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
        const val MSG_GERRY_GET_CAM_INFO = 1012

        const val GERRY_KEEP_ALIVE_TIME = 600000L       // every 10m

        const val serviceName = "gerryService"
        const val gerryClientUri = "https://tme-marcus.firebaseio.com/gerryclients.json"
        const val gerryDefaultServer = "207.112.107.194"
        const val gerryDefaultPort = 48005

        var instance: GerryService? = null
        var gerryRun = RUN_STOP
        var clientID = ""
        var gerryClient = emptyMap<String, Any>()

        // list of gerry mdu
        val gerryMDUs = mutableMapOf<String, Any>()

    }


    var username = ""
    var password = ""
    var usertype = ""

    lateinit var gerryPreferences: SharedPreferences
    lateinit var gerryHandler: Handler
    private var socket = GerrySocket()
    private var statusCallback: ((String?) -> Unit)? = null

    // Gerry server command callback
    fun cbGerryCommand(msg: GerryMsg): Boolean {

        when (msg.command) {
            GerryMsg.MDU_POSE_DATA -> {
                gerryPose(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.clearData()
            }

            GerryMsg.MDU_STATUS_DATA -> {
                gerryStatus(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.clearData()
            }

            GerryMsg.MDU_EVENT_DATA -> {
                gerryEvent(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.clearData()
            }

            GerryMsg.NOTIFY_ALERT -> {
                gerryAlert(msg)
                // ack
                msg.ack = GerryMsg.ACK_SUCCESS
                msg.clearData()
            }

            GerryMsg.CLIENT_KEEPALIVE -> {
                // keep alive echo
                msg.ack = GerryMsg.ACK_SUCCESS
            }

            else -> {
                // response, unknown cmd
                msg.ack = GerryMsg.ACK_FAIL
                msg.reason = GerryMsg.REASON_UNKNOWN_COMMAND
                msg.clearData()
            }

        }

        return true
    }

    private fun createNotificationChannel() {

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // alert notification channel
            var notificationChannelId = getString(R.string.notification_channel_name)
            var descriptionText = "Gerry Events Notification"

            var channel =
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

            // forground service notification channel
            notificationChannelId = getString(R.string.notification_service_name)
            descriptionText = "Gerry Service Notification"

            channel =
                NotificationChannel(
                    notificationChannelId,
                    notificationChannelId,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = descriptionText
                }

            // Register the channel with the system
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )

        }
    }


    fun showNotification(text: String, title: String = "Gerry Event", bigIcon: Int = 0) {

        mainHandler.post {

            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val notificationChannelId = getString(R.string.notification_channel_name)
            val intent = Intent(this, GerryLiveActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
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


    fun getServiceNotification(text: String): Notification {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        val notificationChannelId = getString(R.string.notification_service_name)
        val intent = Intent(this, GerryMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_app_name))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Set the intent that will fire when the user taps the notification
            .setContentIntent(pendingIntent)

        return builder.build()
    }

    private var serviceForeground = false

    private fun showServiceNotification() {
        val pendingIntent: PendingIntent =
            Intent(this, GerryMainActivity::class.java).let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    0
                )
            }

        val notification =
            NotificationCompat.Builder(this, getString(R.string.notification_service_name))
                .setContentTitle(getString(R.string.notification_app_name))
                .setContentText(
                    when (gerryRun) {
                        RUN_STOP -> "Gerry service stopped"
                        RUN_START -> "Gerry service starting"
                        RUN_NO_NETWORK -> "No Internet"
                        RUN_LOGIN_SUCCESS -> "Gerry service connected"
                        RUN_RUN -> "Gerry service connected"
                        else -> {
                            "Gerry service disconnected"
                        }
                    }
                )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        if (serviceForeground) {
            with(NotificationManagerCompat.from(this)) {
                // each event type has same notificationId
                notify(ID_GERRY_SERVICE, notification)
            }
        } else {
            serviceForeground = true
            startForeground(ID_GERRY_SERVICE, notification)
        }
    }

    private fun showAlertNotification(ai: Map<*, *>) {

        var eventType = ai.getLeafInt("type")
        // check unsupported events

        // special case of MDU disconnected
        if (eventType == 10000) {
            eventType = 0
        }

        if (eventType < 0 || eventType >= event_texts.size) {
            return
        }

        val defaultSharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(this)
        val notificationEnabled = defaultSharedPreferences.getBoolean("notifications", true)
        if (!notificationEnabled) {
            return
        }

        val notificationSet =
            defaultSharedPreferences.getStringSet("notification_filter", null)
        if (notificationSet != null) {
            if (eventType.toString() !in notificationSet) {
                return
            }
        }

        val mdu = ai.getLeafString("mdu")
        val room = ai.getLeafString("room")
        val unit = gerryMDUs.getLeafString("${mdu}/info/unit")
        val loc = gerryMDUs.getLeafString("${mdu}/info/loc")
        val title = resources.getText(event_texts[eventType]).toString()
        val text = "in $room, ${unit}, $loc"        // room, at time
        val bigIcon = event_icons[eventType]

        // intent to run live activity
        val intent = Intent(applicationContext, GerryLiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("mdu", mdu)
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(applicationContext, 0, intent, 0)

        // the NotificationChannel class is new and not in the support library
        val notificationChannelId = getString(R.string.notification_channel_name)
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
                ResourcesCompat.getDrawable(resources, bigIcon, null)

            val bigIconBmp = drawable?.toBitmap()
            if (bigIconBmp != null)
                builder.setLargeIcon(bigIconBmp)
        } catch (e: Exception) {
        }

        with(NotificationManagerCompat.from(this)) {
            // each event type has same notificationId
            notify(ID_GERRY_ALERTS + eventType, builder.build())
        }

    }

    // gerry service receiving procedure

    // MDU_STATUS_DATA (205)
    private fun gerryStatus(msg: GerryMsg) {
        val xmlData = msg.xmlObj
        val mduId = xmlData.getLeafString("mclient/mdu")
        synchronized(gerryMDUs) {
            val mdu = gerryMDUs[mduId]
            if (mdu is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (mdu as MutableMap<String, Any?>).apply {
                    val mdup = xmlData.getLeaf("mclient/mdup")
                    if (mdup == null) {
                        remove("status_mdup")
                    } else {
                        this["status_mdup"] = mdup
                    }

                    if (this["status"] == "Run") {
                        mainHandler.post {
                            statusCallback?.invoke(mduId)
                        }
                    }
                }
            }
        }
    }

    // MDU_POSE_DATA (202)
    private fun gerryPose(msg: GerryMsg) {
        val xmlData = msg.xmlObj
        val mdu = xmlData.getLeafString("mclient/mdu")
        val camera = xmlData.getLeafInt("mclient/mdup/cam")
        val pose = xmlData.getLeafArray("mclient/mdup/poses/pose")
        synchronized(gerryMDUs) {
            val live = gerryMDUs.getLeafString("${mdu}/pose/${camera}/live")
            val cb = gerryMDUs.getLeaf("${mdu}/pose/${camera}/callback")
            if (live == "Run" && cb is Function<*>) {
                mainHandler.post {
                    @Suppress("UNCHECKED_CAST")
                    (cb as (List<*>) -> Unit)(pose)
                }
            }
        }
    }

    // NOTIFY_ALERT (13)
    private fun gerryAlert(msg: GerryMsg) {
        val xmlData = msg.xmlObj
        val ai = xmlData.getLeaf("mclient/ai")
        if (ai is Map<*, *>) {
            mainHandler.post {
                showAlertNotification(ai)
            }
        }
    }

    // MDU_EVENT_DATA (206), should never happen as in Marcus_Client Document from Tongrui, use gerryAlert()
    private fun gerryEvent(msg: GerryMsg) {
        val xmlData = msg.xmlObj
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
    fun gerryConnect(): GerrySocket? {

        var server = gerryDefaultServer
        var port = gerryDefaultPort
        var client = clientID

        // get server/port from gerryClient
        val clientEntry = gerryClient.getLeaf("clients/${client}")
        if (clientEntry is Map<*, *>) {
            server = gerryClient.getLeafString("clients/${client}/server")
            port = gerryClient.getLeafInt("clients/${client}/port")
            client = gerryClient.getLeafString("clients/${client}/clientid")
        }

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
            val ackData = ack.xmlObj
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
                usertype = ackData.getLeafString("mclient/type")
                return socket
            }
        }
        socket.close()
        return null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)

            if (gerryRun in RUN_START..RUN_CONNECT) {
                gerryHandler.sendEmptyMessage(MSG_GERRY_INIT)
            }

        }

        override fun onLost(network: Network) {
            super.onLost(network)
            gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
        }

    }

    private fun isNetworkActive(): Boolean {
        // check internet connectivity before run service
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    // GERRY Events

    // init gerry service , in service thread
    @Suppress("UNUSED_PARAMETER")
    private fun gerryInit(msg: Message) {

        if (socket.isConnected && gerryRun == RUN_RUN)        // already successfully connected
            return

        // check internet connectivity before run service
        if (!isNetworkActive()) {
            if (gerryRun > 0)
                gerryRun = RUN_NO_NETWORK
            mainHandler.post {
                // show gerry service notification
                showServiceNotification()

            }
            return
        }

        // try login
        if (gerryRun > 0)
            gerryRun = RUN_CONNECT

        // read gerry clients info from firebase
        for (loop in 0..10) {
            val jsonStr = getHttpContent(gerryClientUri)
            if (jsonStr.length > 50) {
                gerryClient = JSONObject(jsonStr).toMap()
                // save client settings
                mainHandler.post {
                    // save login info
                    gerryPreferences
                        .edit()
                        .apply {
                            putString("gerryClient", jsonStr)
                            apply()
                        }
                }
                break
            }
            Thread.sleep(500)
        }

        // Proceed login
        gerryHandler.sendEmptyMessage(MSG_GERRY_LOGIN)
    }

    private fun gerryLogin(msg: Message) {
        if (socket.isConnected && gerryRun == RUN_RUN)        // already successfully connected
            return

        if (gerryRun > 0)
            gerryRun = RUN_USER_LOGIN

        socket.close()

        if (msg.obj != null) {
            clientID = msg.obj.getLeafString("clientId")
            username = msg.obj.getLeafString("username")
            password = msg.obj.getLeafString("password")
        }

        if (username.isBlank() || clientID.isBlank()) {      // no user id
            return
        }

        val s = gerryConnect()
        if (s == null) {
            // wrong clientId
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
            gerryRun >= RUN_LOGIN_SUCCESS
            // setup gerry command callback
            socket.commandListener = {
                if (it == null) {
                    // I use null message to indicate connection lost
                    gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
                    false
                } else
                    cbGerryCommand(it)
            }
            // refresh MDUs list, will set gerryRun to RUN_RUN
            synchronized(gerryMDUs) {
                gerryMDUs.clear()
            }
            gerryHandler.sendEmptyMessage(MSG_GERRY_GET_MDU_LIST)

            // save success login info
            mainHandler.post {
                gerryPreferences.edit().apply {
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

            }
        }
    }

    // request gerry server logout
    private fun gerryLogout(msg: Message) {
        if (gerryRun > 0) {
            gerryRun = RUN_USER_LOGIN
            // close socket, try reconnect
            if (socket.isOpen)
                socket.close()
            username = ""
            password = ""
            synchronized(gerryMDUs) {
                gerryMDUs.clear()
            }
            val cb = msg.obj.getLeaf("callback")
            // clear saved login info
            mainHandler.post {
                gerryPreferences
                    .edit()
                    .apply {
                        putString("username", username)
                        putString("password", password)
                        apply()
                    }
                if (cb is Function<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (cb as () -> Unit)()
                }
            }
        }
    }

    // send out keep alive message
    @Suppress("UNUSED_PARAMETER")
    private fun gerryKeepAlive(msg: Message) {
        if (!socket.isConnected || socket.gerryCmd(GerryMsg.CLIENT_KEEPALIVE) == null) {
            // connection failed, try reconnect
            if (isNetworkActive()) {
                gerryRun = RUN_CONNECT
                gerryHandler.sendEmptyMessageDelayed(MSG_GERRY_LOGIN, 5000)
            } else {
                gerryRun = RUN_NO_NETWORK
                // clear all mdu status
                mainHandler.post {
                    for (key in gerryMDUs.keys) {
                        val mdu = gerryMDUs[key]
                        if (mdu is MutableMap<*, *>) {
                            mdu.remove("status_mdup")
                            statusCallback?.invoke(key)
                        }
                    }
                }
            }

            mainHandler.post {
                showServiceNotification()
            }
        }
        gerryHandler.removeMessages(MSG_GERRY_KEEP_ALIVE)
        gerryHandler.sendEmptyMessageDelayed(
            MSG_GERRY_KEEP_ALIVE,
            GERRY_KEEP_ALIVE_TIME
        )
    }


    // get mdu list
    private fun gerryGetMduList(@Suppress("UNUSED_PARAMETER") msg: Message) {

        val ack = socket.gerryCmd(GerryMsg.CLIENT_GET_LOC_UNIT_LIST)
        if (ack != null) {
            val l = ack.xmlObj.getLeafArray("mclient/item")
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

            // show gerry connected notification
            showServiceNotification()

            gerryHandler.sendEmptyMessage(MSG_GERRY_EVENT_START)         // start receiving alerts

        } else {
            // let keep alive to check if connection is broken
            gerryHandler.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
        }
    }


    // start event/alert
    private fun gerryEventStart(msg: Message) {
        // send  CLIENT_SUBJECT_STATUS_START, for events, as Togonrui email, date: 2020-07-02
        // Status is always enable on main socket to receive alerts
        gerryStatusStart(msg)
    }

    // start gerry live status
    private fun gerryStatusStart(msg: Message) {
        if (msg.obj is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            statusCallback = msg.obj.getLeaf("callback") as ((String?) -> Unit)?
        }
        for (mdu in gerryMDUs.keys) {
            val ack = socket.gerryCmd(
                GerryMsg.CLIENT_SUBJECT_STATUS_START, mapOf(
                    "mclient" to mapOf(
                        "mdu" to mdu
                    )
                )
            )
            @Suppress("UNCHECKED_CAST")
            (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                if (ack != null) {
                    if (msg.obj is Map<*, *>) {
                        this["status"] = "Run"
                    }
                    val mdup = ack.xmlObj.getLeaf("mclient/mdup")
                    if (mdup == null) {
                        remove("status_mdup")
                    } else {
                        this["status_mdup"] = mdup
                    }
                } else {
                    // failed
                    this["status"] = "Failed"
                    remove("status_mdup")
                }
            }
        }

        mainHandler.post {
            for (key in gerryMDUs.keys) {
                statusCallback?.invoke(key)
            }
        }
    }

    // stop status reports, never send CLIENT_SUBJECT_STATUS_STOP
    @Suppress("UNUSED_PARAMETER")
    private fun gerryStatusStop(msg: Message) {
        statusCallback = null
        synchronized(gerryMDUs) {
            for (mdu in gerryMDUs.keys) {
                @Suppress("UNCHECKED_CAST")
                (gerryMDUs[mdu] as MutableMap<String, Any?>).apply {
                    this["status"] = "Stopped"
                }
            }
        }
    }

    // start live meta view
    private fun gerryLiveStart(msg: Message) {

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

    // stop live meta view
    private fun gerryLiveStop(msg: Message) {
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


    // get events/alerts list
    private fun gerryGetEvents(msg: Message) {
        if (msg.obj is Map<*, *>) {
            var cmd = msg.obj.getLeafInt("command")
            if (cmd != GerryMsg.CLIENT_GET_ALERTS)
                cmd = GerryMsg.CLIENT_GET_EVENTS
            val cbUpdateList = msg.obj.getLeaf("cbUpdateList")
            val cbCompleteList = msg.obj.getLeaf("cbCompleteList")

            val keys = msg.obj.getLeaf("mduSet")
            if (keys is Collection<*>) {
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
                        if (ack != null && cbUpdateList is Function<*>) {
                            val obj = ack.xmlObj
                            val ai = obj.getLeafArray("mclient/ai")
                            val ei = obj.getLeafArray("mclient/ei")
                            @Suppress("UNCHECKED_CAST")
                            mainHandler.post {
                                (cbUpdateList as (String, List<*>) -> Unit)(
                                    mdu,
                                    ai
                                )
                                (cbUpdateList as (String, List<*>) -> Unit)(
                                    mdu,
                                    ei
                                )
                            }
                        }
                    }
                }
            }
            mainHandler.post {
                if (cbCompleteList is Function<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (cbCompleteList as () -> Unit)()
                }
            }
        }
    }


    // get camera list/info
    private fun gerryGetCamInfo(msg: Message) {
        if (msg.obj is Map<*, *>) {
            val mdu = msg.obj.getLeafString("mdu")
            val cbCamInfo = msg.obj.getLeaf("cbCamInfo")
            if (gerryMDUs.containsKey(mdu) && cbCamInfo is Function<*>) {
                val ack = socket.gerryCmd(
                    GerryMsg.CLIENT_GET_CAM_INFO, mapOf(
                        "mclient" to mapOf(
                            "mdu" to mdu
                        )
                    )
                )
                val camInfo = ack?.xmlObj.getLeafArray("mclient/cameras/ci")
                mainHandler.post {
                    @Suppress("UNCHECKED_CAST")
                    (cbCamInfo as (List<Any?>) -> Unit)(camInfo)
                }
            }
        }
    }

    // Handler that receives messages from the thread
    private inner class GerryServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (gerryRun > 0) {
                when (msg.what) {
                    MSG_GERRY_INIT -> {
                        // init gerry service , in service thread
                        gerryInit(msg)
                    }

                    MSG_GERRY_LOGIN -> {
                        // request gerry server login
                        gerryLogin(msg)
                        mainHandler.post {
                            // show gerry running
                            showServiceNotification()
                        }
                    }

                    MSG_GERRY_LOGOUT -> {
                        // request gerry server logout
                        gerryLogout(msg)
                        mainHandler.post {
                            // show gerry running
                            showServiceNotification()
                        }
                    }

                    MSG_GERRY_KEEP_ALIVE -> {
                        // send out keep alive message
                        gerryKeepAlive(msg)
                    }

                    MSG_GERRY_GET_MDU_LIST -> {
                        // get mdu list
                        gerryGetMduList(msg)
                    }

                    // start events/alerts
                    MSG_GERRY_EVENT_START -> {
                        // start event/alert
                        gerryEventStart(msg)
                    }

                    MSG_GERRY_STATUS_START -> {
                        // start gerry live status
                        gerryStatusStart(msg)

                    }

                    MSG_GERRY_STATUS_STOP -> {
                        // stop status reports
                        gerryStatusStop(msg)
                    }

                    MSG_GERRY_LIVE_START -> {
                        // start live metaview
                        gerryLiveStart(msg)
                    }

                    MSG_GERRY_LIVE_STOP -> {
                        // stop live meta view
                        gerryLiveStop(msg)
                    }

                    MSG_GERRY_GET_EVENTS -> {
                        // get events/alerts list
                        gerryGetEvents(msg)
                    }

                    MSG_GERRY_GET_CAM_INFO -> {
                        // get camera list/info
                        gerryGetCamInfo(msg)
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

        override fun doWork(): Result {
            if (gerryRun == RUN_RUN) {
                instance
                    ?.gerryHandler
                    ?.sendEmptyMessage(MSG_GERRY_KEEP_ALIVE)
            } else {
                // GerryService killed? try start it
                val serviceIntent = Intent(applicationContext, GerryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
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

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        gerryPreferences = EncryptedSharedPreferences.create(
            this,
            serviceName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        gerryPreferences.also {
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

        gerryPreferences.edit().apply {
            putString("clientID", clientID)
            putString("username", username)
            putString("password", password)
        }.apply()


        //---------------------

        gerryRun = RUN_START
        HandlerThread(serviceName).apply {
            start()
            gerryHandler = GerryServiceHandler(looper)
        }

        // start background periodic works (keep alive)
        backgroundStart()

        // pre-create notification channel
        createNotificationChannel()

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

        // register network connectivity call back
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)

        mainHandler.post {
            showServiceNotification()
        }

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
        gerryHandler.looper.quitSafely()
        Toast.makeText(this, "Gerry Service Stopped!", Toast.LENGTH_LONG).show()
        showServiceNotification()

        // remove network connectivity call back
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }
}

// read file through Gerry Service
fun gerryReadFileX(path: String): ByteArray {
    val bufStream = ByteArrayOutputStream()
    val gerrySocket = GerryService.instance?.gerryConnect()
    if (gerrySocket != null) {
        if (gerrySocket.gerryCmd(
                GerryMsg.CLIENT_OPEN_READFILE,
                mapOf(
                    "mclient" to mapOf(
                        "filename" to path
                    )
                )
            ) != null
        ) {
            val readMsg = GerryMsg(GerryMsg.CLIENT_READFILE)
            while (true) {
                readMsg.dwData = GerryMsg.MAX_XDATA
                readMsg.qwData = bufStream.size().toLong()
                gerrySocket.sendGerryMsg(readMsg)
                val ack = gerrySocket.gerryAck(readMsg.command)
                if (ack != null) {
                    val rs = ack.extSize
                    if (rs > 0) {
                        bufStream.write(ack.xData.array())
                    }
                    if (rs < readMsg.dwData) {
                        break
                    }
                } else {
                    break           // error
                }
            }
            // close read file, I would just close the connection to save some time
            // gerrySocket.gerryCmd(GerryMsg.CLIENT_CLOSE_READFILE)
        }
        gerrySocket.close()
    }
    return bufStream.toByteArray()
}


// read file through Gerry Service
fun gerryReadFile(path: String): ByteArray {
    GerryService.instance?.gerryConnect()?.use {
        if (it.gerryCmd(
                GerryMsg.CLIENT_OPEN_READFILE,
                mapOf(
                    "mclient" to mapOf(
                        "filename" to path
                    )
                )
            ) != null
        ) {
            val readMsg = GerryMsg(GerryMsg.CLIENT_READFILE)
            readMsg.dwData = GerryMsg.MAX_XDATA
            readMsg.qwData = 0
            it.sendGerryMsg(readMsg)
            val ack = it.gerryAck(readMsg.command)
            if (ack != null && ack.extSize > 0) {
                return ack.xData.array()
            }
            // close read file, I would just close the connection to save some time
            // gerrySocket.gerryCmd(GerryMsg.CLIENT_CLOSE_READFILE)
        }
    }
    return ByteArray(0)
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
        c?.allowUnsafe()
        val s = BufferedInputStream(c.getInputStream())
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
    val host =
        GerryService.gerryClient.getLeafString("clients/${GerryService.clientID}/db/host")
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
