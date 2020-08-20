package com.google.euphonia.trainableswitches.audiodemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Audio demo view for the Euphonia Audio Model Demo app.
 *
 * View loads and runs the TFLite audio model for inference in real time and displays the
 * recognition results on the screen along with the input audio signal.
 */
internal class AudioDemoView(context: Context) : View(context) {
    private val recordingBufferLock: ReentrantLock = ReentrantLock()

    /** The TFLite interpreter instance.  */
    private var interpreter: Interpreter? = null

    /** Audio length (in # of PCM samples) required by the TFLite model.  */
    private var modelInputLength = 0

    /** Number of output classes of the TFLite model.  */
    private var modelNumClasses = 0

    /** Names of the model's output classes.  */
    private lateinit var classNames: Array<String>

    /** Used to hold the real-time probabilites predicted by the model for the output classes.  */
    private lateinit var predictionProbs: FloatArray

    /** Latest prediction latency in milliseconds.  */
    private var latestPredictionLatencyMs = 0f
    private var recordingThread: Thread? = null
    private var recordingOffset = 0
    private var recordingBuffer: ShortArray? = null

    /** Buffer that holds audio PCM sample that are fed to the TFLite model for inference.  */
    private var inputBuffer: FloatBuffer? = null
    private var plotter: Plotter? = null

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (plotter == null) {
            return
        }

