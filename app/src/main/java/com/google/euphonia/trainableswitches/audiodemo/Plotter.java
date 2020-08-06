package com.google.euphonia.trainableswitches.audiodemo;

import static com.google.euphonia.trainableswitches.audiodemo.Utils.BACKGROUND_NOISE_CLASS_NAME;
import static com.google.euphonia.trainableswitches.audiodemo.Utils.NANOS_IN_SEC;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import java.nio.FloatBuffer;

/** Helper functions for making plots. */
public final class Plotter {
    /** Period of the rolling display of probability, in seconds. */
    private static final float TEXT_LINE_HEIGHT = 50f;
    /** Text size in the history probability plot. */
    private static final float HISTORY_PLOT_TEXT_SIZE = 40f;
    /** x coordinate for basic texts on the screen. */
    private static final float TEXT_X = 5f;
    /** Fraction of the screen height for the top of the waveform plot. */
    private static final float WAVEFORM_WINDOW_TOP_FRAC = 0.2f;
    /** Fraction of the screen height for the bottom of the waveform plot. */
    private static final float WAVEFORM_WINDOW_BOTTOM_FRAC = 0.6f;
    /** Fraction of the screen height for the top of the history probability plot. */
    private static final float HISTORY_PROB_TOP_FRAC = 0.6f;
    /** Fraction of the screen height for the bottom of the history probability plot. */
    private static final float HISTORY_PROB_BOTTOM_FRAC = 0.9f;
    /** Fraction of the screen height for showing string summarizing the # of positive events. */
    private static final float SUMMARY_TEXT_FRAC = 0.95f;
    /** Colors used to represent the output classes of the model. */
    private static final int[] classColors = {
            Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED,
    };

    private final Context context;
    private final String[] classNames;
    private final float probThreshold;
    private final long startTimeNanos;
    private final int screenWidth;
    private final int screenHeight;
    /**
     * History traces of the output class probabilites. The first dimension is for time steps; the
     * second is for output classes.
     */
    private final float[][] historyProbs;
    /** Index to the elements of historyProbs. Keeps track of where we are in the rolling display. */
    private int historyProbsIndex;
    private int numPositiveEvents;
    private Paint basicTextPaint;
    private Paint supraThresholdTextPaint;
    private Paint waveformPaint;
    private Paint axesPaint;
    private Paint axesTextPaint;
    private Paint[] classTracePaints;
    private Paint[] classTextPaints;

    /**
     * Constructor of Plotter.
     *
     * @param context Android app context.
     * @param classNames Output class names of the model.
     * @param probThreshold Threshold probability value, above which a non-background-noise class is
     *     considered active (i.e., a suprthreshold or positive event).
     */
    public Plotter(Context context, String[] classNames, float probThreshold) {
        this.context = context;
        this.classNames = classNames;
        this.probThreshold = probThreshold;
        final DisplayMetrics displayMetrics = getScreenDimensions();
        startTimeNanos = SystemClock.elapsedRealtimeNanos();
        screenHeight = displayMetrics.heightPixels;
        screenWidth = displayMetrics.widthPixels;
        historyProbs = new float[screenWidth][classNames.length];
        historyProbsIndex = 0;
        numPositiveEvents = 0;
        createPaints();
    }

    /**
     * Plot a FloatBuffer as a curve on in a canvas.
     *
     * @param canvas The canvas to plut the curve in.
     * @param xs The FloatBuffer to plot.
     * @param length Number of elements in the FloatBuffer.
     * @param scalingFactor Scaling factor for the elements of `xs`.
     */
    public void plotWaveform(Canvas canvas, FloatBuffer xs, int length, float scalingFactor) {
        final float halfHeight =
                (WAVEFORM_WINDOW_BOTTOM_FRAC - WAVEFORM_WINDOW_TOP_FRAC) * screenHeight / 2;
        final float zeroY = (WAVEFORM_WINDOW_TOP_FRAC + WAVEFORM_WINDOW_BOTTOM_FRAC) / 2 * screenHeight;
        final float stepX = ((float) screenWidth) / length;

        final Path path = new Path();
        float currentX = 0f;
        path.moveTo(currentX, zeroY + xs.get(0) * scalingFactor * halfHeight);
        for (int i = 1; i < length; ++i) {
            currentX += stepX;
            path.lineTo(currentX, zeroY + xs.get(i) * scalingFactor * halfHeight);
        }
        canvas.drawPath(path, waveformPaint);
    }

    /**
     * Plot latest prediction results. Including inference latency and probabilities outputted by the
     * model.
     *
     * @param canvas The canvas to plot the results.
     * @param predictionProbs Probabilities predicted by the model for the output classes.
     * @param predictionLatencyMs Prediction (inference) latency in milliseconds.
     */
    public void plotPredictions(Canvas canvas, float[] predictionProbs, float predictionLatencyMs) {
        float currentY = TEXT_LINE_HEIGHT;
        canvas.drawText(
                String.format("Inference latency: %.3f ms", predictionLatencyMs),
                TEXT_X,
                currentY,
                basicTextPaint);
        currentY += TEXT_LINE_HEIGHT;

        if (predictionProbs.length == 0) {
            return;
        }
        if (Float.isNaN(predictionProbs[0])) {
            // This can happen when the app has just started up and the samples from the microphone are
            // still all zero.
            canvas.drawText("No valid prediction...", TEXT_X, currentY, basicTextPaint);
            return;
        }
        for (int i = 0; i < predictionProbs.length; ++i) {
            final float prob = predictionProbs[i];
            final boolean isPositive =
                    prob > probThreshold && !classNames[i].equals(BACKGROUND_NOISE_CLASS_NAME);
            if (isPositive) {
                numPositiveEvents++;
            }
            Paint paint = isPositive ? supraThresholdTextPaint : basicTextPaint;
            final String classText = String.format("%s: %.6f", classNames[i], prob);
            canvas.drawText(classText, TEXT_X, currentY, paint);
            currentY += TEXT_LINE_HEIGHT;
            historyProbs[historyProbsIndex][i] = prob;
        }
        historyProbsIndex = (historyProbsIndex + 1) % historyProbs.length;
        plotHistoryProbs(canvas);
    }

