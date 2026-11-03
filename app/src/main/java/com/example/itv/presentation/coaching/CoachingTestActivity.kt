package com.example.itv.presentation.coaching
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import org.json.JSONObject
import java.io.File

// Source compatibility only; no implementation remains in presentation.

class CoachingTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java))
        finish()
    }
}
