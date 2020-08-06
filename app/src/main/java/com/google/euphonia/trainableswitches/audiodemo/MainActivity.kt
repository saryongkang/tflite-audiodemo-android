package com.google.euphonia.trainableswitches.audiodemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.euphonia.trainableswitches.audiodemo.Utils.AUDIO_DEMO_TAG


class MainActivity : AppCompatActivity() {
    private lateinit var audioDemoView: AudioDemoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_main)

        audioDemoView = AudioDemoView(this)
        setContentView(audioDemoView)

        requestMicrophonePermission()
    }

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

    override fun onDestroy() {
        audioDemoView.cleanup();

        super.onDestroy()
    }

    private fun requestMicrophonePermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 1337
    }
}