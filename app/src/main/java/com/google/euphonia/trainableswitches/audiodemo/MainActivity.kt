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
import java.util.*

class MainActivity : AppCompatActivity() {
    private val probabilitiesAdapter by lazy { ProbabilitiesAdapter() }

    private lateinit var soundClassifier: SoundClassifier

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

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        soundClassifier = SoundClassifier(this).also {
            it.lifecycleOwner = this
        }

        soundClassifier.probabilities.observe(this) { probs ->
            if (probs.isEmpty() || probs.size > 3) {
                Log.w(Utils.AUDIO_DEMO_TAG, "Invalid probability output!")
                return@observe
            }
            probabilitiesAdapter.probabilityList = probs
            probabilitiesAdapter.notifyDataSetChanged()
        }

        binding.recyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = probabilitiesAdapter
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestMicrophonePermission()
        } else {
            soundClassifier.start()
        }
    }

    // TODO: Remove this when androidx.activity library become stable
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(AUDIO_DEMO_TAG, "Audio permission granted :)")
                //audioDemoView.startAudioRecord()
                soundClassifier.start()

            } else {
                Log.e(AUDIO_DEMO_TAG, "Audio permission not granted :(")
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
            //audioDemoView.startAudioRecord()
            soundClassifier.start()
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