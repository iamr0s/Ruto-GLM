package com.rosan.ruto.service.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.view.Display
import android.view.DisplayAddress
import android.view.DisplayInfo
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import com.rosan.installer.ext.util.graphics.pixelCopy
import com.rosan.ruto.display.BitmapWrapper
import com.rosan.ruto.service.IDisplayManager
import java.util.concurrent.ConcurrentHashMap

abstract class DisplayManagerServiceStub @Keep constructor(private val context: Context) :
    IDisplayManager.Stub() {
    protected val manager = context.getSystemService<DisplayManager>()!!

    protected val imageReaders = mutableListOf<ImageReader>()

    protected val displayMap = ConcurrentHashMap<Int, VirtualDisplay>()

    val global: DisplayManagerGlobal
        get() = DisplayManagerGlobal.getInstance()

    protected fun createNewName() =
        "ruto-display:${System.currentTimeMillis()}"

    protected fun createNewSurface(width: Int, height: Int): Surface {
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        imageReaders.add(imageReader)
        return imageReader.surface
    }

    protected fun requireSurface(width: Int, height: Int, surface: Surface? = null): Surface =
        surface ?: createNewSurface(width, height)

    override fun onDestroy() {
        for (display in displayMap.values) {
            try {
                display.release()
            } catch (_: Throwable) {
            }
        }
    }

    override fun getDisplayIds(): IntArray = global.displayIds

    override fun getDisplays(): List<DisplayInfo> = displayIds.map { getDisplayInfo(it) }

    override fun getDisplayInfo(displayId: Int): DisplayInfo = global.getDisplayInfo(displayId)

    override fun createDisplay2(surface: Surface?): Int {
        val displayInfo = getDisplayInfo(Display.DEFAULT_DISPLAY)
        val name = createNewName()
        val width = displayInfo.logicalWidth
        val height = displayInfo.logicalHeight
        val density = displayInfo.logicalDensityDpi

        return createDisplay(name, width, height, density, surface)
    }

    companion object {
        /**
         * Virtual display flags: Indicates that the display is trusted to show system decorations and
         * receive inputs without users' touch.
         *
         * @see #createVirtualDisplay
         * @see #VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
         * @hide
         */
        const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10

        /**
         * Virtual display flags: Indicates that the display should not be a part of the default
         * DisplayGroup and instead be part of a new DisplayGroup.
         *
         * @see #createVirtualDisplay
         * @hide
         */
        const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    }

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface?
    ): Int {
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_TRUSTED or
                VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
        val requireSurface = requireSurface(width, height, surface)
        val display =
            manager.createVirtualDisplay(name, width, height, density, requireSurface, flags)
        val displayId = display.display.displayId
        displayMap[displayId] = display
        return displayId
    }

    override fun isMyDisplay(displayId: Int): Boolean {
        return displayMap.containsKey(displayId)
    }

    override fun capture(displayId: Int): BitmapWrapper? {
        return BitmapWrapper(captureBitmap(displayId))
    }

    open fun captureBitmap(displayId: Int): Bitmap {
        val display = displayMap[displayId] ?: return shellCapture(displayId)
        val surface = display.surface
        val displayInfo = getDisplayInfo(displayId)
        val width = displayInfo.logicalWidth
        val height = displayInfo.logicalHeight

        return surface.pixelCopy(width, height)
    }

    private fun getDisplayPhysicalId(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return displayId.toString()

        val address = getDisplayInfo(displayId).address as? DisplayAddress.Physical ?: return null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val clazz = DisplayAddress.Physical::class.java

            @SuppressLint("BlockedPrivateApi") val method =
                clazz.getDeclaredField("mPhysicalDisplayId")
            method.isAccessible = true
            return method.get(address)?.toString().toString()
        }

        return address.physicalDisplayId.toString()
    }

    protected fun shellCapture(displayId: Int): Bitmap {
        val process =
            ProcessBuilder("screencap", "-d", getDisplayPhysicalId(displayId), "-p").start()
        val bitmap = BitmapFactory.decodeStream(process.inputStream.buffered())
        process.waitFor()
        return bitmap
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        displayMap.computeIfPresent(displayId) { _, display ->
            val width = display.display.width
            val height = display.display.height

            releaseSurface(display.surface)

            val requireSurface = requireSurface(width, height, surface)
            display.surface = requireSurface
            return@computeIfPresent display
        }
    }

    override fun release(displayId: Int) {
        displayMap.computeIfPresent(displayId) { _, display ->
            releaseSurface(display.surface)
            display.release()
            return@computeIfPresent null
        }
    }

    private fun releaseSurface(surface: Surface) {
        imageReaders.removeIf {
            it.surface == surface
        }
    }
}