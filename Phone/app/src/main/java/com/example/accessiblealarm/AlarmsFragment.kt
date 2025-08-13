package com.example.accessiblealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class AlarmsFragment : Fragment() {
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val defaultTimes = listOf("08:00", "12:00", "16:00", "20:00", "18:00")
    private lateinit var alarmManager: android.app.AlarmManager
    private val TAG = "AlarmsFragment"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_alarms, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Initialize all alarms
            for (i in 1..5) {
                initializeAlarm(view, i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing alarms", e)
        }
    }

    private fun initializeAlarm(view: View, alarmNumber: Int) {
        try {
            val timeTextView = view.findViewById<TextView>(getAlarmTimeId(alarmNumber))
            val enabledSwitch = view.findViewById<Switch>(getAlarmEnabledId(alarmNumber))

            // Get saved time or use default
            val savedTime = getSavedTime(alarmNumber)
            val defaultTime = defaultTimes[alarmNumber - 1]
            
            if (savedTime == null) {
                // If no saved time, use default and save it
                timeTextView.text = formatTimeForDisplay(defaultTime)
                saveAlarmTime(alarmNumber, defaultTime)
                // Ensure default alarms are disabled
                enabledSwitch.isChecked = false
                saveAlarmEnabled(alarmNumber, false)
            } else {
                // Convert 24-hour format to 12-hour format for display
                timeTextView.text = formatTimeForDisplay(savedTime)
                enabledSwitch.isChecked = getSavedEnabled(alarmNumber)
                
                // Schedule alarm if enabled
                if (enabledSwitch.isChecked) {
                    val (hour, minute) = savedTime.split(":").map { it.toInt() }
                    scheduleAlarm(alarmNumber, hour, minute)
                }
            }

            // Set click listener on the time TextView
            timeTextView.setOnClickListener {
                showTimePickerDialog(alarmNumber, timeTextView)
            }

            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                saveAlarmEnabled(alarmNumber, isChecked)
                if (isChecked) {
                    val time = getSavedTime(alarmNumber) ?: defaultTime
                    val (hour, minute) = time.split(":").map { it.toInt() }
                    scheduleAlarm(alarmNumber, hour, minute)
                } else {
                    cancelAlarm(alarmNumber)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing alarm $alarmNumber", e)
        }
    }

    private fun formatTimeForDisplay(time24h: String): String {
        try {
            val (hour, minute) = time24h.split(":").map { it.toInt() }
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            return timeFormat.format(calendar.time)
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting time: $time24h", e)
            return time24h
        }
    }

    private fun scheduleAlarm(alarmNumber: Int, hour: Int, minute: Int) {
        try {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If the time has already passed today, schedule for tomorrow
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            val intent = Intent(requireContext(), AlarmReceiver::class.java).apply {
                putExtra("ALARM_ID", alarmNumber)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                alarmNumber,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            // Save alarm time
            requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("alarm_$alarmNumber", calendar.timeInMillis)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm $alarmNumber", e)
        }
    }

    private fun cancelAlarm(alarmNumber: Int) {
        try {
            val intent = Intent(requireContext(), AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                alarmNumber,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
                .edit()
                .remove("alarm_$alarmNumber")
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling alarm $alarmNumber", e)
        }
    }

    private fun showTimePickerDialog(alarmNumber: Int, timeTextView: TextView) {
        try {
            val currentTime = timeTextView.text.toString()
            val calendar = Calendar.getInstance()
            timeFormat.parse(currentTime)?.let { calendar.time = it }
            
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            val timePickerDialog = TimePickerDialog(
                requireContext(),
                R.style.CustomTimePickerDialog,
                { _, selectedHour, selectedMinute ->
                    // Save in 24-hour format internally
                    val newTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                    saveAlarmTime(alarmNumber, newTime)
                    
                    // Display in 12-hour format
                    calendar.set(Calendar.HOUR_OF_DAY, selectedHour)
                    calendar.set(Calendar.MINUTE, selectedMinute)
                    timeTextView.text = timeFormat.format(calendar.time)

                    // Reschedule alarm if enabled
                    if (getSavedEnabled(alarmNumber)) {
                        scheduleAlarm(alarmNumber, selectedHour, selectedMinute)
                    }
                },
                hour,
                minute,
                false // false for 12-hour format
            )

            // Make the dialog larger
            timePickerDialog.setOnShowListener {
                val dialog = timePickerDialog as TimePickerDialog
                val window = dialog.window
                window?.let {
                    // Set the dialog width to 90% of screen width
                    val displayMetrics = resources.displayMetrics
                    val width = (displayMetrics.widthPixels * 0.9).toInt()
                    it.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }

            timePickerDialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing time picker for alarm $alarmNumber", e)
        }
    }

    private fun getAlarmTimeId(alarmNumber: Int): Int {
        return resources.getIdentifier("alarm${alarmNumber}_time", "id", requireContext().packageName)
    }

    private fun getAlarmEnabledId(alarmNumber: Int): Int {
        return resources.getIdentifier("alarm${alarmNumber}_enabled", "id", requireContext().packageName)
    }

    private fun getSavedTime(alarmNumber: Int): String? {
        return requireContext().getSharedPreferences("AlarmPrefs", 0)
            .getString("alarm${alarmNumber}_time", null)
    }

    private fun saveAlarmTime(alarmNumber: Int, time: String) {
        requireContext().getSharedPreferences("AlarmPrefs", 0)
            .edit()
            .putString("alarm${alarmNumber}_time", time)
            .apply()
    }

    private fun getSavedEnabled(alarmNumber: Int): Boolean {
        return requireContext().getSharedPreferences("AlarmPrefs", 0)
            .getBoolean("alarm${alarmNumber}_enabled", false)
    }

    private fun saveAlarmEnabled(alarmNumber: Int, enabled: Boolean) {
        requireContext().getSharedPreferences("AlarmPrefs", 0)
            .edit()
            .putBoolean("alarm${alarmNumber}_enabled", enabled)
            .apply()
    }
} 