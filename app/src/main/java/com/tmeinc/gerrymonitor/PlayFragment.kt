package com.tmeinc.gerrymonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Play alert's meta view Fragment
 */
class PlayFragment(val mdu: String, val position: Int) : Fragment() {

    lateinit var drawView: GerryMetaView
    lateinit var textTime: TextView
    lateinit var progressBar: ProgressBar

    var event: Any? = null
    var camFrames: Any? = null
    var cameraNum = 0
    private var eventTime = 0L
    private var currentFrame = 0
    private var fps = 2             // default fps
    var frameList: List<Any?> = emptyList()
    var bgImagePath = ""

    private fun getBackground() {
        val metrics = context!!.resources.displayMetrics
        val screenDensity = metrics.density
        executorService
            .submit {
                // load background files
                val bgFilename = bgImagePath
                if (bgFilename.isBlank()) {
                    return@submit
                }
                val hash = bgFilename.hashCode()

                val file = File(context!!.externalCacheDir, "bg${mdu}_${hash}")
                val cfgFile = File(context!!.externalCacheDir, "bg${mdu}_${hash}.cfg")
                if (file.exists() && cfgFile.exists()) {
                    val fCfg = FileInputStream(cfgFile)
                    val buffer = ByteArray(2048)
                    val l = fCfg.read(buffer)
                    val cfg = String(buffer, 0, l).xmlObj()
                    val w = cfg.getLeafInt("w")
                    val h = cfg.getLeafInt("h")
                    fCfg.close()

                    val bgImage = BitmapFactory.decodeFile(file.path)
                    drawView.post {
                        drawView.setBackground(bgImage, w, h)
                    }

                } else {
                    // use http is much faster, why?
                    // val bgData = gerryGetFile(bgFilename)
                    val bgData = gerryReadFile(bgFilename)
                    if (bgData.size < 8) {
                        return@submit
                    }
                    val opt = BitmapFactory.Options()
                    opt.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(
                        bgData,
                        0,
                        bgData.size,
                        opt
                    )

                    val h = opt.outHeight
                    val w = opt.outWidth
                    if (h < 16 || w < 16) {
                        return@submit
                    }

                    val sw = 2 * drawView.width / screenDensity
                    var scale = 1
                    while (sw < w.toFloat() / scale)
                        scale *= 2

                    opt.inJustDecodeBounds = false
                    opt.inSampleSize = scale
                    val bgImage = BitmapFactory.decodeByteArray(
                        bgData,
                        0,
                        bgData.size,
                        opt
                    )
                    drawView.post {
                        drawView.setBackground(bgImage, w, h)
                    }

                    // save image cache
                    val fOut = FileOutputStream(file)
                    bgImage.compress(Bitmap.CompressFormat.JPEG, 80, fOut)
                    fOut.close()

                    // save image cfg
                    val fCfg = FileOutputStream(cfgFile)
                    fCfg.write(
                        mapOf(
                            "w" to w,
                            "h" to h
                        ).toXml().toByteArray()
                    )
                    fCfg.close()
                }
            }
    }

    private fun showFrame() {
        if (frameList.isNotEmpty()) {
            if (currentFrame >= frameList.size)
                currentFrame = frameList.size - 1
            if (currentFrame < 0)
                currentFrame = 0
            val ts = eventTime + currentFrame.toLong() / fps
            drawView.setPoses(frameList[currentFrame].getLeafArray("pose"))
            textTime.text =
                SimpleDateFormat.getDateTimeInstance(
                    DateFormat.DEFAULT,
                    DateFormat.DEFAULT
                ).format(Date(1000L * ts))

            val max = progressBar.max
            var fsize = frameList.size
            if (fsize > 1)
                --fsize
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                progressBar.setProgress(max * currentFrame / fsize, true)
            } else {
                progressBar.progress = max * currentFrame / fsize
            }
        }
    }

    private val playRunnable = Runnable {
        play()
    }

    private fun play() {
        showFrame()
        mainHandler.removeCallbacks(playRunnable)
        if (++currentFrame < frameList.size)
            mainHandler.postDelayed(playRunnable, 1000L / fps)
    }

    private fun pause() {
        mainHandler.removeCallbacks(playRunnable)
    }

    private fun rewind() {
        currentFrame = 0
        showFrame()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val fragmentView = inflater.inflate(R.layout.fragment_play_event, container, false)
        drawView = fragmentView.findViewById(R.id.drawView)
        textTime = fragmentView.findViewById(R.id.textTime)
        progressBar = fragmentView.findViewById(R.id.progressBar)

        camFrames = (activity as PlayEventActivity).cameraList[position]
        cameraNum = camFrames.getLeafInt("num")
        fps = camFrames.getLeafInt("fps")
        frameList = camFrames.getLeafArray("frames/frame")

        event = (activity as PlayEventActivity).event
        eventTime = event.getLeafLong("event/ts")

        val cameraBGs = event.getLeafArray("event/camera")
        for (bg in cameraBGs) {
            val id = bg.getLeafInt("id")
            if (id == cameraNum) {
                bgImagePath = bg.getLeafString("bg")
                break
            }
        }

        // setup button callback
        var bt: ImageButton = fragmentView.findViewById(R.id.buttonRew)
        bt.setOnClickListener {
            rewind()
        }
        bt = fragmentView.findViewById(R.id.buttonPlay)
        bt.setOnClickListener {
            play()
        }
        bt = fragmentView.findViewById(R.id.buttonPause)
        bt.setOnClickListener {
            pause()
        }

        // set background image
        getBackground()
        // show first frame
        showFrame()

        return fragmentView
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }
}