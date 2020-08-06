package com.google.euphonia.trainableswitches.audiodemo;

import static com.google.euphonia.trainableswitches.audiodemo.Utils.AUDIO_DEMO_TAG;
import static com.google.euphonia.trainableswitches.audiodemo.Utils.NANOS_IN_MILLIS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

/**
 * Audio demo view for the Euphonia Audio Model Demo app.
 *
 * <p>View loads and runs the TFLite audio model for inference in real time and displays the
 * recognition results on the screen along with the input audio signal.
 */
class AudioDemoView extends View {

    /** Path of the converted model metadata file, relative to the assets/ directory. */
    private static final String METADATA_PATH = "metadata.json";
    /** JSON key string for word labels in the metadata JSON file. */
    private static final String WORD_LABELS_KEY = "wordLabels";
    /** Path of the converted .tflite file, relative to the assets/ directory. */
    private static final String MODEL_PATH = "combined_model.tflite";
    /** Hard code the required audio rample rate in Hz. */
    private static final int SAMPLE_RATE_HZ = 44100;
    /** How many milliseconds to sleep between successive audio sample pulls. */
    private static final int AUDIO_PULL_PERIOD_MS = 50;
    /** How many milliseconds between consecutive model inference calls. */
    // TODO(cais): Make this configurable.
    private static final int RECOGNITION_PERIOD_MS = 250;
    /** Number of warmup runs to do after loading the TFLite model. */
    private static final int NUM_WARMUP_RUNS = 3;
    /** Probability value above which a class is labeled as active (i.e., detected) the display. */
    private static final float PROB_THRESHOLD = 0.9f;

    private final Context context;
    private final ReentrantLock recordingBufferLock;
    /** The TFLite interpreter instance. */
    private Interpreter interpreter;
    /** Audio length (in # of PCM sapmles) required by the TFLite model. */
    private int modelInputLength;
    /** Number of output classes of the TFLite model. */
    private int modelNumClasses;
    /** Names of the model's output classes. */
    private String[] classNames;
    /** Used to hold the real-time probabilites predicted by the model for the output classes. */
    private float[] predictionProbs;
    /** Latest prediction latency in milliseconds. */
    private float latestPredictionLatencyMs;

    private Thread recordingThread;
    private int recordingOffset;
    private short[] recordingBuffer;
    /** Buffer that holds audio PCM sample that are fed to the TFLite model for inference. */
    private FloatBuffer inputBuffer;

    private Plotter plotter;

    public AudioDemoView(Context context) {
        super(context);
        this.context = context;
        recordingBufferLock = new ReentrantLock();
        setUpAudioModel();
    }

    public void cleanup() {
        if (interpreter != null) {
            // Release resources held by the TFLite interpreter.
            interpreter.close();
            interpreter = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);

        if (plotter == null) {
            return;
        }

        // Draw the prediction probabilities.
        plotter.plotPredictions(canvas, predictionProbs, latestPredictionLatencyMs);
        // TODO(cais): Add history plot of probabilities.
        // Draw the waveform.
        if (inputBuffer != null) {
            final float waveformScalingFactor = 1f / 32768f;
            plotter.plotWaveform(canvas, inputBuffer, modelInputLength, waveformScalingFactor);
        }
    }

