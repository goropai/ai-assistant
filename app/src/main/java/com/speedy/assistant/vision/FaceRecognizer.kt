package com.speedy.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max

/** Обличчя в кадрі: відбиток і частка кадру, яку воно займає. */
class FaceObservation(val embedding: FloatArray, val area: Float)

/**
 * Офлайн-розпізнавання облич: ML Kit знаходить обличчя (модель вбудована в APK),
 * MobileFaceNet (assets, MIT) перетворює вирівняне обличчя 112×112 на відбиток з 192 чисел.
 * Викликати не з головного потоку.
 */
class FaceRecognizer(context: Context) : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(MIN_FACE)
            .build()
    )
    private val interpreter: Interpreter
    private val batch: Int
    private val embeddingSize: Int
    private val input: ByteBuffer
    private val pixels = IntArray(SIZE * SIZE)

    init {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        // Модель експортована з пакетом із 2 облич ([2, 112, 112, 3]); подаємо одне двічі.
        batch = interpreter.getInputTensor(0).shape()[0]
        embeddingSize = interpreter.getOutputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(batch * SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    }

    /** Обличчя на вертикально поставленому кадрі (не більше [MAX_FACES], найбільші першими). */
    fun recognize(frame: Bitmap): List<FaceObservation> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
            .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            .take(MAX_FACES)
        return faces.mapNotNull { face ->
            val box = face.boundingBox
            val crop = align(frame, box.exactCenterX(), box.exactCenterY(), max(box.width(), box.height()) * 1.15f, face.headEulerAngleZ)
                ?: return@mapNotNull null
            val area = box.width().toFloat() * box.height() / (frame.width * frame.height)
            FaceObservation(embed(crop), area)
        }
    }

    /** Вирізає квадрат навколо обличчя і повертає його так, щоб очі були на одній лінії. */
    private fun align(frame: Bitmap, cx: Float, cy: Float, side: Float, roll: Float): Bitmap? {
        if (side < MIN_PIXELS) return null
        val scale = SIZE / side
        val matrix = Matrix().apply {
            postTranslate(-cx, -cy)
            postRotate(roll) // ML Kit: додатний кут — голова нахилена проти годинникової
            postScale(scale, scale)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(frame, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun embed(face: Bitmap): FloatArray {
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        face.recycle()
        input.rewind()
        repeat(batch) {
            for (p in pixels) {
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()
        val out = Array(batch) { FloatArray(embeddingSize) }
        interpreter.run(input, out)
        return FaceBook.normalize(out[0])
    }

    override fun close() {
        detector.close()
        interpreter.close()
    }

    private companion object {
        const val MODEL = "mobilefacenet.tflite"
        const val SIZE = 112
        const val MIN_FACE = 0.15f
        const val MIN_PIXELS = 40f
        const val MAX_FACES = 3
    }
}
