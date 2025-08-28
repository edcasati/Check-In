package com.example.accessiblealarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.accessiblealarm.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 124
        private const val SMS_PERMISSION_REQUEST_CODE = 125
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        
        private val NOTIFICATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
        
        private val BACKGROUND_LOCATION_PERMISSION = arrayOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupNavigation()
            checkAndRequestPermissions()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            // Try to show a simple error message and gracefully exit
            Toast.makeText(this, "App initialization failed. Please try again.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupNavigation() {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            navController = navHostFragment.navController
            
            // Set up the bottom navigation
            val navView: BottomNavigationView = binding.bottomNavigation
            navView.setupWithNavController(navController)

            // Set up the ActionBar
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.navigation_home,
                    R.id.navigation_alarms,
                    R.id.navigation_settings
                )
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error setting up navigation", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check basic required permissions
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }
        
        // Check notification permissions for Android 13+
        for (permission in NOTIFICATION_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Check if we should show explanation
            val shouldShowRationale = permissionsToRequest.any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }

            if (shouldShowRationale) {
                showPermissionExplanationDialog(permissionsToRequest.toTypedArray())
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        
        // Check for background location permission on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocationPermission()
            }
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                AlertDialog.Builder(this)
                    .setTitle("Background Location Permission")
                    .setMessage("This app needs background location access to provide accurate location information when alarms trigger, even when the app is not actively being used or when the device is asleep.")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            BACKGROUND_LOCATION_PERMISSION,
                            BACKGROUND_LOCATION_REQUEST_CODE
                        )
                    }
                    .setNegativeButton("Skip") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(this, "Background location permission denied. GPS may not work when app is in background.", Toast.LENGTH_LONG).show()
                    }
                    .show()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    BACKGROUND_LOCATION_PERMISSION,
                    BACKGROUND_LOCATION_REQUEST_CODE
                )
            }
        }
    }

    private fun showPermissionExplanationDialog(permissions: Array<String>) {
        val permissionTypes = mutableListOf<String>()
        
        if (permissions.contains(Manifest.permission.SEND_SMS)) {
            permissionTypes.add("• SMS - to send check-in messages to your contacts")
        }
        if (permissions.any { it.contains("LOCATION") }) {
            permissionTypes.add("• Location - to include your location in check-in messages")
        }
        if (permissions.contains(Manifest.permission.READ_CONTACTS)) {
            permissionTypes.add("• Contacts - to select who receives your check-in messages")
        }
        if (permissions.contains(Manifest.permission.READ_PHONE_STATE)) {
            permissionTypes.add("• Phone - to ensure the app works properly")
        }
        
        val message = "This Check-In app needs the following permissions to function:\n\n" +
                permissionTypes.joinToString("\n") + 
                "\n\nThese permissions are essential for the app's safety features."
        
        AlertDialog.Builder(this)
            .setTitle("Permissions Required for Check-In App")
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    permissions,
                    PERMISSION_REQUEST_CODE
                )
            }
            .setNegativeButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val grantedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] == PackageManager.PERMISSION_GRANTED
                }
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] == PackageManager.PERMISSION_DENIED
                }
                
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(this, "All permissions granted! Check-In app is ready to use.", Toast.LENGTH_LONG).show()
                    // Now request background location permission for Android 10+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestBackgroundLocationPermission()
                    }
                } else {
                    // Handle SMS permission specifically
                    val smsGranted = grantedPermissions.contains(Manifest.permission.SEND_SMS)
                    val smsDenied = deniedPermissions.contains(Manifest.permission.SEND_SMS)
                    
                    if (smsDenied) {
                        showSMSPermissionDialog()
                    } else if (smsGranted) {
                        Toast.makeText(this, "SMS permission granted! You can now send check-in messages.", Toast.LENGTH_LONG).show()
                    }
                    
                    // Check if any permission was permanently denied
                    val permanentlyDenied = permissions.filterIndexed { index, permission ->
                        grantResults[index] == PackageManager.PERMISSION_DENIED &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                    }

                    if (permanentlyDenied.isNotEmpty()) {
                        if (permanentlyDenied.contains(Manifest.permission.SEND_SMS)) {
                            showSMSSettingsDialog()
                        } else {
                            showSettingsDialog()
                        }
                    } else if (deniedPermissions.isNotEmpty() && !smsDenied) {
                        showPermissionExplanationDialog(deniedPermissions.toTypedArray())
                    }
                }
            }
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Background location permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Background location permission denied. GPS may not work when app is in background.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSMSPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Permission Required")
            .setMessage("The Check-In app needs SMS permission to send safety messages to your contacts. This is the core feature of the app.\n\nWithout this permission, the app cannot function properly.")
            .setPositiveButton("Grant SMS Permission") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.SEND_SMS),
                    PERMISSION_REQUEST_CODE
                )
            }
            .setNegativeButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showSMSSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Permission Required")
            .setMessage("SMS permission is essential for this Check-In app to work. Please follow these steps:\n\n" +
                    "1. Tap 'Open Settings' below\n" +
                    "2. Find 'Permissions' \n" +
                    "3. Tap 'SMS' or 'Phone'\n" +
                    "4. Enable the permission\n" +
                    "5. Return to the app\n\n" +
                    "Without SMS permission, you cannot send check-in messages.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("I'll do it later") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Remember: SMS permission is required for check-in messages", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Some permissions were permanently denied. Please enable them in Settings to use all features of the app.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkSMSPermissionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // Show a toast reminder about SMS permission
            Toast.makeText(this, "SMS permission needed for check-in messages. Check app permissions in Settings.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Recheck permissions when app resumes
        checkAndRequestPermissions()
        // Also check SMS permission specifically
        checkSMSPermissionStatus()
    }
} 