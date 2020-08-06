package com.tmeinc.gerrymonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Live meta view Fragment
 */
class LiveFragment(val mdu: String, val position: Int) : Fragment() {

    lateinit var drawView: GerryMetaView
    var cameraId = 0
    lateinit var bgImagePath: String

    private fun cbLive(poses: List<Any?>) {
        drawView.setPoses(poses)
    }

    private fun startLive() {
        val obj = mapOf(
            "mdu" to mdu,
            "camera" to cameraId,
            "callback" to { l: List<Any?> ->
                cbLive(l)
            })
        GerryService.instance
            ?.gerryHandler
            ?.obtainMessage(GerryService.MSG_GERRY_LIVE_START, obj)
            ?.sendToTarget()
    }

    private fun stopLive() {
        val obj = mapOf(
            "mdu" to mdu,
            "camera" to cameraId
        )
        GerryService.instance
            ?.gerryHandler
            ?.obtainMessage(GerryService.MSG_GERRY_LIVE_STOP, obj)
            ?.sendToTarget()
    }

    private fun getBackground() {
        val metrics = context!!.resources.displayMetrics
        val screenDensity = metrics.density
        executorService
            .submit {
                // load background files
                val bgFilename = bgImagePath
                val hash = bgFilename
                    .hashCode()
                    .toString(Character.MAX_RADIX)

                val file = File(context!!.externalCacheDir, "b${hash}")
                val cfgFile = File(context!!.externalCacheDir, "b${hash}.cfg")
                if (file.exists() && cfgFile.exists()) {
                    val fCfg = FileInputStream(cfgFile)
                    val buffer = ByteArray(2048)
                    val l = fCfg.read(buffer)
                    val cfg = String(buffer, 0, l).xmlObj()
                    val w = cfg.getLeafInt("w")
                    val h = cfg.getLeafInt("h")
                    fCfg.close()
                    if (h < 16 || w < 16) {
                        return@submit
                    }

                    val bgImage = BitmapFactory.decodeFile(file.path)
                        ?: return@submit

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
                    while (sw < w.toFloat() / scale) {
                        scale *= 2
                    }

                    opt.inJustDecodeBounds = false
                    opt.inSampleSize = scale
                    val bgImage = BitmapFactory.decodeByteArray(
                        bgData,
                        0,
                        bgData.size,
                        opt
                    ) ?: return@submit
                    drawView.post {
                        drawView.setBackground(bgImage, w, h)
                    }

                    // save image cache
                    val fOut = FileOutputStream(file)
                    bgImage.compress(Bitmap.CompressFormat.WEBP, 80, fOut)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraId = (activity as GerryLiveActivity).cameraList[position].id
        bgImagePath = (activity as GerryLiveActivity).cameraList[position].bgImg
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val fragmentView = inflater.inflate(R.layout.fragment_live, container, false)
        drawView = fragmentView.findViewById(R.id.drawView)

        getBackground()
        return fragmentView
    }

    private var drawTest = false

    override fun onResume() {
        super.onResume()
        startLive()

        // camera fake data test
        if (cameraId == 7)
            drawTest = true
        executorService
            .submit {
                // load background files
                val genFile = "d:\\Marcus1\\alertPose\\00012E795D63\\pos_20190621121246_room1.gen"
                val genXML = gerryGetFile(genFile)
                val gen = String(genXML).xmlObj()
                val camA = gen.getLeafArray("mdup/animation/camera")
                if (camA.isNotEmpty()) {
                    var fps = camA.getLeafInt("0/fps")
                    if (fps <= 0)
                        fps = 2
                    val frames = camA.getLeafArray("0/frames/frame")
                    for (frame in frames) {
                        if (drawTest) {
                            cbLive(frame.getLeafArray("pose"))
                            Thread.sleep(1000L / fps)
                        }
                    }
                }
            }

    }

    override fun onPause() {
        super.onPause()
        stopLive()

        drawTest = false
    }
}