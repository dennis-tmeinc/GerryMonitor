package com.tmeinc.gerrymonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet

/*
 Customized widget for draw meta view
 */
class GerryMetaView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
    androidx.appcompat.widget.AppCompatImageView(context, attrs, defStyleAttr) {
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context) : this(context, null)

    data class PosePoint(val x: Float, val y: Float)

    private var imgWidth = 1280
    private var imgHeight = 720

    private val screenDensity =
        context.resources.displayMetrics.density

    private var xScale = 1.0f
    private var yScale = 1.0f

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = screenDensity * 3
        color = Color.RED
    }

    private var poseList = emptyList<List<PosePoint>>()

    // remove poses after 30 seconds
    val resetPosesRunnable = Runnable {
        poseList = emptyList<List<PosePoint>>()
        postInvalidateDelayed(1000)
    }

    // may called from other thread
    fun setPoses(poses: List<Any?>) {
        poseList = List(poses.size) { i ->
            val poseStr = poses[i]
            if (poseStr is String) {
                val p = poseStr.split(",")
                List(18) {
                    if (it < p.size / 2) {
                        PosePoint(
                            p[it * 2].trim().toFloat() * xScale,
                            p[it * 2 + 1].trim().toFloat() * yScale
                        )
                    } else {
                        PosePoint(0.0f, 0.0f)
                    }
                }
            } else {
                emptyList()
            }
        }
        postInvalidate()
        // auto clear poses after 30 seconds
        mainHandler.removeCallbacks(resetPosesRunnable)
        mainHandler.postDelayed(resetPosesRunnable, 30000)
    }

    fun setBackground(bgImage: Bitmap, w: Int, h: Int) {
        imgWidth = w
        imgHeight = h
        // scale view size
        setImageBitmap(bgImage)
    }


    fun isVisible(): Boolean {

        if (!isShown)
            return false

        val metrics = context.resources.displayMetrics
        val rect = Rect()
        getGlobalVisibleRect(rect)
        val screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        return rect.intersect(screen)
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null)
            return

        xScale = width.toFloat() / imgWidth
        yScale = height.toFloat() / imgHeight

        val pl = poseList
        for (pose in pl) {
            if (pose.size < 18)
                continue

            fun drawLine(p1: PosePoint, p2: PosePoint) {
                if (p1.x > 0 && p1.y > 0 && p2.x > 0 && p2.y > 0) {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                }
            }

            fun drawCircle(c: PosePoint, r: Float) {
                if (c.x > 0 && c.y > 0) {
                    canvas.drawCircle(c.x, c.y, r, paint)
                }
            }

            fun getCenterOfHead(): PosePoint {
                return PosePoint(0.0f, 0.0f)
            }

            fun getBodySize(): Int {
                return 0
            }

            fun drawHead() {

                paint.color = Color.GREEN
                paint.style = Paint.Style.FILL
                drawCircle(pose[p_l_ear], screenDensity * 4)
                drawCircle(pose[p_r_ear], screenDensity * 4)

                paint.color = Color.BLUE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = screenDensity * 1
                drawCircle(pose[p_l_eye], screenDensity * 2)
                drawCircle(pose[p_r_eye], screenDensity * 2)

                paint.color = Color.YELLOW
                paint.style = Paint.Style.FILL
                drawCircle(pose[p_nose], screenDensity * 3)
            }

            fun drawTrunk() {
                Path().also {
                    if (pose[p_neck].x > 0 && pose[p_neck].y > 0)
                        it.moveTo(pose[p_neck].x, pose[p_neck].y)
                    else if (pose[p_l_shoulder].x > 0 && pose[p_l_shoulder].y > 0)
                        it.moveTo(pose[p_l_shoulder].x, pose[p_l_shoulder].y)
                    else
                        it.moveTo(pose[p_r_shoulder].x, pose[p_r_shoulder].y)
                    if (pose[p_l_shoulder].x > 0 && pose[p_l_shoulder].y > 0)
                        it.lineTo(pose[p_l_shoulder].x, pose[p_l_shoulder].y)
                    if (pose[p_l_hip].x > 0 && pose[p_l_hip].y > 0)
                        it.lineTo(pose[p_l_hip].x, pose[p_l_hip].y)
                    if (pose[p_r_hip].x > 0 && pose[p_r_hip].y > 0)
                        it.lineTo(pose[p_r_hip].x, pose[p_r_hip].y)
                    if (pose[p_r_shoulder].x > 0 && pose[p_r_shoulder].y > 0)
                        it.lineTo(pose[p_r_shoulder].x, pose[p_r_shoulder].y)
                    it.close()
                    paint.color = Color.RED
                    paint.style = Paint.Style.FILL
                    canvas.drawPath(it, paint)
                }
            }

            fun drawArm(p1: Int, p2: Int) {
                drawLine(pose[p1], pose[p2])
            }

            fun drawLeg(p1: Int, p2: Int) {
                drawLine(pose[p1], pose[p2])
            }

            fun drawLeg(p1: Int, p2: Int, p3: Int) {
                drawLine(pose[p1], pose[p2])
            }

            drawHead()
            drawTrunk()

            paint.strokeWidth = screenDensity * 3
            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            drawArm(p_l_shoulder, p_l_elbow)
            drawArm(p_r_shoulder, p_r_elbow)
            drawArm(p_l_elbow, p_l_wrist)
            drawArm(p_r_elbow, p_r_wrist)

            drawLeg(p_l_hip, p_l_knee, p_r_hip)
            drawLeg(p_r_hip, p_r_knee, p_l_hip)
            drawLeg(p_l_knee, p_l_ankle)
            drawLeg(p_r_knee, p_r_ankle)

        }
    }

    companion object {
        // pose index
        const val p_nose = 0
        const val p_neck = 1

        const val p_l_eye = 15
        const val p_l_ear = 17
        const val p_l_shoulder = 5
        const val p_l_elbow = 6
        const val p_l_wrist = 7
        const val p_l_hip = 11
        const val p_l_knee = 12
        const val p_l_ankle = 13

        const val p_r_eye = 14
        const val p_r_ear = 16
        const val p_r_shoulder = 2
        const val p_r_elbow = 3
        const val p_r_wrist = 4
        const val p_r_hip = 8
        const val p_r_knee = 9
        const val p_r_ankle = 10
    }
}

