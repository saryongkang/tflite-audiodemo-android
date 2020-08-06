package com.google.euphonia.trainableswitches.audiodemo

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.DisplayMetrics
import java.nio.FloatBuffer

/** Helper functions for making plots.  */
class Plotter(
    private val context: Context,
    private val classNames: Array<String>,
    private val probThreshold: Float
) {
    private val startTimeNanos: Long
    private val screenWidth: Int
    private val screenHeight: Int

    /**
     * History traces of the output class probabilites. The first dimension is for time steps; the
     * second is for output classes.
     */
    private val historyProbs: Array<FloatArray>

    /** Index to the elements of historyProbs. Keeps track of where we are in the rolling display.  */
    private var historyProbsIndex: Int
    private var numPositiveEvents: Int
    private var basicTextPaint: Paint? = null
    private var supraThresholdTextPaint: Paint? = null
    private var waveformPaint: Paint? = null
    private var axesPaint: Paint? = null
    private var axesTextPaint: Paint? = null
    private lateinit var classTracePaints: Array<Paint?>
    private lateinit var classTextPaints: Array<Paint?>

    /**
     * Plot a FloatBuffer as a curve on in a canvas.
     *
     * @param canvas The canvas to plut the curve in.
     * @param xs The FloatBuffer to plot.
     * @param length Number of elements in the FloatBuffer.
     * @param scalingFactor Scaling factor for the elements of `xs`.
     */
    fun plotWaveform(
        canvas: Canvas,
        xs: FloatBuffer,
        length: Int,
        scalingFactor: Float
    ) {
        val halfHeight =
            (WAVEFORM_WINDOW_BOTTOM_FRAC - WAVEFORM_WINDOW_TOP_FRAC) * screenHeight / 2
        val zeroY =
            (WAVEFORM_WINDOW_TOP_FRAC + WAVEFORM_WINDOW_BOTTOM_FRAC) / 2 * screenHeight
        val stepX = screenWidth.toFloat() / length
        val path = Path()
        var currentX = 0f
        path.moveTo(currentX, zeroY + xs[0] * scalingFactor * halfHeight)
        for (i in 1 until length) {
            currentX += stepX
            path.lineTo(currentX, zeroY + xs[i] * scalingFactor * halfHeight)
        }
        canvas.drawPath(path, waveformPaint!!)
    }

    /**
     * Plot latest prediction results. Including inference latency and probabilities outputted by the
     * model.
     *
     * @param canvas The canvas to plot the results.
     * @param predictionProbs Probabilities predicted by the model for the output classes.
     * @param predictionLatencyMs Prediction (inference) latency in milliseconds.
     */
    fun plotPredictions(
        canvas: Canvas,
        predictionProbs: FloatArray,
        predictionLatencyMs: Float
    ) {
        var currentY =
            TEXT_LINE_HEIGHT
        canvas.drawText(
            String.format("Inference latency: %.3f ms", predictionLatencyMs),
            TEXT_X,
            currentY,
            basicTextPaint!!
        )
        currentY += TEXT_LINE_HEIGHT
        if (predictionProbs.size == 0) {
            return
        }
        if (java.lang.Float.isNaN(predictionProbs[0])) {
            // This can happen when the app has just started up and the samples from the microphone are
            // still all zero.
            canvas.drawText(
                "No valid prediction...",
                TEXT_X,
                currentY,
                basicTextPaint!!
            )
            return
        }
        for (i in predictionProbs.indices) {
            val prob = predictionProbs[i]
            val isPositive =
                prob > probThreshold && classNames[i] != Utils.BACKGROUND_NOISE_CLASS_NAME
            if (isPositive) {
                numPositiveEvents++
            }
            val paint =
                if (isPositive) supraThresholdTextPaint else basicTextPaint
            val classText = String.format("%s: %.6f", classNames[i], prob)
            canvas.drawText(
                classText,
                TEXT_X,
                currentY,
                paint!!
            )
            currentY += TEXT_LINE_HEIGHT
            historyProbs[historyProbsIndex][i] = prob
        }
        historyProbsIndex = (historyProbsIndex + 1) % historyProbs.size
        plotHistoryProbs(canvas)
    }

    /**
     * Plot probability history.
     *
     *
     * Displays history probabilities for all non-background-noise classes on the screen, in a
     * rolling fashion.
     *
     * @param canvas The canvas to plot the traces on.
     */
    private fun plotHistoryProbs(canvas: Canvas) {
        val height =
            (HISTORY_PROB_BOTTOM_FRAC - HISTORY_PROB_TOP_FRAC) * screenHeight
        val maxY =
            HISTORY_PROB_BOTTOM_FRAC * screenHeight
        val numSteps = historyProbs.size
        val numClasses: Int = historyProbs[0].size
        val stepX = screenWidth.toFloat() / numSteps
        drawHistoryProbsAxes(canvas)
        for (k in 0 until numClasses) {
            if (classNames[k] == Utils.BACKGROUND_NOISE_CLASS_NAME) {
                continue
            }
            val path = Path()
            val paint = classTracePaints[k % classTracePaints.size]
            var currentX = 0f
            var p =
                if (java.lang.Float.isNaN(historyProbs[0][k])) 0f else historyProbs[0][k]
            path.moveTo(currentX, maxY - p)
            for (i in 1 until historyProbsIndex) {
                currentX += stepX
                p = if (java.lang.Float.isNaN(historyProbs[i][k])) 0f else historyProbs[i][k]
                path.lineTo(currentX, maxY - height * p)
            }
            canvas.drawPath(path, paint!!)
            canvas.drawText(
                classNames[k],
                currentX,
                maxY - height * p,
                classTextPaints[k % classTextPaints.size]!!
            )
        }

        // Draw text to indicate # of positive events and total elapsed time since app start.
        canvas.drawText(
            String.format(
                "#(positive)=%d, duration=%ds",
                numPositiveEvents,
                (SystemClock.elapsedRealtimeNanos() - startTimeNanos) / Utils.NANOS_IN_SEC
            ),
            TEXT_X,
            SUMMARY_TEXT_FRAC * screenHeight,
            basicTextPaint!!
        )
    }

    /** Draw axes for the history probability plot.  */
    private fun drawHistoryProbsAxes(canvas: Canvas) {
        val yTicks = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)
        val height =
            (HISTORY_PROB_BOTTOM_FRAC - HISTORY_PROB_TOP_FRAC) * screenHeight
        val maxY =
            HISTORY_PROB_BOTTOM_FRAC * screenHeight
        for (yTick in yTicks) {
            val path = Path()
            val y = maxY - height * yTick
            path.moveTo(0f, y)
            path.lineTo(screenWidth.toFloat(), y)
            canvas.drawPath(path, axesPaint!!)
            canvas.drawText(
                String.format(if (yTick == 0f) "p=%.2f" else "%.2f", yTick),
                0f,
                y,
                axesTextPaint!!
            )
        }
    }

    private val screenDimensions: DisplayMetrics
        private get() {
            val displayMetrics = DisplayMetrics()
            (context as Activity).windowManager.defaultDisplay.getMetrics(displayMetrics)
            return displayMetrics
        }

    /** Create Paint objects for later use.  */
    private fun createPaints() {
        basicTextPaint = Paint()
        basicTextPaint!!.style = Paint.Style.FILL
        basicTextPaint!!.color = Color.GRAY
        basicTextPaint!!.strokeWidth = 1f
        basicTextPaint!!.textSize = TEXT_LINE_HEIGHT
        supraThresholdTextPaint = Paint()
        supraThresholdTextPaint!!.style = Paint.Style.FILL
        supraThresholdTextPaint!!.color = Color.GREEN
        supraThresholdTextPaint!!.strokeWidth = 1f
        supraThresholdTextPaint!!.textSize = TEXT_LINE_HEIGHT
        waveformPaint = Paint()
        waveformPaint!!.style = Paint.Style.STROKE
        waveformPaint!!.color = Color.GRAY
        waveformPaint!!.strokeWidth = 1f
        axesPaint = Paint()
        axesPaint!!.style = Paint.Style.STROKE
        axesPaint!!.color = Color.DKGRAY
        axesPaint!!.strokeWidth = 1f
        axesTextPaint = Paint()
        axesTextPaint!!.style = Paint.Style.FILL
        axesTextPaint!!.color = Color.DKGRAY
        axesTextPaint!!.textSize = HISTORY_PLOT_TEXT_SIZE
        classTracePaints =
            arrayOfNulls(classColors.size)
        classTextPaints =
            arrayOfNulls(classColors.size)
        for (i in classColors.indices) {
            val tracePaint = Paint()
            tracePaint.style = Paint.Style.STROKE
            tracePaint.color = classColors[i]
            tracePaint.strokeWidth = 1f
            tracePaint.textSize = HISTORY_PLOT_TEXT_SIZE
            classTracePaints[i] = tracePaint
            val textPaint = Paint()
            textPaint.style = Paint.Style.FILL
            textPaint.color = classColors[i]
            textPaint.strokeWidth = 1f
            textPaint.textSize = HISTORY_PLOT_TEXT_SIZE
            classTextPaints[i] = textPaint
        }
    }

    companion object {
        /** Period of the rolling display of probability, in seconds.  */
        private const val TEXT_LINE_HEIGHT = 50f

        /** Text size in the history probability plot.  */
        private const val HISTORY_PLOT_TEXT_SIZE = 40f

        /** x coordinate for basic texts on the screen.  */
        private const val TEXT_X = 5f

        /** Fraction of the screen height for the top of the waveform plot.  */
        private const val WAVEFORM_WINDOW_TOP_FRAC = 0.2f

        /** Fraction of the screen height for the bottom of the waveform plot.  */
        private const val WAVEFORM_WINDOW_BOTTOM_FRAC = 0.6f

        /** Fraction of the screen height for the top of the history probability plot.  */
        private const val HISTORY_PROB_TOP_FRAC = 0.6f

        /** Fraction of the screen height for the bottom of the history probability plot.  */
        private const val HISTORY_PROB_BOTTOM_FRAC = 0.9f

        /** Fraction of the screen height for showing string summarizing the # of positive events.  */
        private const val SUMMARY_TEXT_FRAC = 0.95f

        /** Colors used to represent the output classes of the model.  */
        private val classColors = intArrayOf(
            Color.CYAN,
            Color.GREEN,
            Color.MAGENTA,
            Color.RED
        )
    }

    /**
     * Constructor of Plotter.
     *
     * @param context Android app context.
     * @param classNames Output class names of the model.
     * @param probThreshold Threshold probability value, above which a non-background-noise class is
     * considered active (i.e., a suprthreshold or positive event).
     */
    init {
        val displayMetrics = screenDimensions
        startTimeNanos = SystemClock.elapsedRealtimeNanos()
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels
        historyProbs =
            Array(screenWidth) { FloatArray(classNames.size) }
        historyProbsIndex = 0
        numPositiveEvents = 0
        createPaints()
    }
}