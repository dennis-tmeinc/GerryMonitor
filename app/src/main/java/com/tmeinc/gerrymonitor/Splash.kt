package com.tmeinc.gerrymonitor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class Splash : Activity() {

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStart = SystemClock.elapsedRealtime()
    var splashRun = false

    private fun timerRun() {
        if (splashRun) {
            if (GerryService.instance != null) {
                if (GerryService.gerryRun >= GerryService.RUN_RUN) {
                    startActivity(Intent(this, GerryMainActivity::class.java))
                    finish()
                    splashRun = false
                } else if (SystemClock.elapsedRealtime() - timerStart > 10000 || GerryService.gerryRun == GerryService.RUN_USERLOGIN) {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    splashRun = false
                }
            }
            // post dealyed
            if (splashRun)
                timerHandler.postDelayed({
                    timerRun()
                }, 200)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        if (GerryService.gerryRun == GerryService.RUN_RUN) {
            GerryService.instance
                ?.gerryHandler
                ?.sendEmptyMessage(GerryService.MSG_GERRY_GET_MDU_LIST)
        } else if (GerryService.gerryRun > 0) {
            GerryService.instance
                ?.gerryHandler
                ?.sendEmptyMessage(GerryService.MSG_GERRY_INIT)
        } else {
            // Start GerryService
            applicationContext.startService(Intent(applicationContext, GerryService::class.java))
        }

    }

    override fun onStart() {
        super.onStart()

        splashRun = true
        timerHandler.postDelayed({
            timerRun()
        }, 2000)

    }

    override fun onStop() {
        super.onStop()
        splashRun = false
        timerHandler.removeCallbacksAndMessages(null)
    }
}