    /**
     * Plot probability history.
     *
     * <p>Displays history probabilities for all non-background-noise classes on the screen, in a
     * rolling fashion.
     *
     * @param canvas The canvas to plot the traces on.
     */
    private void plotHistoryProbs(Canvas canvas) {
        final float height = (HISTORY_PROB_BOTTOM_FRAC - HISTORY_PROB_TOP_FRAC) * screenHeight;
        final float maxY = HISTORY_PROB_BOTTOM_FRAC * screenHeight;
        final int numSteps = historyProbs.length;
        final int numClasses = historyProbs[0].length;
        final float stepX = ((float) screenWidth) / numSteps;

        drawHistoryProbsAxes(canvas);
        for (int k = 0; k < numClasses; ++k) {
            if (classNames[k].equals(BACKGROUND_NOISE_CLASS_NAME)) {
                continue;
            }
            final Path path = new Path();
            final Paint paint = classTracePaints[k % classTracePaints.length];
            float currentX = 0f;
            float p = Float.isNaN(historyProbs[0][k]) ? 0f : historyProbs[0][k];
            path.moveTo(currentX, maxY - p);
            for (int i = 1; i < historyProbsIndex; ++i) {
                currentX += stepX;
                p = Float.isNaN(historyProbs[i][k]) ? 0f : historyProbs[i][k];
                path.lineTo(currentX, maxY - height * p);
            }
            canvas.drawPath(path, paint);
            canvas.drawText(
                    classNames[k], currentX, maxY - height * p, classTextPaints[k % classTextPaints.length]);
        }

        // Draw text to indicate # of positive events and total elapsed time since app start.
        canvas.drawText(
                String.format(
                        "#(positive)=%d, duration=%ds",
                        numPositiveEvents,
                        (SystemClock.elapsedRealtimeNanos() - startTimeNanos) / NANOS_IN_SEC),
                TEXT_X,
                SUMMARY_TEXT_FRAC * screenHeight,
                basicTextPaint);
    }

    /** Draw axes for the history probability plot. */
    private void drawHistoryProbsAxes(Canvas canvas) {
        final float[] yTicks = {0f, 0.25f, 0.5f, 0.75f, 1.0f};
        final float height = (HISTORY_PROB_BOTTOM_FRAC - HISTORY_PROB_TOP_FRAC) * screenHeight;
        final float maxY = HISTORY_PROB_BOTTOM_FRAC * screenHeight;
        for (float yTick : yTicks) {
            final Path path = new Path();
            final float y = maxY - height * yTick;
            path.moveTo(0, y);
            path.lineTo(screenWidth, y);
            canvas.drawPath(path, axesPaint);
            canvas.drawText(String.format(yTick == 0f ? "p=%.2f" : "%.2f", yTick), 0, y, axesTextPaint);
        }
    }

    private DisplayMetrics getScreenDimensions() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics;
    }

    /** Create Paint objects for later use. */
    private void createPaints() {
        basicTextPaint = new Paint();
        basicTextPaint.setStyle(Paint.Style.FILL);
        basicTextPaint.setColor(Color.GRAY);
        basicTextPaint.setStrokeWidth(1);
        basicTextPaint.setTextSize(TEXT_LINE_HEIGHT);

        supraThresholdTextPaint = new Paint();
        supraThresholdTextPaint.setStyle(Paint.Style.FILL);
        supraThresholdTextPaint.setColor(Color.GREEN);
        supraThresholdTextPaint.setStrokeWidth(1);
        supraThresholdTextPaint.setTextSize(TEXT_LINE_HEIGHT);

        waveformPaint = new Paint();
        waveformPaint.setStyle(Paint.Style.STROKE);
        waveformPaint.setColor(Color.GRAY);
        waveformPaint.setStrokeWidth(1);

        axesPaint = new Paint();
        axesPaint.setStyle(Paint.Style.STROKE);
        axesPaint.setColor(Color.DKGRAY);
        axesPaint.setStrokeWidth(1);

        axesTextPaint = new Paint();
        axesTextPaint.setStyle(Paint.Style.FILL);
        axesTextPaint.setColor(Color.DKGRAY);
        axesTextPaint.setTextSize(HISTORY_PLOT_TEXT_SIZE);

        classTracePaints = new Paint[classColors.length];
        classTextPaints = new Paint[classColors.length];
        for (int i = 0; i < classColors.length; ++i) {
            final Paint tracePaint = new Paint();
            tracePaint.setStyle(Paint.Style.STROKE);
            tracePaint.setColor(classColors[i]);
            tracePaint.setStrokeWidth(1);
            tracePaint.setTextSize(HISTORY_PLOT_TEXT_SIZE);
            classTracePaints[i] = tracePaint;
            final Paint textPaint = new Paint();
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(classColors[i]);
            textPaint.setStrokeWidth(1);
            textPaint.setTextSize(HISTORY_PLOT_TEXT_SIZE);
            classTextPaints[i] = textPaint;
        }
    }
}
