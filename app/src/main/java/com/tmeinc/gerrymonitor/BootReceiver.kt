package com.tmeinc.gerrymonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // start gerry service
        context.startService(Intent(context, GerryService::class.java))
        if( GerryService.gerryRun == GerryService.RUN_RUN) {
            GerryService.instance
                ?.gerryHandler
                ?.sendEmptyMessage(GerryService.MSG_GERRY_GET_MDU_LIST)
        }
        else if (GerryService.gerryRun > 0) {
            GerryService.instance
                ?.gerryHandler
                ?.sendEmptyMessage(GerryService.MSG_GERRY_INIT)
        }
        else {
            // Start GerryService
            context.applicationContext
                .startService(Intent(context.applicationContext, GerryService::class.java))
        }
    }
}
