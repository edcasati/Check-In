package com.example.accessiblealarm

import android.content.Context
import android.util.Log
import java.util.*

data class NextAlarm(val alarmId: Int, val timeInMillis: Long, val timeString: String)

class AlarmScheduleHelper(private val context: Context) {
    private val sharedPreferences = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
    private val TAG = "AlarmScheduleHelper"
    
    /**
     * Finds the next scheduled alarm
     * @return NextAlarm object with alarm ID, time in milliseconds, and formatted time string, or null if no alarms are enabled
     */
    fun findNextAlarm(): NextAlarm? {
        val currentTime = Calendar.getInstance()
        var nextAlarmTime: Calendar? = null
        var nextAlarmId = -1
        var minTimeDiff = Long.MAX_VALUE

        Log.d(TAG, "Finding next alarm from current time: ${currentTime.time}")

        // Check all 5 possible alarms
        for (i in 1..5) {
            val alarmTime = sharedPreferences.getString("alarm${i}_time", null)
            val isEnabled = sharedPreferences.getBoolean("alarm${i}_enabled", false)
            
            Log.d(TAG, "Checking alarm $i - Time: $alarmTime, Enabled: $isEnabled")

            if (alarmTime != null && isEnabled) {
                val (alarmHour, alarmMinute) = alarmTime.split(":").map { it.toInt() }
                val alarmCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarmHour)
                    set(Calendar.MINUTE, alarmMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // If the alarm time has passed today, check for tomorrow
                if (alarmCalendar.before(currentTime)) {
                    alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                val timeDiff = alarmCalendar.timeInMillis - currentTime.timeInMillis
                Log.d(TAG, "Alarm $i time: ${alarmCalendar.time}, Time diff: $timeDiff ms")

                if (timeDiff < minTimeDiff) {
                    minTimeDiff = timeDiff
                    nextAlarmTime = alarmCalendar
                    nextAlarmId = i
                }
            }
        }

        return if (nextAlarmTime != null && nextAlarmId != -1) {
            val timeString = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(nextAlarmTime.time)
            Log.d(TAG, "Next alarm found: ID=$nextAlarmId, Time=$timeString")
            NextAlarm(nextAlarmId, nextAlarmTime.timeInMillis, timeString)
        } else {
            Log.d(TAG, "No next alarm found")
            null
        }
    }
    
    /**
     * Finds the alarm after the specified alarm ID in the daily schedule
     * @param currentAlarmId The ID of the current alarm
     * @return NextAlarm object for the next alarm in sequence, or null if no more alarms today
     */
    fun findNextAlarmAfter(currentAlarmId: Int): NextAlarm? {
        val currentTime = Calendar.getInstance()
        val currentAlarmTime = sharedPreferences.getString("alarm${currentAlarmId}_time", null)
        
        if (currentAlarmTime == null) {
            Log.w(TAG, "Current alarm time not found for ID: $currentAlarmId")
            return null
        }
        
        val (currentHour, currentMinute) = currentAlarmTime.split(":").map { it.toInt() }
        val currentAlarmCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, currentHour)
            set(Calendar.MINUTE, currentMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        var nextAlarmTime: Calendar? = null
        var nextAlarmId = -1
        var minTimeDiff = Long.MAX_VALUE

        Log.d(TAG, "Finding next alarm after ID $currentAlarmId (${currentAlarmTime})")

        // Check all alarms that come after the current alarm time today
        for (i in 1..5) {
            if (i == currentAlarmId) continue // Skip the current alarm
            
            val alarmTime = sharedPreferences.getString("alarm${i}_time", null)
            val isEnabled = sharedPreferences.getBoolean("alarm${i}_enabled", false)
            
            if (alarmTime != null && isEnabled) {
                val (alarmHour, alarmMinute) = alarmTime.split(":").map { it.toInt() }
                val alarmCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarmHour)
                    set(Calendar.MINUTE, alarmMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // Only consider alarms that are later in the day than the current alarm
                if (alarmCalendar.after(currentAlarmCalendar)) {
                    val timeDiff = alarmCalendar.timeInMillis - currentTime.timeInMillis
                    Log.d(TAG, "Considering alarm $i time: ${alarmCalendar.time}, Time diff: $timeDiff ms")

                    if (timeDiff > 0 && timeDiff < minTimeDiff) {
                        minTimeDiff = timeDiff
                        nextAlarmTime = alarmCalendar
                        nextAlarmId = i
                    }
                }
            }
        }

        return if (nextAlarmTime != null && nextAlarmId != -1) {
            val timeString = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(nextAlarmTime.time)
            Log.d(TAG, "Next alarm after current found: ID=$nextAlarmId, Time=$timeString")
            NextAlarm(nextAlarmId, nextAlarmTime.timeInMillis, timeString)
        } else {
            Log.d(TAG, "No next alarm found after current alarm")
            null
        }
    }
    
    /**
     * Checks if the current time is within the specified minutes before the given alarm time
     * @param alarmTimeMillis The alarm time in milliseconds
     * @param minutesBefore How many minutes before the alarm to check
     * @return true if current time is within the window
     */
    fun isWithinMinutesBefore(alarmTimeMillis: Long, minutesBefore: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        val windowStart = alarmTimeMillis - (minutesBefore * 60 * 1000L)
        val isWithinWindow = currentTime >= windowStart && currentTime < alarmTimeMillis
        
        Log.d(TAG, "Checking if within $minutesBefore minutes before alarm:")
        Log.d(TAG, "Current time: ${Date(currentTime)}")
        Log.d(TAG, "Alarm time: ${Date(alarmTimeMillis)}")
        Log.d(TAG, "Window start: ${Date(windowStart)}")
        Log.d(TAG, "Is within window: $isWithinWindow")
        
        return isWithinWindow
    }
}
