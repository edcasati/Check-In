package com.example.accessiblealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.navigation.fragment.findNavController
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.location.Geocoder
import androidx.core.text.HtmlCompat

class HomeFragment : Fragment() {
    private lateinit var timeTextView: TextView
    private lateinit var dateTextView: TextView
    private lateinit var stopAlarmButton: Button
    private lateinit var nextCheckInTime: TextView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private var pressStartTime: Long = 0
    private val TAG = "HomeFragment"
    private var progressUpdateRunnable: Runnable? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var emergencyButton: Button
    private var emergencyActive = false
    private var emergencyPressTimestamps = mutableListOf<Long>()
    private var emergencyFlashing = false
    private var emergencyCountdown = 10
    private var emergencyFlashingRunnable: Runnable? = null
    private var emergencyCountdownRunnable: Runnable? = null
    private var emergencySirenPlayer: MediaPlayer? = null
    private var emergencyOriginalColor: Int = 0
    private var emergencyBrightRed: Int = Color.RED
    private var emergencyOriginalText: CharSequence? = null

    private val alarmStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlarmService.ACTION_ALARM_STATE_CHANGED) {
                val isAlarmActive = intent.getBooleanExtra("alarm_active", false)
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val isRescheduled = intent.getBooleanExtra("alarm_rescheduled", false)
                
                Log.d(TAG, "Received alarm state change: active=$isAlarmActive, id=$alarmId, rescheduled=$isRescheduled")
                
                updateButtonAppearance()
                
                // Update next check-in time if alarm was rescheduled
                if (isRescheduled) {
                    Log.d(TAG, "Alarm was rescheduled, updating next check-in time")
                    // Use a slight delay to ensure all SharedPreferences updates are complete
                    handler.postDelayed({
                        updateNextCheckInTime()
                    }, 200)
                }
            }
        }
    }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTimeAndDate()
            updateNextCheckInTime()
            updateButtonAppearance()
            handler.postDelayed(this, 1000) // Update every second
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Register for alarm state updates
        requireContext().registerReceiver(
            alarmStateReceiver,
            IntentFilter(AlarmService.ACTION_ALARM_STATE_CHANGED)
        )
        
        timeTextView = view.findViewById(R.id.current_time)
        dateTextView = view.findViewById(R.id.current_date)
        stopAlarmButton = view.findViewById(R.id.stop_alarm_button)
        nextCheckInTime = view.findViewById(R.id.next_check_in_time)
        progressBar = view.findViewById(R.id.progress_bar)
        emergencyButton = view.findViewById(R.id.extra_action_button)
        
        // Set HTML formatted text for the button
        stopAlarmButton.text = HtmlCompat.fromHtml(getString(R.string.check_in_button_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        
        // Set initial emergency button text
        emergencyOriginalColor = (emergencyButton.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.parseColor("#440000")
        emergencyOriginalText = HtmlCompat.fromHtml(
            """
            <div style='text-align: center;'>
                <big><b>EMERGENCY</b></big><br><br>
                <small>press 3 times<br>in 5 seconds</small>
            </div>
            """.trimIndent(), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        emergencyButton.text = emergencyOriginalText
        emergencyButton.setOnClickListener {
            handleEmergencyButtonPress()
        }
        
        // Initial update
        updateTimeAndDate()
        updateNextCheckInTime()
        updateButtonAppearance()
        
        // Start periodic updates
        handler.post(updateTimeRunnable)

        // Set up check-in button with long press detection
        stopAlarmButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressStartTime = System.currentTimeMillis()
                    val isAlarmActive = requireContext().getSharedPreferences("AlarmPrefs", 0)
                        .getBoolean("alarm_active", false)
                    
                    if (!isAlarmActive) {
                        // Start progress bar and change button color
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 0
                        stopAlarmButton.setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
                        
                        // Start progress updates
                        progressUpdateRunnable = object : Runnable {
                            override fun run() {
                                val elapsed = System.currentTimeMillis() - pressStartTime
                                val progress = (elapsed * 100 / 3000).toInt().coerceAtMost(100)
                                progressBar.progress = progress
                                
                                if (progress < 100) {
                                    handler.postDelayed(this, 16) // ~60fps
                                } else {
                                    // Play sound when progress reaches 100%
                                    playBeepSound()
                                }
                            }
                        }
                        handler.post(progressUpdateRunnable!!)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val pressDuration = System.currentTimeMillis() - pressStartTime
                    val isAlarmActive = requireContext().getSharedPreferences("AlarmPrefs", 0)
                        .getBoolean("alarm_active", false)

                    // Stop progress updates
                    progressUpdateRunnable?.let { handler.removeCallbacks(it) }
                    progressBar.visibility = View.GONE
                    
                    // Update button color based on alarm state
                    updateButtonAppearance()

                    if (isAlarmActive) {
                        // If alarm is active, stop it immediately with a simple tap
                        val intent = Intent(requireContext(), AlarmService::class.java)
                        intent.action = "STOP_ALARM"
                        requireContext().startService(intent)
                        
                        // Reset missed check-in counter since user manually stopped the alarm
                        SettingsFragment.resetMissedCount(requireContext())
                    } else if (pressDuration >= 3000) {
                        // If alarm is not active and button is pressed for 3 seconds, send SMS
                        val sharedPrefs = requireContext().getSharedPreferences("AlarmPrefs", 0)
                        val isSnoozing = sharedPrefs.getBoolean("is_snoozing", false)
                        
                        if (isSnoozing) {
                            // Cancel snooze alarm if we're currently snoozing
                            val snoozeAlarmId = sharedPrefs.getInt("snooze_alarm_id", -1)
                            if (snoozeAlarmId != -1) {
                                val alarmManager = AlarmManager(requireContext())
                                alarmManager.cancelAlarm(snoozeAlarmId)
                            }
                            
                            // Clear snooze state
                            sharedPrefs.edit()
                                .putBoolean("is_snoozing", false)
                                .remove("snooze_start_time")
                                .remove("snooze_alarm_id")
                                .apply()
                        }
                        
                        val intent = Intent(requireContext(), AlarmService::class.java)
                        intent.action = "SEND_SMS_WITH_RESCHEDULE"
                        requireContext().startService(intent)
                        
                        // Reset missed check-in counter
                        SettingsFragment.resetMissedCount(requireContext())
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun playBeepSound() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, R.raw.beep)
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing beep sound", e)
        }
    }

    private fun updateTimeAndDate() {
        val currentTime = Calendar.getInstance()
        timeTextView.text = timeFormat.format(currentTime.time)
        dateTextView.text = dateFormat.format(currentTime.time)
    }

    private fun updateNextCheckInTime() {
        try {
            Log.d(TAG, "updateNextCheckInTime() called")
            val sharedPrefs = requireContext().getSharedPreferences("AlarmPrefs", 0)
            val currentTime = Calendar.getInstance()
            
            // Check if we're currently snoozing
            val isSnoozing = sharedPrefs.getBoolean("is_snoozing", false)
            if (isSnoozing) {
                val snoozeStartTime = sharedPrefs.getLong("snooze_start_time", 0)
                if (snoozeStartTime > 0) {
                    val snoozeEndTime = snoozeStartTime + (5 * 60 * 1000L) // 5 minutes
                    val remainingTime = snoozeEndTime - System.currentTimeMillis()
                    
                    if (remainingTime > 0) {
                        val remainingMinutes = (remainingTime / (60 * 1000L)).toInt()
                        val remainingSeconds = ((remainingTime % (60 * 1000L)) / 1000L).toInt()
                        nextCheckInTime.text = "Snoozing (${remainingMinutes}:${String.format("%02d", remainingSeconds)})"
                        return
                    } else {
                        // Snooze time has passed, clear the snooze state
                        sharedPrefs.edit()
                            .putBoolean("is_snoozing", false)
                            .remove("snooze_start_time")
                            .remove("snooze_alarm_id")
                            .apply()
                    }
                }
            }
            
            var nextAlarmTime: Calendar? = null
            var minTimeDiff = Long.MAX_VALUE

            Log.d(TAG, "Current time: ${timeFormat.format(currentTime.time)}")

            // Check all 5 possible alarms
            for (i in 1..5) {
                val alarmTime = sharedPrefs.getString("alarm${i}_time", null)
                val isEnabled = sharedPrefs.getBoolean("alarm${i}_enabled", false)
                
                Log.d(TAG, "Checking alarm $i - Time: $alarmTime, Enabled: $isEnabled")

                if (alarmTime != null && isEnabled) {
                    // Check if there's a rescheduled time for this alarm
                    val rescheduledTime = sharedPrefs.getLong("alarm_$i", 0)
                    
                    val alarmCalendar = if (rescheduledTime > 0 && rescheduledTime > currentTime.timeInMillis) {
                        // Use the rescheduled time if it exists and is in the future
                        Log.d(TAG, "Using rescheduled time for alarm $i: ${Date(rescheduledTime)}")
                        Calendar.getInstance().apply {
                            timeInMillis = rescheduledTime
                        }
                    } else {
                        // Use the original scheduled time
                        val (alarmHour, alarmMinute) = alarmTime.split(":").map { it.toInt() }
                        Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, alarmHour)
                            set(Calendar.MINUTE, alarmMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.also { calendar ->
                            // If the time has passed today, check for tomorrow
                            if (calendar.before(currentTime)) {
                                calendar.add(Calendar.DAY_OF_YEAR, 1)
                            }
                        }
                    }

                    val timeDiff = alarmCalendar.timeInMillis - currentTime.timeInMillis
                    Log.d(TAG, "Alarm $i time: ${timeFormat.format(alarmCalendar.time)}, Time diff: $timeDiff")

                    if (timeDiff > 0 && timeDiff < minTimeDiff) {
                        minTimeDiff = timeDiff
                        nextAlarmTime = alarmCalendar
                    }
                }
            }

            val nextTimeText = if (nextAlarmTime != null) {
                timeFormat.format(nextAlarmTime.time)
            } else {
                "--:--"
            }
            Log.d(TAG, "Setting next check-in time to: $nextTimeText")
            nextCheckInTime.text = nextTimeText
            Log.d(TAG, "Next check-in time display updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating next check-in time", e)
            nextCheckInTime.text = "--:--"
        }
    }

    private fun updateButtonAppearance() {
        val isAlarmActive = requireContext().getSharedPreferences("AlarmPrefs", 0)
            .getBoolean("alarm_active", false)
        
        stopAlarmButton.setBackgroundColor(
            resources.getColor(
                if (isAlarmActive) android.R.color.holo_red_light
                else android.R.color.darker_gray,
                null
            )
        )

        // Update button text based on alarm state
        stopAlarmButton.text = if (isAlarmActive) {
            HtmlCompat.fromHtml(
                """
                <div style='text-align: center;'>
                    <big><b>ALARM ACTIVE</b></big><br>
                    <big><b>Tap to Stop</b></big><br>
                    <small>Check-In Required</small>
                </div>
                """.trimIndent(), HtmlCompat.FROM_HTML_MODE_LEGACY
            )
        } else {
            HtmlCompat.fromHtml(getString(R.string.check_in_button_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    private fun handleEmergencyButtonPress() {
        val now = System.currentTimeMillis()
        emergencyPressTimestamps.add(now)
        // Remove timestamps older than 5 seconds
        emergencyPressTimestamps = emergencyPressTimestamps.filter { now - it <= 5000 }.toMutableList()
        if (!emergencyActive && emergencyPressTimestamps.size >= 3) {
            activateEmergency()
            emergencyPressTimestamps.clear()
        } else if (emergencyActive && emergencyPressTimestamps.size >= 3) {
            deactivateEmergency()
            emergencyPressTimestamps.clear()
        }
    }

    private fun activateEmergency() {
        emergencyActive = true
        startSiren()
        startFlashing()
        startCountdown()
        setEmergencyCountdownText(emergencyCountdown)
    }

    private fun deactivateEmergency() {
        emergencyActive = false
        stopSiren()
        stopFlashing()
        stopCountdown()
        resetEmergencyButton()
    }

    private fun startSiren() {
        stopSiren()
        try {
            emergencySirenPlayer = MediaPlayer.create(context, R.raw.siren)
            emergencySirenPlayer?.isLooping = true
            emergencySirenPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren", e)
        }
    }

    private fun stopSiren() {
        try {
            emergencySirenPlayer?.stop()
            emergencySirenPlayer?.release()
            emergencySirenPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping siren", e)
        }
    }

    private fun startFlashing() {
        if (emergencyFlashing) return
        emergencyFlashing = true
        var isRed = false
        emergencyFlashingRunnable = object : Runnable {
            override fun run() {
                if (!emergencyFlashing) return
                emergencyButton.setBackgroundColor(if (isRed) emergencyOriginalColor else emergencyBrightRed)
                isRed = !isRed
                handler.postDelayed(this, 250)
            }
        }
        handler.post(emergencyFlashingRunnable!!)
    }

    private fun stopFlashing() {
        emergencyFlashing = false
        emergencyFlashingRunnable?.let { handler.removeCallbacks(it) }
        emergencyButton.setBackgroundColor(emergencyOriginalColor)
    }

    private fun startCountdown() {
        emergencyCountdown = 10
        emergencyCountdownRunnable = object : Runnable {
            override fun run() {
                if (!emergencyActive) return
                setEmergencyCountdownText(emergencyCountdown)
                if (emergencyCountdown == 0) {
                    // Send emergency SMS
                    val intent = Intent(requireContext(), AlarmService::class.java)
                    intent.action = "SEND_EMERGENCY_SMS"
                    requireContext().startService(intent)
                    
                    // Show sent confirmation
                    setEmergencySentText()
                } else {
                    emergencyCountdown--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(emergencyCountdownRunnable!!)
    }

    private fun stopCountdown() {
        emergencyCountdownRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun setEmergencyCountdownText(count: Int) {
        val text = "<div style='text-align: center;'><small><font color='#CCCCCC'>SMS sending in:</font></small><br><big><font color='#CCCCCC'>$count</font></big><br><small><font color='#CCCCCC'>press 3 times to cancel</font></small></div>"
        emergencyButton.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun setEmergencySentText() {
        val text = "<div style='text-align: center;'><small><font color='#00FF00'>EMERGENCY SMS</font></small><br><big><font color='#00FF00'>SENT!</font></big><br><small><font color='#CCCCCC'>press 3 times to cancel</font></small></div>"
        emergencyButton.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun resetEmergencyButton() {
        emergencyButton.text = emergencyOriginalText
    }

    override fun onResume() {
        super.onResume()
        // Update immediately when fragment resumes
        updateTimeAndDate()
        updateNextCheckInTime()
        updateButtonAppearance()
        handler.post(updateTimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
        progressUpdateRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            requireContext().unregisterReceiver(alarmStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateTimeRunnable)
        progressUpdateRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            requireContext().unregisterReceiver(alarmStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }
} 