    /** Load TFLite model and prepare it for inference */
    private void setUpAudioModel() {
        loadModelMetadata();
        try {
            // Model path relative to the assets folder.
            MappedByteBuffer tfliteBuffer = FileUtil.loadMappedFile(context, MODEL_PATH);
            Log.i(AUDIO_DEMO_TAG, String.format("Done creating TFLite buffer from %s", MODEL_PATH));
            interpreter = new Interpreter(tfliteBuffer, new Interpreter.Options());
        } catch (IOException e) {
            Log.e(
                    AUDIO_DEMO_TAG,
                    String.format("Switches: Failed to call TFLite model(): %s", e.getMessage()));
            return;
        }
        // Inspect input and output specs.
        final int[] inputShape = interpreter.getInputTensor(0).shape();
        Log.i(
                AUDIO_DEMO_TAG, String.format("TFLite model input shape: %s", Arrays.toString(inputShape)));
        final int[] outputShape = interpreter.getOutputTensor(0).shape();
        Log.i(AUDIO_DEMO_TAG, String.format("TFLite output shape: %s", Arrays.toString(outputShape)));

        modelInputLength = inputShape[1];
        modelNumClasses = outputShape[1];
        if (modelNumClasses != classNames.length) {
            Log.e(
                    AUDIO_DEMO_TAG,
                    String.format(
                            "Mismatch between metadata number of classes (%d)" + " and model output length (%d)",
                            classNames.length, modelNumClasses));
        }
        predictionProbs = new float[modelNumClasses];
        // Fill the array with NaNs initially.
        for (int i = 0; i < modelNumClasses; ++i) {
            predictionProbs[i] = Float.NaN;
        }

        // Warm the model up by running inference with some dummy input data.
        inputBuffer = FloatBuffer.allocate(modelInputLength);
        generateDummyAudioInput(inputBuffer);
        for (int n = 0; n < NUM_WARMUP_RUNS; ++n) {
            // Create input and output buffers.
            FloatBuffer outputBuffer = FloatBuffer.allocate(modelNumClasses);
            final long t0 = SystemClock.elapsedRealtimeNanos();
            inputBuffer.rewind();
            outputBuffer.rewind();
            interpreter.run(inputBuffer, outputBuffer);
            Log.i(
                    AUDIO_DEMO_TAG,
                    String.format(
                            "Switches: Done calling interpreter.run(): %s (%.6f ms)",
                            Arrays.toString(outputBuffer.array()),
                            (SystemClock.elapsedRealtimeNanos() - t0) / (float) NANOS_IN_MILLIS));
        }

        // Start real-time audio recording.
        startAudioRecord();
    }

    private void generateDummyAudioInput(FloatBuffer inputBuffer) {
        final float twoPiTimesFreq = 2 * (float) Math.PI * 1000f;
        for (int i = 0; i < modelInputLength; ++i) {
            final float x = (float) i / (modelInputLength - 1);
            inputBuffer.put(i, (float) Math.sin(twoPiTimesFreq * x));
        }
    }

