package io.github.jqssun.airplay

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val mode = display.mode
        return mode.physicalWidth to mode.physicalHeight
    }
    // Display.Mode is API 23; below it the real size is the panel's current mode
    val size = Point()
    display.getRealSize(size)
    return size.x to size.y
}
