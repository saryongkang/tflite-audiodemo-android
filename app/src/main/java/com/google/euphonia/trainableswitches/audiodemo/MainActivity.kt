package com.google.euphonia.trainableswitches.audiodemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.euphonia.trainableswitches.audiodemo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val probabilitiesAdapter by lazy { ProbabilitiesAdapter() }

    private lateinit var soundClassifier: SoundClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        soundClassifier = SoundClassifier(this).also {
            it.lifecycleOwner = this
        }

        binding.recyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = probabilitiesAdapter.apply {
                labelList = soundClassifier.labelList
            }
        }

        binding.inputSwitch.setOnCheckedChangeListener { _, isChecked ->
            soundClassifier.isPaused = !isChecked
            if (isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        binding.overlapFactorSlider.addOnChangeListener { _, value, _ ->
            soundClassifier.overlapFactor = value
        }

        soundClassifier.probabilities.observe(this) { resultMap ->
            if (resultMap.isEmpty() || resultMap.size > soundClassifier.labelList.size) {
                Log.w(TAG, "Invalid size of probability output! (size: ${resultMap.size})")
                return@observe
            }
            probabilitiesAdapter.probabilityMap = resultMap
            probabilitiesAdapter.notifyDataSetChanged()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestMicrophonePermission()
        } else {
            soundClassifier.start()
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (isTopResumedActivity) {
            soundClassifier.start()
        } else {
            soundClassifier.stop()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Audio permission granted :)")
                soundClassifier.start()
            } else {
                Log.e(TAG, "Audio permission not granted :(")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            soundClassifier.start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 1337
        private const val TAG = "AudioDemo"
    }
}