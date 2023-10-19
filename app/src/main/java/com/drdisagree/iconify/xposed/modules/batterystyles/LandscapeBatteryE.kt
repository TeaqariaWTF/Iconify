/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.drdisagree.iconify.xposed.modules.batterystyles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.graphics.PathParser
import com.drdisagree.iconify.R
import com.drdisagree.iconify.xposed.HookRes.modRes
import com.drdisagree.iconify.xposed.utils.SettingsLibUtils
import kotlin.math.floor

@SuppressLint("DiscouragedApi")
open class LandscapeBatteryE(private val context: Context, frameColor: Int) :
    BatteryDrawable() {

    // Need to load:
    // 1. perimeter shape
    // 2. fill mask (if smaller than perimeter, this would create a fill that
    //    doesn't touch the walls
    private val perimeterPath = Path()
    private val scaledPerimeter = Path()
    private val errorPerimeterPath = Path()
    private val scaledErrorPerimeter = Path()

    // Fill will cover the whole bounding rect of the fillMask, and be masked by the path
    private val fillMask = Path()
    private val scaledFill = Path()
    private val fillOutlinePath = Path()
    private val scaledfillOutline = Path()

    // Based off of the mask, the fill will interpolate across this space
    private val fillRect = RectF()

    // Top of this rect changes based on level, 100% == fillRect
    private val levelRect = RectF()
    private val levelPath = Path()

    // Updates the transform of the paths when our bounds change
    private val scaleMatrix = Matrix()
    private val padding = Rect()

    // The net result of fill + perimeter paths
    private val unifiedPath = Path()

    // Bolt path (used while charging)
    private val boltPath = Path()
    private val scaledBolt = Path()

    // Plus sign (used for power save mode)
    private val plusPath = Path()
    private val scaledPlus = Path()

    private var intrinsicHeight: Int
    private var intrinsicWidth: Int

    // To implement hysteresis, keep track of the need to invert the interior icon of the battery
    private var invertFillIcon = false

    // Colors can be configured based on battery level (see res/values/arrays.xml)
    private var colorLevels: IntArray

    private var fillColor: Int = Color.WHITE
    private var backgroundColor: Int = Color.WHITE

    // updated whenever level changes
    private var levelColor: Int = Color.WHITE

    // Dual tone implies that battery level is a clipped overlay over top of the whole shape
    private var dualTone = false

    private var batteryLevel = 0

    private val invalidateRunnable: () -> Unit = {
        invalidateSelf()
    }

    open var criticalLevel: Int = 5

    var charging = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setChargingEnabled(charging: Boolean) {
        this.charging = charging
        postInvalidate()
    }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setPowerSavingEnabled(powerSaveEnabled: Boolean) {
        this.powerSaveEnabled = powerSaveEnabled
        postInvalidate()
    }

    var showPercent = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setShowPercentEnabled(showPercent: Boolean) {
        this.showPercent = showPercent
        postInvalidate()
    }

    private val fillColorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.SRC
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillColorStrokeProtection = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.CLEAR
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = SettingsLibUtils.getColorAttrDefaultColor(context, android.R.attr.colorError)
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
        p.blendMode = BlendMode.SRC
    }

    private val chargingPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val customFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val powerSavePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val powerSaveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val scaledFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val scaledPerimeterPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    private val scaledPerimeterPaintDef = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
    }

    // Only used if dualTone is set to true
    private val dualToneBackgroundFill = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 85 // ~0.3 alpha by default
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
    }

    init {
        val density = context.resources.displayMetrics.density
        intrinsicHeight = (HEIGHT * density).toInt()
        intrinsicWidth = (WIDTH * density).toInt()

        val res = context.resources
        val levels = res.obtainTypedArray(
            res.getIdentifier(
                "batterymeter_color_levels", "array", context.packageName
            )
        )
        val colors = res.obtainTypedArray(
            res.getIdentifier(
                "batterymeter_color_values", "array", context.packageName
            )
        )
        val n = levels.length()
        colorLevels = IntArray(2 * n)
        for (i in 0 until n) {
            colorLevels[2 * i] = levels.getInt(i, 0)
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = SettingsLibUtils.getColorAttrDefaultColor(
                    colors.getResourceId(i, 0), context
                )
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0)
            }
        }
        levels.recycle()
        colors.recycle()

        loadPaths()
    }

    override fun draw(c: Canvas) {
        c.saveLayer(null, null)
        unifiedPath.reset()
        levelPath.reset()
        levelRect.set(fillRect)
        val fillFraction = batteryLevel / 100f
        val fillLeft =
            if (batteryLevel == 100)
                fillRect.left
            else
                fillRect.left + (fillRect.width() * (1 - fillFraction))

        levelRect.left = floor(fillLeft.toDouble()).toFloat()
        levelPath.addRect(levelRect, Path.Direction.CCW)

        scaledFillPaint.alpha = if (scaledFillAlpha) 100 else 0
        scaledPerimeterPaint.alpha =
            if (scaledPerimeterAlpha) 100 else scaledPerimeterPaintDef.alpha

        // The perimeter should never change
        c.drawPath(scaledFill, scaledFillPaint)
        c.drawPath(scaledPerimeter, scaledPerimeterPaint)
        // If drawing dual tone, the level is used only to clip the whole drawable path
        if (!dualTone) {
            unifiedPath.op(levelPath, Path.Op.UNION)
        }

        fillPaint.color = levelColor
        val black = Color.BLACK

        if (customBlendColor) {
            chargingPaint.color =
                if (chargingColor == Color.BLACK) Color.TRANSPARENT else chargingColor

            powerSavePaint.color =
                if (powerSaveColor == Color.BLACK) SettingsLibUtils.getColorAttrDefaultColor(
                    context,
                    android.R.attr.colorError
                ) else powerSaveColor

            powerSaveFillPaint.color =
                if (powerSaveFillColor == Color.BLACK) Color.TRANSPARENT else powerSaveFillColor
        } else {
            chargingPaint.color = Color.TRANSPARENT
            powerSavePaint.color =
                SettingsLibUtils.getColorAttrDefaultColor(context, android.R.attr.colorError)
            powerSaveFillPaint.color = Color.TRANSPARENT
        }

        // Deal with unifiedPath clipping before it draws
        if (charging && !customChargingIcon) {
            // Clip out the bolt shape
            unifiedPath.op(scaledBolt, Path.Op.DIFFERENCE)
            if (!invertFillIcon) {
                c.drawPath(scaledBolt, fillPaint)
            }
        }

        if (dualTone) {
            // Dual tone means we draw the shape again, clipped to the charge level
            c.drawPath(unifiedPath, dualToneBackgroundFill)
            c.save()
            c.clipRect(
                0f,
                bounds.left - bounds.width() * fillFraction,
                bounds.right.toFloat(),
                bounds.left.toFloat()
            )
            c.drawPath(unifiedPath, fillPaint)
            c.restore()
        } else {
            // Non dual-tone means we draw the perimeter (with the level fill), and potentially
            // draw the fill again with a critical color
            if (customBlendColor) {
                if (charging) {
                    fillPaint.color = fillColor
                    c.clipOutPath(scaledfillOutline)
                    c.drawPath(unifiedPath, fillPaint)
                    fillPaint.color = levelColor
                } else if (powerSaveEnabled) {
                    c.drawPath(scaledErrorPerimeter, powerSavePaint)
                    c.clipOutPath(scaledfillOutline)
                    c.drawPath(levelPath, fillPaint)
                    c.drawPath(levelPath, powerSaveFillPaint)
                } else {
                    c.clipOutPath(scaledfillOutline)
                    customFillPaint.color = customFillColor
                    customFillPaint.shader =
                        if (customFillColor != black && customFillGradColor != black) LinearGradient(
                            levelRect.left, 0f, 0f, levelRect.bottom,
                            customFillGradColor, customFillColor,
                            Shader.TileMode.CLAMP
                        ) else null
                    c.drawPath(
                        levelPath,
                        if (customFillColor == Color.BLACK) fillPaint else customFillPaint
                    )
                }
            } else {
                if (charging) {
                    fillPaint.color = fillColor
                    c.clipOutPath(scaledfillOutline)
                    c.drawPath(unifiedPath, fillPaint)
                    fillPaint.color = levelColor
                } else if (powerSaveEnabled) {
                    c.drawPath(scaledErrorPerimeter, errorPaint)
                    c.clipOutPath(scaledfillOutline)
                    c.drawPath(levelPath, fillPaint)
                } else {
                    fillPaint.color = fillColor
                    c.clipOutPath(scaledfillOutline)
                    c.drawPath(unifiedPath, fillPaint)
                    fillPaint.color = levelColor
                }
            }

            // Show colorError below this level
            if (batteryLevel <= CRITICAL_LEVEL && !charging) {
                c.save()
                c.clipPath(scaledFill)
                c.drawPath(levelPath, fillPaint)
                c.restore()
            }
        }

        if (charging) {
            if (!customChargingIcon) {
                c.clipOutPath(scaledBolt)
                c.drawPath(levelPath, chargingPaint)
                if (invertFillIcon) {
                    c.drawPath(scaledBolt, fillColorStrokePaint)
                } else {
                    c.drawPath(scaledBolt, fillColorStrokeProtection)
                }
            } else {
                c.drawPath(levelPath, chargingPaint)
            }
        } else if (powerSaveEnabled) {
            // If power save is enabled draw the perimeter path with colorError
            c.drawPath(scaledErrorPerimeter, powerSavePaint)
            c.drawPath(levelPath, powerSaveFillPaint)
            // And draw the plus sign on top of the fill
            if (!showPercent) {
                c.drawPath(scaledPlus, powerSavePaint)
            }
        }
        c.restore()

        var state = false
        if (customChargingIcon && charging) {
            state = true
        } else if (customChargingIcon && !charging) {
            state = true
        } else if (!customChargingIcon && charging) {
            state = false
        } else if (!customChargingIcon && !charging) {
            state = true
        }

        if (showPercent && state) {
            textPaint.textSize = bounds.width() * 0.20f
            val textHeight = +textPaint.fontMetrics.ascent
            val pctX = (bounds.width() + textHeight) * 0.84f
            val pctY = bounds.height() * 0.61f

            if (customBlendColor) {
                if (powerSaveEnabled && powerSaveFillColor == Color.BLACK) {
                    textPaint.color = fillColor
                    c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)

                    textPaint.color = fillColor.toInt().inv() or 0xFF000000.toInt()
                    c.save()
                    c.clipRect(
                        fillRect.left + (fillRect.width() * (1 - fillFraction)),
                        fillRect.top,
                        fillRect.right,
                        fillRect.bottom
                    )
                    c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)
                } else if (!powerSaveEnabled && customFillColor == Color.BLACK) {
                    textPaint.color = fillColor
                    c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)

                    textPaint.color = fillColor.toInt().inv() or 0xFF000000.toInt()
                    c.save()
                    c.clipRect(
                        fillRect.left + (fillRect.width() * (1 - fillFraction)),
                        fillRect.top,
                        fillRect.right,
                        fillRect.bottom
                    )
                    c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)
                } else {
                    textPaint.color = fillColor
                    c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)
                }
            } else {
                textPaint.color = fillColor
                c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)

                textPaint.color = fillColor.toInt().inv() or 0xFF000000.toInt()
                c.save()
                c.clipRect(
                    fillRect.left + (fillRect.width() * (1 - fillFraction)),
                    fillRect.top,
                    fillRect.right,
                    fillRect.bottom
                )
                c.drawText(batteryLevel.toString(), pctX, pctY, textPaint)
            }
            c.restore()
        }
    }

    private fun batteryColorForLevel(level: Int): Int {
        return when {
            charging || powerSaveEnabled -> fillColor
            else -> getColorForLevel(level)
        }
    }

    private fun getColorForLevel(level: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < colorLevels.size) {
            thresh = colorLevels[i]
            color = colorLevels[i + 1]
            if (level <= thresh) {

                // Respect tinting for "normal" level
                return if (i == colorLevels.size - 2) {
                    fillColor
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    /**
     * Alpha is unused internally, and should be defined in the colors passed to {@link setColors}.
     * Further, setting an alpha for a dual tone battery meter doesn't make sense without bounds
     * defining the minimum background fill alpha. This is because fill + background must be equal
     * to the net alpha passed in here.
     */
    override fun setAlpha(alpha: Int) {
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        fillColorStrokePaint.colorFilter = colorFilter
        dualToneBackgroundFill.colorFilter = colorFilter
    }

    /**
     * Deprecated, but required by Drawable
     */
    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat"),
    )
    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun getIntrinsicHeight(): Int {
        return intrinsicHeight
    }

    override fun getIntrinsicWidth(): Int {
        return intrinsicWidth
    }

    /**
     * Set the fill level
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun setBatteryLevel(l: Int) {
        // invertFillIcon = if (l >= 67) true else if (l <= 33) false else invertFillIcon
        batteryLevel = l
        levelColor = batteryColorForLevel(batteryLevel)
        invalidateSelf()
    }

    fun getBatteryLevel(): Int {
        return batteryLevel
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateSize()
    }

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padding.left = left
        padding.top = top
        padding.right = right
        padding.bottom = bottom

        updateSize()
    }

    override fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        fillColor = if (dualTone) fgColor else singleToneColor

        fillPaint.color = fillColor
        fillColorStrokePaint.color = fillColor

        scaledFillPaint.color = fillColor
        scaledPerimeterPaint.color = fillColor
        scaledPerimeterPaintDef.color = fillColor

        backgroundColor = bgColor
        dualToneBackgroundFill.color = bgColor

        // Also update the level color, since fillColor may have changed
        levelColor = batteryColorForLevel(batteryLevel)

        invalidateSelf()
    }

    private fun postInvalidate() {
        unscheduleSelf(invalidateRunnable)
        scheduleSelf(invalidateRunnable, 0)
    }

    private fun updateSize() {
        val b = bounds
        if (b.isEmpty) {
            scaleMatrix.setScale(1f, 1f)
        } else {
            scaleMatrix.setScale((b.right / WIDTH), (b.bottom / HEIGHT))
        }

        perimeterPath.transform(scaleMatrix, scaledPerimeter)
        errorPerimeterPath.transform(scaleMatrix, scaledErrorPerimeter)
        fillMask.transform(scaleMatrix, scaledFill)
        scaledFill.computeBounds(fillRect, true)
        fillOutlinePath.transform(scaleMatrix, scaledfillOutline)
        boltPath.transform(scaleMatrix, scaledBolt)
        plusPath.transform(scaleMatrix, scaledPlus)

        // It is expected that this view only ever scale by the same factor in each dimension, so
        // just pick one to scale the strokeWidths
        val scaledStrokeWidth =
            (b.right / WIDTH * PROTECTION_STROKE_WIDTH).coerceAtLeast(PROTECTION_MIN_STROKE_WIDTH)

        fillColorStrokePaint.strokeWidth = scaledStrokeWidth
        fillColorStrokeProtection.strokeWidth = scaledStrokeWidth
    }

    @SuppressLint("RestrictedApi")
    private fun loadPaths() {
        val pathString = modRes.getString(R.string.config_landscapeBatteryPerimeterPathE)
        perimeterPath.set(PathParser.createPathFromPathData(pathString))
        perimeterPath.computeBounds(RectF(), true)

        val errorPathString = modRes.getString(R.string.config_landscapeBatteryErrorPerimeterPathE)
        errorPerimeterPath.set(PathParser.createPathFromPathData(errorPathString))
        errorPerimeterPath.computeBounds(RectF(), true)

        val fillMaskString = modRes.getString(R.string.config_landscapeBatteryFillMaskE)
        fillMask.set(PathParser.createPathFromPathData(fillMaskString))
        // Set the fill rect so we can calculate the fill properly
        fillMask.computeBounds(fillRect, true)

        val fillOutlinePathString = modRes.getString(R.string.config_landscapeBatteryFillOutlineE)
        fillOutlinePath.set(PathParser.createPathFromPathData(fillOutlinePathString))
        fillOutlinePath.computeBounds(RectF(), true)

        val boltPathString = modRes.getString(R.string.config_landscapeBatteryBoltPathE)
        boltPath.set(PathParser.createPathFromPathData(boltPathString))

        val plusPathString = modRes.getString(R.string.config_landscapeBatteryPowersavePathE)
        plusPath.set(PathParser.createPathFromPathData(plusPathString))

        dualTone = false
    }

    companion object {
        private val TAG = LandscapeBatteryE::class.java.simpleName
        private const val WIDTH = 20f
        private const val HEIGHT = 13f
        private const val CRITICAL_LEVEL = 15

        // On a 20x13 grid, how wide to make the fill protection stroke.
        // Scales when our size changes
        private const val PROTECTION_STROKE_WIDTH = 2f

        // Arbitrarily chosen for visibility at small sizes
        private const val PROTECTION_MIN_STROKE_WIDTH = 5f
    }
}