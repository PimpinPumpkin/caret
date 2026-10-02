/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

/**
 * Interpolates XY-coordinates using Cubic Hermite Curve.
 */
class HermiteInterpolator {
    private lateinit var mXCoords: IntArray
    private lateinit var mYCoords: IntArray
    private var mMinPos = 0
    private var mMaxPos = 0

    // Working variable to calculate interpolated value.
    /** The coordinates of the start point of the interval. */
    @JvmField var p1X = 0
    @JvmField var p1Y = 0
    /** The coordinates of the end point of the interval. */
    @JvmField var p2X = 0
    @JvmField var p2Y = 0
    /** The slope of the tangent at the start point. */
    @JvmField var slope1X = 0f
    @JvmField var slope1Y = 0f
    /** The slope of the tangent at the end point. */
    @JvmField var slope2X = 0f
    @JvmField var slope2Y = 0f
    /** The interpolated coordinates.
     * The return variables of {@link #interpolate(float)} to avoid instantiations.
     */
    @JvmField var interpolatedX = 0f
    @JvmField var interpolatedY = 0f

    constructor() {
        // Nothing to do with here.
    }

    /**
     * Reset this interpolator to point XY-coordinates data.
     * @param xCoords the array of x-coordinates. Valid data are in left-open interval
     *                <code>[minPos, maxPos)</code>.
     * @param yCoords the array of y-coordinates. Valid data are in left-open interval
     *                <code>[minPos, maxPos)</code>.
     * @param minPos the minimum index of left-open interval of valid data.
     * @param maxPos the maximum index of left-open interval of valid data.
     */
    fun reset(xCoords: IntArray, yCoords: IntArray, minPos: Int,
            maxPos: Int) {
        mXCoords = xCoords
        mYCoords = yCoords
        mMinPos = minPos
        mMaxPos = maxPos
    }

    /**
     * Set interpolation interval.
     * <p>
     * The start and end coordinates of the interval will be set in {@link #p1X}, {@link #p1Y},
     * {@link #p2X}, and {@link #p2Y}. The slope of the tangents at start and end points will be
     * set in {@link #slope1X}, {@link #slope1Y}, {@link #slope2X}, and {@link #slope2Y}.
     *
     * @param p0 the index just before interpolation interval. If <code>p1</code> points the start
     *           of valid points, <code>p0</code> must be less than <code>minPos</code> of
     *           {@link #reset(int[],int[],int,int)}.
     * @param p1 the start index of interpolation interval.
     * @param p2 the end index of interpolation interval.
     * @param p3 the index just after interpolation interval. If <code>p2</code> points the end of
     *           valid points, <code>p3</code> must be equal or greater than <code>maxPos</code> of
     *           {@link #reset(int[],int[],int,int)}.
     */
    fun setInterval(p0: Int, p1: Int, p2: Int, p3: Int) {
        p1X = mXCoords[p1]
        p1Y = mYCoords[p1]
        p2X = mXCoords[p2]
        p2Y = mYCoords[p2]
        // A(ax,ay) is the vector p1->p2.
        val ax = p2X - p1X
        val ay = p2Y - p1Y

        // Calculate the slope of the tangent at p1.
        if (p0 >= mMinPos) {
            // p1 has previous valid point p0.
            // The slope of the tangent is half of the vector p0->p2.
            slope1X = (p2X - mXCoords[p0]) / 2.0f
            slope1Y = (p2Y - mYCoords[p0]) / 2.0f
        } else if (p3 < mMaxPos) {
            // p1 has no previous valid point, but p2 has next valid point p3.
            // B(bx,by) is the slope vector of the tangent at p2.
            val bx = (mXCoords[p3] - p1X) / 2.0f
            val by = (mYCoords[p3] - p1Y) / 2.0f
            val crossProdAB = ax * by - ay * bx
            val dotProdAB = ax * bx + ay * by
            val normASquare = (ax * ax + ay * ay).toFloat()
            val invHalfNormASquare = 1.0f / normASquare / 2.0f
            // The slope of the tangent is the mirror image of vector B to vector A.
            slope1X = invHalfNormASquare * (dotProdAB * ax + crossProdAB * ay)
            slope1Y = invHalfNormASquare * (dotProdAB * ay - crossProdAB * ax)
        } else {
            // p1 and p2 have no previous valid point. (Interval has only point p1 and p2)
            slope1X = ax.toFloat()
            slope1Y = ay.toFloat()
        }

        // Calculate the slope of the tangent at p2.
        if (p3 < mMaxPos) {
            // p2 has next valid point p3.
            // The slope of the tangent is half of the vector p1->p3.
            slope2X = (mXCoords[p3] - p1X) / 2.0f
            slope2Y = (mYCoords[p3] - p1Y) / 2.0f
        } else if (p0 >= mMinPos) {
            // p2 has no next valid point, but p1 has previous valid point p0.
            // B(bx,by) is the slope vector of the tangent at p1.
            val bx = (p2X - mXCoords[p0]) / 2.0f
            val by = (p2Y - mYCoords[p0]) / 2.0f
            val crossProdAB = ax * by - ay * bx
            val dotProdAB = ax * bx + ay * by
            val normASquare = (ax * ax + ay * ay).toFloat()
            val invHalfNormASquare = 1.0f / normASquare / 2.0f
            // The slope of the tangent is the mirror image of vector B to vector A.
            slope2X = invHalfNormASquare * (dotProdAB * ax + crossProdAB * ay)
            slope2Y = invHalfNormASquare * (dotProdAB * ay - crossProdAB * ax)
        } else {
            // p1 and p2 has no previous valid point. (Interval has only point p1 and p2)
            slope2X = ax.toFloat()
            slope2Y = ay.toFloat()
        }
    }

    /**
     * Calculate interpolation value at <code>t</code> in unit interval <code>[0,1]</code>.
     * <p>
     * On the unit interval [0,1], given a starting point p1 at t=0 and an ending point p2 at t=1
     * with the slope of the tangent m1 at p1 and m2 at p2, the polynomial of cubic Hermite curve
     * can be defined by
     *   p(t) = (1+2t)(1-t)(1-t)*p1 + t(1-t)(1-t)*m1 + (3-2t)t^2*p2 + (t-1)t^2*m2
     * where t is an element of [0,1].
     * <p>
     * The interpolated XY-coordinates will be set in {@link #interpolatedX} and
     * {@link #interpolatedY}.
     *
     * @param t the interpolation parameter. The value must be in close interval <code>[0,1]</code>.
     */
    fun interpolate(t: Float) {
        val omt = 1.0f - t
        val tm2 = 2.0f * t
        val k1 = 1.0f + tm2
        val k2 = 3.0f - tm2
        val omt2 = omt * omt
        val t2 = t * t
        interpolatedX = (k1 * p1X + t * slope1X) * omt2 + (k2 * p2X - omt * slope2X) * t2
        interpolatedY = (k1 * p1Y + t * slope1Y) * omt2 + (k2 * p2Y - omt * slope2Y) * t2
    }
}
