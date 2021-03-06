package com.tmeinc.gerrymonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if( intent.action == Intent.ACTION_USER_PRESENT  ||
            intent.action == Intent.ACTION_BOOT_COMPLETED )
        when {
            GerryService.gerryRun == GerryService.RUN_RUN -> {
                GerryService.instance
                    ?.gerryHandler
                    ?.sendEmptyMessage(GerryService.MSG_GERRY_GET_MDU_LIST)
            }
            GerryService.gerryRun > 0 -> {
                GerryService.instance
                    ?.gerryHandler
                    ?.sendEmptyMessage(GerryService.MSG_GERRY_INIT)
            }
            else -> {
                // Start GerryService
                val serviceIntent = Intent(context, GerryService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
