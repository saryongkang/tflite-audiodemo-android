package com.google.euphonia.trainableswitches.audiodemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.euphonia.trainableswitches.audiodemo.Utils.AUDIO_DEMO_TAG


class MainActivity : AppCompatActivity() {
    private lateinit var audioDemoView: AudioDemoView

    // TODO: Uncomment this when androidx.activity library become stable
//    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
//            isGranted: Boolean ->
//        if (isGranted) {
//            Log.i(AUDIO_DEMO_TAG, "Audio permission granted :)");
//            audioDemoView.startAudioRecord();
//        } else {
//            Log.e(AUDIO_DEMO_TAG, "Audio permission not granted :(");
//        }
//    }

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

    // TODO: Remove this when androidx.activity library become stable
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(AUDIO_DEMO_TAG, "Audio permission granted :)");
                audioDemoView.startAudioRecord();
            } else {
                Log.e(AUDIO_DEMO_TAG, "Audio permission not granted :(");
            }
        }
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioDemoView.startAudioRecord();
        } else {
            // TODO: Uncomment this when androidx.activity library become stable
//            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 1337
    }
}