    private void loadModelMetadata() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(context.getAssets().open(METADATA_PATH)));

            StringBuilder jsonStringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonStringBuilder.append(line);
            }
            JsonParser parser = new JsonParser();
            JsonObject metadata = (JsonObject) parser.parse(jsonStringBuilder.toString());
            JsonArray wordLabels = metadata.get(WORD_LABELS_KEY).getAsJsonArray();
            classNames = new String[wordLabels.size()];
            for (int i = 0; i < wordLabels.size(); ++i) {
                classNames[i] = wordLabels.get(i).getAsString();
            }
            plotter = new Plotter(context, classNames, PROB_THRESHOLD);
        } catch (IOException e) {
            Log.e(
                    AUDIO_DEMO_TAG, String.format("Failed to read model metadata.json: %s", e.getMessage()));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(
                            AUDIO_DEMO_TAG,
                            String.format("Failed to close reader for asset file %s", METADATA_PATH));
                }
            }
        }
    }

    class AudioRecordingRunnable implements Runnable {
        @Override
        public void run() {
            int bufferSize =
                    AudioRecord.getMinBufferSize(
                            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                bufferSize = SAMPLE_RATE_HZ * 2;
                Log.e(AUDIO_DEMO_TAG, "bufferSize has error or bad value");
            }
            Log.i(AUDIO_DEMO_TAG, String.format("bufferSize = %d", bufferSize));

            AudioRecord record =
                    new AudioRecord(
                            // TODO(cais): Add UI affordance for choosing other AudioSource values,
                            // including MIC, UNPROCESSED, and CAMCORDER.
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(AUDIO_DEMO_TAG, "AudioRecord failed to initialize");
                return;
            }
            Log.i(AUDIO_DEMO_TAG, "Successfully intialized AudioRecord");

            final int bufferSamples = bufferSize / 2;
            short[] audioBuffer = new short[bufferSamples];
            final int recordingBufferSamples =
                    (int) Math.ceil((float) modelInputLength / bufferSamples) * bufferSamples;
            Log.i(AUDIO_DEMO_TAG, String.format("recordingBufferSamples = %d", recordingBufferSamples));

            recordingOffset = 0;
            recordingBuffer = new short[recordingBufferSamples];

            record.startRecording();
            Log.i(AUDIO_DEMO_TAG, "Successfully started AudioRecord recording");
            // Start recognition (model inference) thread.
            startRecognition();

            while (true) {
                try {
                    MILLISECONDS.sleep(AUDIO_PULL_PERIOD_MS);
                } catch (InterruptedException e) {
                    Log.e(AUDIO_DEMO_TAG, "Sleep interrupted in audio recording thread.");
                }
                int numRead = record.read(audioBuffer, 0, audioBuffer.length);
                if (numRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.w(AUDIO_DEMO_TAG, "AudioRecord.ERROR_INVALID_OPERATION");
                } else if (numRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(AUDIO_DEMO_TAG, "AudioRecord.ERROR_BAD_VALUE");
                } else if (numRead == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.w(AUDIO_DEMO_TAG, "AudioRecord.ERROR_DEAD_OBJECT");
                } else if (numRead == AudioRecord.ERROR) {
                    Log.w(AUDIO_DEMO_TAG, "AudioRecord.ERROR");
                } else if (numRead == bufferSamples) {
                    // We apply locks here to avoid two separate threads (the recording and
                    // recognition threads) reading and writing from the recordingBuffer at the same
                    // time, which can cause the recognition thread to read garbled audio snippets.
                    recordingBufferLock.lock();
                    try {
                        System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, bufferSamples);
                        recordingOffset = (recordingOffset + bufferSamples) % recordingBufferSamples;
                    } finally {
                        recordingBufferLock.unlock();
                    }
                }
            }
        }
    }

    /** Start a thread to pull audio samples in continuously. */
    public synchronized void startAudioRecord() {
        if (recordingThread != null && recordingThread.isAlive()) {
            return;
        }
        recordingThread = new Thread(new AudioRecordingRunnable());
        recordingThread.start();
    }

    class RecognitionRunnable implements Runnable {
        @Override
        public void run() {
            if (modelInputLength <= 0 || modelNumClasses <= 0) {
                Log.e(AUDIO_DEMO_TAG, "Switches: Canont start recognition because model is unavailable.");
                return;
            }

            FloatBuffer outputBuffer = FloatBuffer.allocate(modelNumClasses);
            while (true) {
                try {
                    MILLISECONDS.sleep(RECOGNITION_PERIOD_MS);
                } catch (InterruptedException e) {
                    Log.e(AUDIO_DEMO_TAG, "Sleep interrupted in recognition thread.");
                }
                boolean samplesAreAllZero = true;
                recordingBufferLock.lock();
                try {
                    if (recordingBuffer == null) {
                        continue;
                    }
                    int j = recordingOffset - modelInputLength;
                    j = j % modelInputLength;
                    if (j < 0) {
                        j += modelInputLength;
                    }
                    for (int i = 0; i < modelInputLength; ++i) {
                        final short s = recordingBuffer[j++ % modelInputLength];
                        if (samplesAreAllZero && (s != 0)) {
                            samplesAreAllZero = false;
                        }
                        // TODO(cais): Explore better way of reading float samples directly from the
                        // AudioSource and using bulk put() instead of sample-by-sample put() here.
                        inputBuffer.put(i, (float) s);
                    }
                } finally {
                    recordingBufferLock.unlock();
                }
                if (samplesAreAllZero) {
                    Log.w(AUDIO_DEMO_TAG, "No audio input: All audio samples are zero!");
                    continue;
                }

                final long t0 = SystemClock.elapsedRealtimeNanos();
                inputBuffer.rewind();
                outputBuffer.rewind();
                interpreter.run(inputBuffer, outputBuffer);
                outputBuffer.rewind();
                outputBuffer.get(predictionProbs); // Copy data to predictionProbs.
                latestPredictionLatencyMs = (float) ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6);
                invalidate();
            }
        }
    }

    /** Start a thread that runs model inference (i.e., recognition) at a regular interval. */
    private void startRecognition() {
        Thread recognitionThread = new Thread(new RecognitionRunnable());
        recognitionThread.start();
    }
}
