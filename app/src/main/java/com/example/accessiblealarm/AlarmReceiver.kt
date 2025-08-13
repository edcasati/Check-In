package com.example.accessiblealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    private val TAG = "AlarmReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("ALARM_ID", -1)
        val isSnooze = intent.getBooleanExtra("IS_SNOOZE", false)
        Log.d(TAG, "Alarm received for ID: $alarmId, isSnooze: $isSnooze")
        
        // Start the AlarmService with START_ALARM action
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = "START_ALARM"
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_SNOOZE", isSnooze)
        }
        
        // Start the service as a foreground service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
} 