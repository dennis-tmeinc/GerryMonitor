package com.tmeinc.gerrymonitor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.*

class Splash : Activity() {

    private var timerStart = SystemClock.elapsedRealtime()
    private var splashRun = false

    private fun timerRun() {
        if (splashRun) {
            if (GerryService.instance != null) {
                if (GerryService.gerryRun >= GerryService.RUN_RUN) {
                    startActivity(Intent(this, GerryMainActivity::class.java))
                    finish()
                    splashRun = false
                } else if (SystemClock.elapsedRealtime() - timerStart > 10000 || GerryService.gerryRun == GerryService.RUN_USER_LOGIN) {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    splashRun = false
                }
            }
            // post dealyed
            if (splashRun)
                mainHandler.postDelayed({
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
            val serviceIntent = Intent(this, GerryService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent);
            }
        }

    }

    override fun onStart() {
        super.onStart()

        splashRun = true
        mainHandler.postDelayed({
            timerRun()
        }, 2000)

        // do some cache cleaning
        executorService
            .submit {
                val t = Date().time
                val cacheFiles = externalCacheDir?.listFiles() ?: emptyArray()
                for (file in cacheFiles) {
                    if (t - file.lastModified() > 5 * 24 * 3600 * 1000L)
                        file.delete()
                }
            }
    }

    override fun onStop() {
        super.onStop()
        splashRun = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}