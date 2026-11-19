package com.speedy.assistant

import com.speedy.assistant.phone.Box
import com.speedy.assistant.phone.MiniPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlacementTest {
    private val screen = Box(0, 0, 720, 1280)
    private val mini = Box(416, 168, 696, 348) // правий верхній кут, як на J5

    @Test
    fun noVideoStaysPut() {
        assertEquals(MiniPlacement.Place(168, 1f), MiniPlacement.place(mini, null, screen, 48, 16))
    }

    @Test
    fun movesBelowVideo() {
        // Відео NewPipe у звичайному режимі — верхня третина екрана.
        val video = Box(0, 48, 720, 453)
        assertEquals(MiniPlacement.Place(469, 1f), MiniPlacement.place(mini, video, screen, 48, 16))
    }

    @Test
    fun notOverlappingStaysPut() {
        val low = Box(416, 900, 696, 1080)
        assertEquals(MiniPlacement.Place(900, 1f), MiniPlacement.place(low, Box(0, 48, 720, 453), screen, 48, 16))
    }

    @Test
    fun fullscreenVideoMakesItSeeThrough() {
        val land = Box(0, 0, 1280, 720)
        val miniLand = Box(976, 168, 1256, 348)
        assertEquals(MiniPlacement.Place(168, MiniPlacement.SEE_THROUGH), MiniPlacement.place(miniLand, land, land, 0, 16))
    }

    @Test
    fun noRoomAnywhereMakesItSeeThrough() {
        val tall = Box(0, 100, 720, 1200) // 86% екрана — уже повноекранне
        assertEquals(MiniPlacement.SEE_THROUGH, MiniPlacement.place(mini, tall, screen, 48, 16).alpha)
        val middle = Box(0, 150, 720, 1100) // 74%: ні під, ні над не влазить
        assertEquals(MiniPlacement.Place(168, MiniPlacement.SEE_THROUGH), MiniPlacement.place(mini, middle, screen, 48, 16))
    }

    @Test
    fun movesAboveWhenNoRoomBelow() {
        val bottomVideo = Box(0, 700, 720, 1200)
        val lowMini = Box(416, 800, 696, 980)
        assertEquals(MiniPlacement.Place(504, 1f), MiniPlacement.place(lowMini, bottomVideo, screen, 48, 16))
    }
}
