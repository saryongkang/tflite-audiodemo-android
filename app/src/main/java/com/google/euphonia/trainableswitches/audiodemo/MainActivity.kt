package com.google.euphonia.trainableswitches.audiodemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.euphonia.trainableswitches.audiodemo.Utils.AUDIO_DEMO_TAG


class MainActivity : AppCompatActivity() {
    private lateinit var audioDemoView: AudioDemoView

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted: Boolean ->
        if (isGranted) {
            Log.i(AUDIO_DEMO_TAG, "Audio permission granted :)");
            audioDemoView.startAudioRecord();
        } else {
            Log.e(AUDIO_DEMO_TAG, "Audio permission not granted :(");
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_main)

        audioDemoView = AudioDemoView(this)
        setContentView(audioDemoView)

        requestMicrophonePermission()
    }

    override fun onDestroy() {
        audioDemoView.cleanup();

        super.onDestroy()
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioDemoView.startAudioRecord();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}