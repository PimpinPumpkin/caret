/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

class RoundedLine {
    private val arc1 = RectF()
    private val arc2 = RectF()
    private val path = Path()

    /**
     * Make a rounded line path
     *
     * @param p1x the x-coordinate of the start point.
     * @param p1y the y-coordinate of the start point.
     * @param r1 the radius at the start point
     * @param p2x the x-coordinate of the end point.
     * @param p2y the y-coordinate of the end point.
     * @param r2 the radius at the end point
     * @return an instance of {@link Path} that holds the result rounded line, or an instance of
     * {@link Path} that holds an empty path if the start and end points are equal.
     */
    fun makePath(p1x: Float, p1y: Float, r1: Float,
            p2x: Float, p2y: Float, r2: Float): Path {
        path.rewind()
        val dx = (p2x - p1x).toDouble()
        val dy = (p2y - p1y).toDouble()
        // Distance of the points.
        val l = Math.hypot(dx, dy)
        if (java.lang.Double.compare(0.0, l) == 0) {
            return path // Return an empty path
        }
        // Angle of the line p1-p2
        val a = Math.atan2(dy, dx)
        // Difference of trail cap radius.
        val dr = (r2 - r1).toDouble()
        // Variation of angle at trail cap.
        val ar = Math.asin(dr / l)
        // The start angle of trail cap arc at P1.
        val aa = a - (RIGHT_ANGLE + ar)
        // The end angle of trail cap arc at P2.
        val ab = a + (RIGHT_ANGLE + ar)
        val cosa = Math.cos(aa).toFloat()
        val sina = Math.sin(aa).toFloat()
        val cosb = Math.cos(ab).toFloat()
        val sinb = Math.sin(ab).toFloat()
        // Closing point of arc at P1.
        val p1ax = p1x + r1 * cosa
        val p1ay = p1y + r1 * sina
        // Opening point of arc at P1.
        val p1bx = p1x + r1 * cosb
        val p1by = p1y + r1 * sinb
        // Opening point of arc at P2.
        val p2ax = p2x + r2 * cosa
        val p2ay = p2y + r2 * sina
        // Closing point of arc at P2.
        val p2bx = p2x + r2 * cosb
        val p2by = p2y + r2 * sinb
        // Start angle of the trail arcs.
        val angle = (aa * RADIAN_TO_DEGREE).toFloat()
        val ar2degree = (ar * 2.0 * RADIAN_TO_DEGREE).toFloat()
        // Sweep angle of the trail arc at P1.
        val a1 = -180.0f + ar2degree
        // Sweep angle of the trail arc at P2.
        val a2 = 180.0f + ar2degree
        arc1.set(p1x, p1y, p1x, p1y)
        arc1.inset(-r1, -r1)
        arc2.set(p2x, p2y, p2x, p2y)
        arc2.inset(-r2, -r2)

        // Trail cap at P1.
        path.moveTo(p1x, p1y)
        path.arcTo(arc1, angle, a1)
        // Trail cap at P2.
        path.moveTo(p2x, p2y)
        path.arcTo(arc2, angle, a2)
        // Two trapezoids connecting P1 and P2.
        path.moveTo(p1ax, p1ay)
        path.lineTo(p1x, p1y)
        path.lineTo(p1bx, p1by)
        path.lineTo(p2bx, p2by)
        path.lineTo(p2x, p2y)
        path.lineTo(p2ax, p2ay)
        path.close()
        return path
    }

    fun getBounds(outBounds: Rect) {
        // Reuse arc1 as working variable
        path.computeBounds(arc1, true /* unused */)
        arc1.roundOut(outBounds)
    }

    companion object {
        private const val RADIAN_TO_DEGREE = 180.0 / Math.PI
        private const val RIGHT_ANGLE = Math.PI / 2.0
    }
}
