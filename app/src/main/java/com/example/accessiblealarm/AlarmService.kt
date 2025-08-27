package com.example.accessiblealarm

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.os.Build
import android.widget.Toast
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.text.SimpleDateFormat
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.content.Context
import android.media.AudioManager
import android.location.Geocoder
import android.os.Looper
import kotlinx.coroutines.Job
import android.app.AlarmManager
import android.app.PendingIntent
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import java.io.IOException

class AlarmService : Service(), SensorEventListener {
    private var mediaPlayer: MediaPlayer? = null
    private var alarmId: Int = -1
    private var alarmStartTime: Long = 0
    private var isManuallyStopped = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var alarmTimeoutJob: Job? = null
    private val TAG = "AlarmService"
    private val ALARM_TIMEOUT = 60000L
    private val SNOOZE_DURATION = 5 * 60 * 1000L // 5 minutes in milliseconds
    private var isSnoozeAttempt = false
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastOrientation = -1
    private var orientationChangeTime: Long = 0
    private val ORIENTATION_CHANGE_THRESHOLD = 500L
    private lateinit var audioManager: AppAudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ERROR_NO_PHONE_NUMBER = "No phone number configured"
        const val ERROR_SMS_PERMISSION = "SMS permission not granted"
        const val ERROR_NETWORK = "Network connection error"
        const val ERROR_UNKNOWN = "Unknown error occurred"
        const val CHANNEL_ID = "AlarmChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_ALARM_STATE_CHANGED = "com.example.accessiblealarm.ALARM_STATE_CHANGED"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AlarmService created")
        createNotificationChannel()
        initializeMediaPlayer()
        initializeSensors()
        audioManager = AppAudioManager(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Channel",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for alarm notifications"
                enableVibration(true)
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeMediaPlayer() {
        try {
            // Release any existing MediaPlayer
            mediaPlayer?.release()
            mediaPlayer = null
            
            // Create new MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                // Set data source manually for better control
                val afd = applicationContext.resources.openRawResourceFd(R.raw.alarm)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                
                // Configure audio attributes for Android 15
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(android.media.AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_ALARM)
                }
                
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                
                // Set volume to maximum
                setVolume(1.0f, 1.0f)
                
                // Prepare the MediaPlayer
                prepare()
                
                Log.d(TAG, "MediaPlayer initialized successfully with audio attributes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaPlayer", e)
            mediaPlayer = null
            
            // Fallback to simple MediaPlayer.create
            try {
                Log.d(TAG, "Trying fallback MediaPlayer creation")
                mediaPlayer = MediaPlayer.create(this, R.raw.alarm)
                mediaPlayer?.apply {
                    setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                    isLooping = true
                    setVolume(1.0f, 1.0f)
                    Log.d(TAG, "Fallback MediaPlayer created successfully")
                }
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback MediaPlayer creation also failed", fallbackError)
                mediaPlayer = null
            }
        }
        
        // Acquire wake lock for background operations
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AccessibleAlarm:AlarmService"
        )
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AlarmService onStartCommand called with action: ${intent?.action}")
        
        when (intent?.action) {
            "START_ALARM" -> {
                alarmId = intent.getIntExtra("ALARM_ID", -1)
                isSnoozeAttempt = intent.getBooleanExtra("IS_SNOOZE", false)
                startAlarm()
            }
            "STOP_ALARM" -> {
                stopAlarm()
                // Send SMS when alarm is stopped manually
                sendCheckInSMS()
                stopSelf()
            }
            "SEND_SMS" -> {
                // Handle manual check-in SMS sending
                sendCheckInSMS()
                stopSelf()
            }
            "SEND_EMERGENCY_SMS" -> {
                // Handle emergency SMS sending
                sendEmergencySMS()
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun startAlarm() {
        try {
            // Acquire wake lock to ensure background operation
            wakeLock?.takeIf { !it.isHeld }?.acquire(10 * 60 * 1000L /*10 minutes*/)
            
            // Record alarm start time
            alarmStartTime = System.currentTimeMillis()
            
            // Set alarm as active and clear snooze state if this is a snooze alarm starting
            val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE).edit()
                .putBoolean("alarm_active", true)
            
            if (isSnoozeAttempt) {
                prefs.putBoolean("is_snoozing", false)
                    .remove("snooze_start_time")
                    .remove("snooze_alarm_id")
            }
            prefs.apply()

            // Get the saved volume setting
            val volume = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                .getInt("alarm_volume", 100) // Default to max volume

            // Set the volume and request audio focus
            audioManager.setAppVolume(volume)
            audioManager.requestAudioFocus()
            
            // Check if Do Not Disturb is blocking alarms
            val androidAudioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val ringerMode = androidAudioManager.ringerMode
            Log.d(TAG, "Current ringer mode: $ringerMode (0=silent, 1=vibrate, 2=normal)")
            
            if (ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) {
                Log.w(TAG, "Device is in silent mode, alarm may not be audible")
            }
            
            // Check if we have notification policy access (required for Do Not Disturb bypass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (!notificationManager.isNotificationPolicyAccessGranted) {
                    Log.w(TAG, "No notification policy access granted - alarm may be blocked by Do Not Disturb")
                }
            }

            // Broadcast alarm state change
            sendBroadcast(Intent(ACTION_ALARM_STATE_CHANGED).apply {
                putExtra("alarm_active", true)
                putExtra("alarm_id", alarmId)
            })

            // Start the alarm sound
            try {
                if (mediaPlayer != null) {
                    if (!mediaPlayer!!.isPlaying) {
                        mediaPlayer!!.start()
                        Log.d(TAG, "Alarm sound started successfully")
                    } else {
                        Log.d(TAG, "MediaPlayer is already playing")
                    }
                } else {
                    Log.e(TAG, "MediaPlayer is null, cannot start alarm sound")
                    // Try to reinitialize MediaPlayer
                    initializeMediaPlayer()
                    mediaPlayer?.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting alarm sound", e)
            }
            
            // Create and show notification  
            val notificationTitle = if (isSnoozeAttempt) {
                "Snooze Alarm (Final Attempt)"
            } else {
                getString(R.string.alarm_notification_title)
            }
            
            val notificationText = if (isSnoozeAttempt) {
                "Final chance to check in!"
            } else {
                getString(R.string.alarm_notification_text)
            }
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setSmallIcon(R.drawable.ic_alarm)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            
            // Cancel any previous timeout job
            alarmTimeoutJob?.cancel()
            // Start timeout coroutine
            alarmTimeoutJob = serviceScope.launch {
                delay(ALARM_TIMEOUT)
                if (!isManuallyStopped) {
                    if (!isSnoozeAttempt) {
                        // First attempt - snooze for 5 minutes
                        Log.d(TAG, "First alarm attempt timed out, scheduling snooze")
                        stopAlarm()
                        scheduleSnoozeAlarm()
                        stopSelf()
                    } else {
                        // Snooze attempt also timed out - final failure
                        Log.d(TAG, "Snooze attempt timed out, final failure")
                        stopAlarm()
                        
                        // Send failure SMS and then stop service
                        serviceScope.launch {
                            try {
                                sendFailureSMS()
                                // Wait a moment to ensure SMS is sent
                                delay(1000)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending failure SMS", e)
                            }
                            
                            // Show timeout message
                            withContext(Dispatchers.Main) {
                                val username = getSharedPreferences("AlarmPrefs", MODE_PRIVATE).getString("username", "") ?: ""
                                val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                                Toast.makeText(
                                    applicationContext,
                                    getString(R.string.alarm_expired, username, currentTime),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            
                            // Now stop the service
                            stopSelf()
                        }
                    }
                }
            }

            // Register sensor listener
            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting alarm", e)
            stopSelf()
        }
    }

    private fun stopAlarm() {
        try {
            // Store the current state before changing it
            isManuallyStopped = true
            
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // Restore original volume and abandon audio focus
            audioManager.restoreOriginalVolume()
            audioManager.abandonAudioFocus()
            
            // Unregister sensor listener
            sensorManager?.unregisterListener(this)
            
            // Set alarm as inactive and clear snooze state
            getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                .edit()
                .putBoolean("alarm_active", false)
                .putBoolean("is_snoozing", false)
                .remove("snooze_start_time")
                .remove("snooze_alarm_id")
                .apply()

            // Broadcast alarm state change
            sendBroadcast(Intent(ACTION_ALARM_STATE_CHANGED).apply {
                putExtra("alarm_active", false)
                putExtra("alarm_id", alarmId)
            })

            // Reset snooze state when alarm is manually stopped
            isSnoozeAttempt = false

            // Cancel the timeout coroutine if running
            alarmTimeoutJob?.cancel()
            alarmTimeoutJob = null
            
            // Release wake lock
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm", e)
        }
    }

    private fun scheduleSnoozeAlarm() {
        try {
            val snoozeTime = System.currentTimeMillis() + SNOOZE_DURATION
            val alarmManager = AlarmManager(this)
            
            Log.d(TAG, "Scheduling snooze alarm for ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(snoozeTime))}")
            
            // Save snooze state to SharedPreferences
            getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                .edit()
                .putBoolean("is_snoozing", true)
                .putLong("snooze_start_time", System.currentTimeMillis())
                .putInt("snooze_alarm_id", alarmId)
                .apply()
            
            // Cancel the original alarm first to avoid conflicts
            alarmManager.cancelAlarm(alarmId)
            
            // Schedule the snooze alarm with the same ID but marked as snooze
            val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra("ALARM_ID", alarmId)
                putExtra("IS_SNOOZE", true)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                alarmId,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val systemAlarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            systemAlarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                snoozeTime,
                pendingIntent
            )
            
            // Show snooze notification
            Toast.makeText(
                applicationContext,
                "Alarm snoozed for 5 minutes",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling snooze alarm", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val z = event.values[2]

            // Calculate orientation based on z-axis
            val currentOrientation = when {
                z < -5 -> 1 // face down
                z > 5 -> 0  // face up
                else -> -1  // ignore in-between
            }

            // Check if orientation has changed between face up and face down and enough time has passed
            val currentTime = System.currentTimeMillis()
            if (currentOrientation != -1 && currentOrientation != lastOrientation &&
                currentTime - orientationChangeTime > ORIENTATION_CHANGE_THRESHOLD) {

                // If this is the first orientation reading, just store it
                if (lastOrientation == -1) {
                    lastOrientation = currentOrientation
                    orientationChangeTime = currentTime
                    return
                }

                // If orientation has changed between face up and face down, stop the alarm
                Log.d(TAG, "Phone orientation changed from $lastOrientation to $currentOrientation (face up/down), stopping alarm")
                stopAlarm()
                // Send SMS when alarm is stopped by phone flip (same as manual tap)
                sendCheckInSMS()
                stopSelf()

                lastOrientation = currentOrientation
                orientationChangeTime = currentTime
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        // Only stop the alarm if it hasn't been stopped already
        if (!isManuallyStopped) {
            stopAlarm()
        }
        
        // Clean up wake lock
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        
        Log.d(TAG, "AlarmService destroyed")
    }

    private fun sendCheckInSMS() {
        serviceScope.launch {
            try {
                // Check SMS permission
                if (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.SEND_SMS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "SMS permission not granted")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "SMS permission required to send check-in messages", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Get username from settings
                val sharedPreferences = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                val username = sharedPreferences.getString("username", "") ?: ""
                
                if (username.isEmpty()) {
                    Log.w(TAG, "No username configured")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "Please set your name in settings to send check-in messages", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Load contacts and get those with success checkbox enabled
                val contactsJson = sharedPreferences.getString("selected_contacts", "[]") ?: "[]"
                val contactsArray = JSONArray(contactsJson)
                val successContacts = mutableListOf<Pair<String, String>>() // name, phone pairs
                
                for (i in 0 until contactsArray.length()) {
                    val contactObj = contactsArray.getJSONObject(i)
                    val name = contactObj.getString("name")
                    val phoneNumber = contactObj.getString("phoneNumber")
                    
                    // Create contact key for checking success checkbox
                    val contactKey = "${name}_${phoneNumber}".replace("[^A-Za-z0-9_]".toRegex(), "_")
                    val isSuccessEnabled = sharedPreferences.getBoolean("${contactKey}_success", false)
                    
                    if (isSuccessEnabled) {
                        successContacts.add(Pair(name, phoneNumber))
                    }
                }

                if (successContacts.isEmpty()) {
                    Log.i(TAG, "No contacts with success checkbox enabled")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "No contacts configured for check-in messages", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Create the check-in message with current time
                val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                val message = "$username has checked-in at $currentTime."
                
                // Send SMS to each contact with success checkbox enabled
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this@AlarmService.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                var successCount = 0
                
                for ((name, phoneNumber) in successContacts) {
                    try {
                        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                        Log.d(TAG, "SMS sent successfully to $name ($phoneNumber)")
                        successCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send SMS to $name ($phoneNumber): ${e.message}")
                    }
                }
                
                // Show result to user
                withContext(Dispatchers.Main) {
                    if (successCount > 0) {
                        Toast.makeText(this@AlarmService, "Check-in message sent to $successCount contact(s)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@AlarmService, "Failed to send check-in messages", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending check-in SMS", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AlarmService, "Error sending check-in messages", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendEmergencySMS() {
        serviceScope.launch {
            try {
                // Check SMS permission
                if (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.SEND_SMS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "SMS permission not granted")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "SMS permission required to send emergency messages", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Get username from settings
                val sharedPreferences = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                val username = sharedPreferences.getString("username", "") ?: ""
                
                if (username.isEmpty()) {
                    Log.w(TAG, "No username configured")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "Please set your name in settings to send emergency messages", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Get current location and address
                val address = getCurrentAddress()
                val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

                // Load contacts and get those with emergency checkbox enabled
                val contactsJson = sharedPreferences.getString("selected_contacts", "[]") ?: "[]"
                val contactsArray = JSONArray(contactsJson)
                val emergencyContacts = mutableListOf<Pair<String, String>>() // name, phone pairs
                
                for (i in 0 until contactsArray.length()) {
                    val contactObj = contactsArray.getJSONObject(i)
                    val name = contactObj.getString("name")
                    val phoneNumber = contactObj.getString("phoneNumber")
                    
                    // Create contact key for checking emergency checkbox
                    val contactKey = "${name}_${phoneNumber}".replace("[^A-Za-z0-9_]".toRegex(), "_")
                    val isEmergencyEnabled = sharedPreferences.getBoolean("${contactKey}_emergency", false)
                    
                    if (isEmergencyEnabled) {
                        emergencyContacts.add(Pair(name, phoneNumber))
                    }
                }

                if (emergencyContacts.isEmpty()) {
                    Log.i(TAG, "No contacts with emergency checkbox enabled")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AlarmService, "No contacts configured for emergency messages", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Create the emergency message
                val message = "$username has activated the EMERGENCY alarm on his device located at $address @ $currentTime"
                
                // Send SMS to each contact with emergency checkbox enabled
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this@AlarmService.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                var successCount = 0
                
                for ((name, phoneNumber) in emergencyContacts) {
                    try {
                        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                        Log.d(TAG, "Emergency SMS sent successfully to $name ($phoneNumber)")
                        successCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send emergency SMS to $name ($phoneNumber): ${e.message}")
                    }
                }
                
                // Show result to user
                withContext(Dispatchers.Main) {
                    if (successCount > 0) {
                        Toast.makeText(this@AlarmService, "Emergency message sent to $successCount contact(s)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@AlarmService, "Failed to send emergency messages", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending emergency SMS", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AlarmService, "Error sending emergency messages", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun sendFailureSMS() {
        try {
            // Check SMS permission
            if (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.SEND_SMS) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "SMS permission not granted")
                return
            }

            // Get username from settings
            val sharedPreferences = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
            val username = sharedPreferences.getString("username", "") ?: ""
            
            if (username.isEmpty()) {
                Log.w(TAG, "No username configured")
                return
            }

            // Get current location and address
            val address = getCurrentAddress()
            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val currentDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())

            // Load contacts and get those with fail checkbox enabled
            val contactsJson = sharedPreferences.getString("selected_contacts", "[]") ?: "[]"
            val contactsArray = JSONArray(contactsJson)
            val failContacts = mutableListOf<Pair<String, String>>() // name, phone pairs
            
            for (i in 0 until contactsArray.length()) {
                val contactObj = contactsArray.getJSONObject(i)
                val name = contactObj.getString("name")
                val phoneNumber = contactObj.getString("phoneNumber")
                
                // Create contact key for checking fail checkbox
                val contactKey = "${name}_${phoneNumber}".replace("[^A-Za-z0-9_]".toRegex(), "_")
                val isFailEnabled = sharedPreferences.getBoolean("${contactKey}_fail", false)
                
                if (isFailEnabled) {
                    failContacts.add(Pair(name, phoneNumber))
                }
            }

            if (failContacts.isEmpty()) {
                Log.i(TAG, "No contacts with fail checkbox enabled")
                return
            }

            // Create the failure message
            val message = "$username has FAILED to check in as scheduled. Location: $address. - $currentDate / $currentTime."
            
            // Send SMS to each contact with fail checkbox enabled
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this@AlarmService.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            var successCount = 0
            
            for ((name, phoneNumber) in failContacts) {
                try {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    Log.d(TAG, "Failure SMS sent successfully to $name ($phoneNumber)")
                    successCount++
                    // Small delay between SMS sends
                    delay(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send failure SMS to $name ($phoneNumber): ${e.message}")
                }
            }
            
            // Show result to user
            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    Toast.makeText(this@AlarmService, "Failure notification sent to $successCount contact(s)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AlarmService, "Failed to send failure notifications", Toast.LENGTH_LONG).show()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending failure SMS", e)
        }
    }

    private suspend fun getCurrentAddress(): String = withContext(Dispatchers.IO) {
        try {
            // Check location permissions
            if (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED && 
                ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_COARSE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted")
                return@withContext "Location unavailable (permission required)"
            }

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Check if GPS provider is enabled
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && 
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Log.w(TAG, "No location providers enabled")
                return@withContext "Location unavailable (GPS disabled)"
            }

            // Try to get fresh location first, then fallback to last known
            var location: Location? = null
            
            try {
                // Request fresh location with timeout
                location = requestFreshLocation(locationManager)
                Log.d(TAG, "Got fresh location: ${location?.latitude}, ${location?.longitude}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get fresh location: ${e.message}")
            }
            
            // Fallback to last known location if fresh request failed
            if (location == null) {
                Log.d(TAG, "Falling back to last known location")
                
                // Try GPS first (more accurate)
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
                
                // Fallback to network provider
                if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }

            if (location == null) {
                Log.w(TAG, "Could not get any location")
                return@withContext "Location unavailable"
            }

            // Check if location is too old (more than 10 minutes)
            val locationAge = System.currentTimeMillis() - location.time
            if (locationAge > 10 * 60 * 1000) {
                Log.w(TAG, "Location is too old: ${locationAge / 1000} seconds")
            }

            // Use Geocoder to get address
            return@withContext getAddressFromLocation(location)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current address", e)
            "Location unavailable"
        }
    }

    private suspend fun requestFreshLocation(locationManager: LocationManager): Location? = withContext(Dispatchers.IO) {
        return@withContext try {
            // Double-check permissions before requesting location updates
            if (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED && 
                ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_COARSE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permissions not available for fresh location request")
                return@withContext null
            }
            var freshLocation: Location? = null
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    freshLocation = location
                    Log.d(TAG, "Fresh location received: ${location.latitude}, ${location.longitude}")
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            
            // Try GPS first for best accuracy
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L, // minimum time interval
                    0f,  // minimum distance
                    locationListener,
                    Looper.getMainLooper()
                )
                
                // Wait up to 15 seconds for GPS
                var attempts = 0
                while (freshLocation == null && attempts < 15) {
                    delay(1000)
                    attempts++
                }
                
                locationManager.removeUpdates(locationListener)
                
                if (freshLocation != null) {
                    return@withContext freshLocation
                }
            }
            
            // Fallback to network provider if GPS failed
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) &&
                (ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                 ContextCompat.checkSelfPermission(this@AlarmService, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
                
                // Wait up to 10 seconds for network location
                var attempts = 0
                while (freshLocation == null && attempts < 10) {
                    delay(1000)
                    attempts++
                }
                
                locationManager.removeUpdates(locationListener)
            }
            
            freshLocation
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting fresh location", e)
            null
        }
    }

    private suspend fun getAddressFromLocation(location: Location): String = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "Geocoder service not available")
                return@withContext "Location: ${location.latitude}, ${location.longitude}"
            }
            
            val geocoder = Geocoder(this@AlarmService, Locale.getDefault())
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // For Android 13+ use the new async API
                try {
                    // Use coroutine-compatible approach for the new async API
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            location.latitude, 
                            location.longitude, 
                            1
                        ) { addresses ->
                            val result = if (addresses.isNotEmpty()) {
                                addresses[0].getAddressLine(0) ?: "Address unavailable"
                            } else {
                                "Location: ${location.latitude}, ${location.longitude}"
                            }
                            continuation.resumeWith(Result.success(result))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Geocoding failed: ${e.message}")
                    "Location: ${location.latitude}, ${location.longitude}"
                }
            } else {
                // For older Android versions use try-catch around deprecated API
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (addresses?.isNotEmpty() == true) {
                        addresses[0].getAddressLine(0) ?: "Address unavailable"
                    } else {
                        "Location: ${location.latitude}, ${location.longitude}"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Geocoding failed: ${e.message}")
                    "Location: ${location.latitude}, ${location.longitude}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting address: ${e.message}")
            "Location: ${location.latitude}, ${location.longitude}"
        }
    }
} 