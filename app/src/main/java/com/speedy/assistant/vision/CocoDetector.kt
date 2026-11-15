package com.speedy.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * EfficientDet-Lite0 (COCO) напряму через TensorFlow Lite Interpreter.
 *
 * Task Library (tensorflow-lite-task-vision) тут не годиться: її нативна бібліотека
 * потребує strtod_l, якого немає в Android 7.1 (з'явився в API 26).
 *
 * Модель уже містить TFLite_Detection_PostProcess, тож на виході готові рамки
 * [ymin, xmin, ymax, xmax] у частках входу, номери класів COCO, впевненість і кількість.
 */
class CocoDetector(context: Context, model: String, threads: Int) : Closeable {
    private val interpreter: Interpreter
    private val width: Int
    private val height: Int
    private val floatInput: Boolean
    private val input: ByteBuffer
    private val pixels: IntArray
    private val outputs: Outputs

    init {
        val buffer = context.assets.openFd(model).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
        val tensor = interpreter.getInputTensor(0)
        val shape = tensor.shape() // [1, h, w, 3]
        height = shape[1]
        width = shape[2]
        floatInput = tensor.dataType() == DataType.FLOAT32
        input = ByteBuffer.allocateDirect(width * height * 3 * if (floatInput) 4 else 1)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(width * height)
        outputs = Outputs.locate(List(interpreter.outputTensorCount) { interpreter.getOutputTensor(it).shape() })
    }

    /** Розпізнані об'єкти з впевненістю не менше [threshold]; рамки — у частках кадру. */
    fun detect(frame: Bitmap, threshold: Float): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== frame) scaled.recycle()
        input.rewind()
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            if (floatInput) {
                input.putFloat((r - 127.5f) / 127.5f)
                input.putFloat((g - 127.5f) / 127.5f)
                input.putFloat((b - 127.5f) / 127.5f)
            } else {
                input.put(r.toByte()).put(g.toByte()).put(b.toByte())
            }
        }
        input.rewind()

        val max = interpreter.getOutputTensor(outputs.scores).shape()[1]
        val boxes = Array(1) { Array(max) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(max) }
        val scores = Array(1) { FloatArray(max) }
        val count = FloatArray(1)
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(outputs.boxes to boxes, outputs.classes to classes, outputs.scores to scores, outputs.count to count),
        )
        return parseDetections(boxes[0], classes[0], scores[0], count[0].toInt(), threshold)
    }

    override fun close() = interpreter.close()
}

/** Один об'єкт: клас COCO (з 0), впевненість, рамка в частках кадру. */
data class Detection(
    val cocoClass: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Номери вихідних тензорів TFLite_Detection_PostProcess у графі моделі. */
internal data class Outputs(val boxes: Int, val classes: Int, val scores: Int, val count: Int) {
    companion object {
        /**
         * Постпроцесинг віддає рамки [1, N, 4], класи [1, N], впевненість [1, N] і кількість [1].
         * Імена тензорів ненадійні (у EfficientDet-Lite0 це "StatefulPartitionedCall:3…:0"
         * у зворотному порядку), тому рамки й кількість шукаємо за формою, а класи
         * й впевненість — за порядком у графі (класи йдуть першими).
         */
        fun locate(shapes: List<IntArray>): Outputs {
            val boxes = shapes.indexOfFirst { it.size == 3 && it[2] == 4 }
            val count = shapes.indexOfFirst { it.size == 1 }
            val perBox = shapes.indices.filter { shapes[it].size == 2 }
            require(shapes.size == 4 && boxes >= 0 && count >= 0 && perBox.size == 2) {
                "Невідомі виходи детектора: " + shapes.joinToString { it.contentToString() }
            }
            return Outputs(boxes, classes = perBox[0], scores = perBox[1], count = count)
        }
    }
}

internal fun parseDetections(
    boxes: Array<FloatArray>,
    classes: FloatArray,
    scores: FloatArray,
    count: Int,
    threshold: Float,
): List<Detection> =
    (0 until count.coerceIn(0, scores.size))
        .filter { scores[it] >= threshold }
        .map {
            val (ymin, xmin, ymax, xmax) = boxes[it].map { v -> v.coerceIn(0f, 1f) }
            Detection(classes[it].toInt(), scores[it], xmin, ymin, xmax, ymax)
        }
