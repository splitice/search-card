package com.splitice.searchcard

import kotlin.test.*

class PanelPlacementTest {
    private val window = PanelBounds(0, 0, 400, 900)
    private val safe = PanelBounds(0, 24, 400, 876)
    private val widget = PanelBounds(16, 100, 384, 148)

    private fun place(bounds: PanelBounds = widget, keyboard: Int = 600, area: PanelBounds = safe,
                      field: Int = 48, status: Int = 64) =
        placeWidgetPanel(bounds, area, field, status, 72, 8, keyboard)

    @Test fun `field stays exactly over the widget with room for keyboard and results`() {
        val p = place()
        assertEquals(16, p.left)
        assertEquals(100, p.top)
        assertEquals(368, p.width)
        assertEquals(48, p.fieldHeight)
        assertEquals(592, p.top + p.fieldHeight + p.bodyHeight)
    }

    @Test fun `low widget moves upward only enough to show status and one result`() {
        val p = place(widget.copy(top = 700, bottom = 748))
        assertEquals(408, p.top)
        assertEquals(136, p.bodyHeight)
        assertEquals(16, p.left)
        assertEquals(368, p.width)
    }

    @Test fun `narrow widgets keep their width and account for wrapped status`() {
        val p = place(PanelBounds(260, 500, 380, 548), status = 112)
        assertEquals(120, p.width)
        assertEquals(260, p.left)
        assertEquals(360, p.top)
    }

    @Test fun `large fonts increase field height and shift only as required`() {
        val p = place(widget.copy(top = 450, bottom = 498), field = 80, status = 100)
        assertEquals(340, p.top)
        assertEquals(80, p.fieldHeight)
        assertEquals(172, p.bodyHeight)
    }

    @Test fun `cutouts clamp horizontal placement and the safe top`() {
        val p = place(PanelBounds(0, 0, 368, 48), area = PanelBounds(30, 60, 390, 876))
        assertEquals(30, p.left)
        assertEquals(60, p.top)
        assertEquals(360, p.width)
    }

    @Test fun `short windows pin the field at the top and leave a scroll viewport`() {
        val p = place(keyboard = 150)
        assertEquals(24, p.top)
        assertEquals(48, p.fieldHeight)
        assertEquals(70, p.bodyHeight)
    }

    @Test fun `hardware keyboard uses available window and system bar boundaries`() {
        val p = place(keyboard = 900)
        assertEquals(100, p.top)
        assertEquals(876, p.top + p.fieldHeight + p.bodyHeight)
    }

    @Test fun `opening and closing keyboard interpolate between final placements`() {
        val low = widget.copy(top = 650, bottom = 698)
        fun opening(bottom: Int) = placeWidgetPanel(low, safe, 48, 64, 72, 8, bottom, 900, 600)
        assertEquals(650, opening(900).top)
        assertEquals(529, opening(750).top)
        assertEquals(408, opening(600).top)
        val closing = placeWidgetPanel(low, safe, 48, 64, 72, 8, 750, 600, 900)
        assertEquals(opening(750), closing)
        assertEquals(100, placeWidgetPanel(widget, safe, 48, 64, 72, 8, 750, 900, 600).top)
    }

    @Test fun `missing invalid and stale source bounds select fallback`() {
        assertNull(localWidgetBounds(null, window, 0, 0))
        for (bad in listOf(PanelBounds(0, 0, 0, 0), PanelBounds(-100, 20, 50, 70),
            PanelBounds(0, 0, 800, 48), PanelBounds(Int.MIN_VALUE, 0, Int.MAX_VALUE, 48))) {
            assertNull(localWidgetBounds(WidgetAnchor(bad, window), window, 0, 0))
        }
        val anchor = WidgetAnchor(widget, window)
        assertNull(localWidgetBounds(anchor, PanelBounds(0, 0, 900, 400), 0, 0))
        assertNull(localWidgetBounds(anchor, PanelBounds(10, 0, 410, 900), 10, 0))
    }

    @Test fun `screen coordinates translate into the current activity root`() {
        val window = PanelBounds(100, 200, 500, 1100)
        val anchor = WidgetAnchor(PanelBounds(120, 300, 480, 348), window)
        assertEquals(PanelBounds(20, 76, 380, 124), localWidgetBounds(anchor, window, 100, 224))
    }
}
