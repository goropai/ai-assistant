package com.speedy.assistant.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class CocoDetectorTest {

    @Test
    fun outputsOfEfficientDetLite0() {
        // Як на телефоні: рамки, класи, впевненість, кількість.
        val shapes = listOf(intArrayOf(1, 25, 4), intArrayOf(1, 25), intArrayOf(1, 25), intArrayOf(1))
        assertEquals(Outputs(boxes = 0, classes = 1, scores = 2, count = 3), Outputs.locate(shapes))
    }

    @Test
    fun outputsFoundByShapeInOtherOrder() {
        val shapes = listOf(intArrayOf(1), intArrayOf(1, 10), intArrayOf(1, 10, 4), intArrayOf(1, 10))
        assertEquals(Outputs(boxes = 2, classes = 1, scores = 3, count = 0), Outputs.locate(shapes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOutputsRejected() {
        Outputs.locate(listOf(intArrayOf(1, 1000)))
    }

    @Test
    fun parseFiltersByScoreAndCount() {
        val boxes = arrayOf(
            floatArrayOf(0.1f, 0.2f, 0.9f, 0.6f),
            floatArrayOf(-0.1f, 0.5f, 0.4f, 1.2f), // рамка трохи за межами кадру
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f), // за межами count
        )
        val classes = floatArrayOf(0f, 16f, 17f, 0f)
        val scores = floatArrayOf(0.9f, 0.6f, 0.3f, 0.95f)

        val result = parseDetections(boxes, classes, scores, count = 3, threshold = 0.5f)

        assertEquals(
            listOf(
                Detection(0, 0.9f, left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.9f),
                Detection(16, 0.6f, left = 0.5f, top = 0f, right = 1f, bottom = 0.4f),
            ),
            result,
        )
        assertEquals(Kind.CAT, Kind.fromCoco(result[1].cocoClass))
    }
}