        // Draw the prediction probabilities.
        plotter!!.plotPredictions(canvas, predictionProbs, latestPredictionLatencyMs)
        // TODO(cais): Add history plot of probabilities.
        // Draw the waveform.
        if (inputBuffer != null) {
            val waveformScalingFactor = 1f / 32768f
            plotter!!.plotWaveform(canvas, inputBuffer!!, modelInputLength, waveformScalingFactor)
        }
    }

    fun cleanup() {
        if (interpreter != null) {
            // Release resources held by the TFLite interpreter.
            interpreter!!.close()
            interpreter = null
        }
    }

    /** Load TFLite model and prepare it for inference  */
    private fun setUpAudioModel() {
        loadModelMetadata()
        interpreter = try {
            // Model path relative to the assets folder.
            val tfliteBuffer =
                FileUtil.loadMappedFile(context, MODEL_PATH)
            Log.i(
                Utils.AUDIO_DEMO_TAG,
                "Done creating TFLite buffer from $MODEL_PATH"
            )
            Interpreter(
                tfliteBuffer,
                Interpreter.Options()
            )
        } catch (e: IOException) {
            Log.e(
                Utils.AUDIO_DEMO_TAG,
                "Switches: Failed to call TFLite model(): ${e.message}"
            )
            return
        }
        // Inspect input and output specs.
        val inputShape = interpreter!!.getInputTensor(0).shape()
        Log.i(
            Utils.AUDIO_DEMO_TAG,
            "TFLite model input shape: ${Arrays.toString(inputShape)}"
        )
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        Log.i(
            Utils.AUDIO_DEMO_TAG,
            "TFLite output shape: ${Arrays.toString(outputShape)}"
        )
        modelInputLength = inputShape[1]
        modelNumClasses = outputShape[1]
        if (modelNumClasses != classNames.size) {
            Log.e(
                Utils.AUDIO_DEMO_TAG,
                "Mismatch between metadata number of classes (${classNames.size})" + " and model output length (${modelNumClasses})"
            )
        }
        // Fill the array with NaNs initially.
        predictionProbs = FloatArray(modelNumClasses) { Float.NaN }

        // Warm the model up by running inference with some dummy input data.
        inputBuffer = FloatBuffer.allocate(modelInputLength)
        generateDummyAudioInput(inputBuffer)
        for (n in 0 until NUM_WARMUP_RUNS) {
            // Create input and output buffers.
            val outputBuffer = FloatBuffer.allocate(modelNumClasses)
            val t0 = SystemClock.elapsedRealtimeNanos()
            inputBuffer!!.rewind()
            outputBuffer.rewind()
            interpreter!!.run(inputBuffer, outputBuffer)
            Log.i(
                Utils.AUDIO_DEMO_TAG, String.format(
                    "Switches: Done calling interpreter.run(): %s (%.6f ms)",
                    Arrays.toString(outputBuffer.array()),
                    (SystemClock.elapsedRealtimeNanos() - t0) / Utils.NANOS_IN_MILLIS.toFloat()
                )
            )
        }

        // Start real-time audio recording.
        startAudioRecord()
    }

    private fun generateDummyAudioInput(inputBuffer: FloatBuffer?) {
        val twoPiTimesFreq = 2 * Math.PI.toFloat() * 1000f
        for (i in 0 until modelInputLength) {
            val x = i.toFloat() / (modelInputLength - 1)
            inputBuffer!!.put(i, sin(twoPiTimesFreq * x.toDouble()).toFloat())
        }
    }

    private fun loadModelMetadata() {
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(
                InputStreamReader(
                    context.assets.open(METADATA_PATH)
                )
            )
            val jsonStringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                jsonStringBuilder.append(line)
            }
            val metadata = JsonParser.parseString(jsonStringBuilder.toString()) as JsonObject
            val wordLabels =
                metadata[WORD_LABELS_KEY].asJsonArray
            classNames = wordLabels.map { it.asString }.toTypedArray()
            plotter = Plotter(context, classNames, PROB_THRESHOLD)
        } catch (e: IOException) {
            Log.e(
                Utils.AUDIO_DEMO_TAG,
                "Failed to read model metadata.json: ${e.message}"
            )
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    Log.e(
                        Utils.AUDIO_DEMO_TAG,
                        String.format(
                            "Failed to close reader for asset file %s",
                            METADATA_PATH
                        )
                    )
                }
            }
        }
    }

    internal inner class AudioRecordingRunnable : Runnable {
        override fun run() {
            var bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                bufferSize = SAMPLE_RATE_HZ * 2
                Log.e(Utils.AUDIO_DEMO_TAG, "bufferSize has error or bad value")
            }
            Log.i(Utils.AUDIO_DEMO_TAG, "bufferSize = $bufferSize")
            val record =
                AudioRecord( // TODO(cais): Add UI affordance for choosing other AudioSource values,
                    // including MIC, UNPROCESSED, and CAMCORDER.
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(Utils.AUDIO_DEMO_TAG, "AudioRecord failed to initialize")
                return
            }
            Log.i(Utils.AUDIO_DEMO_TAG, "Successfully initialized AudioRecord")
            val bufferSamples = bufferSize / 2
            val audioBuffer = ShortArray(bufferSamples)
            val recordingBufferSamples =
                ceil(modelInputLength.toFloat() / bufferSamples.toDouble())
                    .toInt() * bufferSamples
            Log.i(Utils.AUDIO_DEMO_TAG, "recordingBufferSamples = $recordingBufferSamples")
            recordingOffset = 0
            recordingBuffer = ShortArray(recordingBufferSamples)
            record.startRecording()
            Log.i(Utils.AUDIO_DEMO_TAG, "Successfully started AudioRecord recording")

            // Start recognition (model inference) thread.
            startRecognition()
            while (true) {
                try {
                    TimeUnit.MILLISECONDS.sleep(AUDIO_PULL_PERIOD_MS)
                } catch (e: InterruptedException) {
                    Log.e(
                        Utils.AUDIO_DEMO_TAG,
                        "Sleep interrupted in audio recording thread."
                    )
                }
                when (record.read(audioBuffer, 0, audioBuffer.size)) {
                    AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.w(Utils.AUDIO_DEMO_TAG, "AudioRecord.ERROR_INVALID_OPERATION")
                    }
                    AudioRecord.ERROR_BAD_VALUE -> {
                        Log.w(Utils.AUDIO_DEMO_TAG, "AudioRecord.ERROR_BAD_VALUE")
                    }
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(Utils.AUDIO_DEMO_TAG, "AudioRecord.ERROR_DEAD_OBJECT")
                    }
                    AudioRecord.ERROR -> {
                        Log.w(Utils.AUDIO_DEMO_TAG, "AudioRecord.ERROR")
                    }
                    bufferSamples -> {
                        // We apply locks here to avoid two separate threads (the recording and
                        // recognition threads) reading and writing from the recordingBuffer at the same
                        // time, which can cause the recognition thread to read garbled audio snippets.
                        recordingBufferLock.lock()
                        recordingOffset = try {
                            audioBuffer.copyInto(recordingBuffer!!, recordingOffset, 0, bufferSamples)
                            (recordingOffset + bufferSamples) % recordingBufferSamples
                        } finally {
                            recordingBufferLock.unlock()
                        }
                    }
                }
            }
        }
    }

    /** Start a thread to pull audio samples in continuously.  */
    @Synchronized
    fun startAudioRecord() {
        if (recordingThread != null && recordingThread!!.isAlive) {
            return
        }
        recordingThread = Thread(AudioRecordingRunnable())
        recordingThread!!.start()
    }

    internal inner class RecognitionRunnable : Runnable {
        override fun run() {
            if (modelInputLength <= 0 || modelNumClasses <= 0) {
                Log.e(
                    Utils.AUDIO_DEMO_TAG,
                    "Switches: Canont start recognition because model is unavailable."
                )
                return
            }
            val outputBuffer = FloatBuffer.allocate(modelNumClasses)
            while (true) {
                try {
                    TimeUnit.MILLISECONDS.sleep(RECOGNITION_PERIOD_MS)
                } catch (e: InterruptedException) {
                    Log.e(Utils.AUDIO_DEMO_TAG, "Sleep interrupted in recognition thread.")
                }
                var samplesAreAllZero = true
                recordingBufferLock.lock()
                try {
                    if (recordingBuffer == null) {
                        continue
                    }
                    var j = (recordingOffset - modelInputLength) % modelInputLength
                    if (j < 0) {
                        j += modelInputLength
                    }
                    for (i in 0 until modelInputLength) {
                        val s = if (i >= POINTS_IN_AVG && j >= POINTS_IN_AVG) {
                            ((j - POINTS_IN_AVG + 1)..j).map { recordingBuffer!![it % modelInputLength] }.average()
                        } else {
                            recordingBuffer!![j % modelInputLength]
                        }
                        j += 1

                        if (samplesAreAllZero && s.toInt() != 0) {
                            samplesAreAllZero = false
                        }
                        // TODO(cais): Explore better way of reading float samples directly from the
                        // AudioSource and using bulk put() instead of sample-by-sample put() here.
                        inputBuffer!!.put(i, s.toFloat())
                    }
                } finally {
                    recordingBufferLock.unlock()
                }
                if (samplesAreAllZero) {
                    Log.w(
                        Utils.AUDIO_DEMO_TAG,
                        "No audio input: All audio samples are zero!"
                    )
                    continue
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                inputBuffer!!.rewind()
                outputBuffer.rewind()
                interpreter!!.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()
                outputBuffer.get(predictionProbs) // Copy data to predictionProbs.
                latestPredictionLatencyMs =
                    ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6).toFloat()
                invalidate()
            }
        }
    }

    /** Start a thread that runs model inference (i.e., recognition) at a regular interval.  */
    private fun startRecognition() {
        val recognitionThread = Thread(RecognitionRunnable())
        recognitionThread.start()
    }

    companion object {
        /** Path of the converted model metadata file, relative to the assets/ directory.  */
        private const val METADATA_PATH = "metadata.json"

        /** JSON key string for word labels in the metadata JSON file.  */
        private const val WORD_LABELS_KEY = "wordLabels"

        /** Path of the converted .tflite file, relative to the assets/ directory.  */
        private const val MODEL_PATH = "combined_model.tflite"

        /** Hard code the required audio rample rate in Hz.  */
        private const val SAMPLE_RATE_HZ = 44100

        /** How many milliseconds to sleep between successive audio sample pulls.  */
        private const val AUDIO_PULL_PERIOD_MS = 50L

        private const val OVERLAP_FACTOR = 0.8

        /** How many milliseconds between consecutive model inference calls.  */ // TODO(cais): Make this configurable.
        private const val RECOGNITION_PERIOD_MS = (1000L * (1 - OVERLAP_FACTOR)).toLong()

        /** Number of warmup runs to do after loading the TFLite model.  */
        private const val NUM_WARMUP_RUNS = 3

        /** Probability value above which a class is labeled as active (i.e., detected) the display.  */
        private const val PROB_THRESHOLD = 0.9f

        private const val POINTS_IN_AVG = 10
    }

    init {
        setUpAudioModel()
    }
}