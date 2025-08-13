package com.example.accessiblealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val alarmManager = AlarmManager(context)
            val sharedPreferences = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)

            // Restore all saved alarms
            for (i in 0..4) {
                val alarmTime = sharedPreferences.getLong("alarm_$i", 0)
                if (alarmTime > System.currentTimeMillis()) {
                    alarmManager.scheduleAlarm(i, alarmTime)
                }
            }
        }
    }
} 