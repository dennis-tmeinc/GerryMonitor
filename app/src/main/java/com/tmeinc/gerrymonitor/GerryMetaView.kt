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

    // initial as HD image
    private var imgWidth = 1920
    private var imgHeight = 1080

    private val screenDensity =
        context.resources.displayMetrics.density

    class PosePoint {
        var x = 0.0f
        var y = 0.0f
        val isValid: Boolean
            get() = y > 0.0f || x > 0.0f
    }

    // drawing pose for single person
    private val pose = Array(18) {
        PosePoint()
    }

    private var poseList = emptyList<String>()

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = screenDensity * 3
        color = Color.RED
    }

    private val path = Path()

    // remove poses after 30 seconds
    private val resetPosesRunnable = Runnable {
        poseList = emptyList()
        postInvalidateOnAnimation()
    }

    var autoClear = true

    // may called from other thread
    fun setPoses(poses: List<Any?>) {
        poseList = List(poses.size) {
            poses[it] as? String ?: ""
        }
        // redraw pose image
        postInvalidateOnAnimation()

        // auto clear poses after 10 seconds
        if (autoClear) {
            mainHandler.removeCallbacks(resetPosesRunnable)
            mainHandler.postDelayed(resetPosesRunnable, 10000)
        }
    }

    // set background images,
    //      bgImage : scaled background image
    //      w,h : original ROC image size
    fun setBackground(bgImage: Bitmap, w: Int, h: Int) {
        imgWidth = w
        imgHeight = h

        // scaled image
        setImageBitmap(bgImage)
        postInvalidateOnAnimation()
    }

    // check if view is visible on Screen
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

        val xScale = width.toFloat() / imgWidth
        val yScale = height.toFloat() / imgHeight

        fun isPointValid(p: Int): Boolean {
            return pose[p].isValid
        }

        fun drawLine(p1: Int, p2: Int) {
            if (isPointValid(p1) && isPointValid(p2)) {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(pose[p1].x, pose[p1].y, pose[p2].x, pose[p2].y, paint)
            }
        }

        fun drawCircle(c: Int, r: Float) {
            if (isPointValid(c)) {
                canvas.drawCircle(pose[c].x, pose[c].y, r, paint)
            }
        }

        fun getBodySize(): Int {
            return 0
        }

        fun drawHead() {

            paint.color = Color.GREEN
            paint.style = Paint.Style.FILL
            drawCircle(p_l_ear, screenDensity * 4)
            drawCircle(p_r_ear, screenDensity * 4)

            paint.color = Color.BLUE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = screenDensity * 1
            drawCircle(p_l_eye, screenDensity * 2)
            drawCircle(p_r_eye, screenDensity * 2)

            paint.color = Color.YELLOW
            paint.style = Paint.Style.FILL
            drawCircle(p_nose, screenDensity * 3)
        }

        fun drawTrunk() {
            path.apply {
                rewind()

                var p = 0
                for (i in arrayOf (
                    p_neck, p_r_shoulder, p_r_hip, p_l_hip, p_l_shoulder
                )) {
                    if (isPointValid(i)) {
                        if (p == 0) {
                            moveTo(pose[i].x, pose[i].y)
                        } else {
                            lineTo(pose[i].x, pose[i].y)
                        }
                        p++
                    }
                }
                if (p > 1) {
                    close()
                    paint.color = Color.RED
                    paint.style = Paint.Style.FILL
                    canvas.drawPath(this, paint)
                }
            }
        }

        fun drawArm(p1: Int, p2: Int) {
            drawLine(p1, p2)
        }

        fun drawLeg(p1: Int, p2: Int) {
            drawLine(p1, p2)
        }

        fun drawLeg(p1: Int, p2: Int, p3: Int) {
            drawLine(p1, p2)
        }

        for (p in poseList) {
            val ps = p.split(",")
            for (i in 0 until 18) {
                if (i < ps.size / 2) {
                    pose[i].x = ps[i * 2].trim().toFloat() * xScale
                    pose[i].y = ps[i * 2 + 1].trim().toFloat() * yScale
                } else {
                    pose[i].x = 0.0f
                    pose[i].y = 0.0f
                }
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

