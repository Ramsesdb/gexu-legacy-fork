package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A custom gesture detector that also implements an on long tap confirmed, because the built-in
 * one conflicts with the quick scale feature.
 */
open class GestureDetectorWithLongTap(
    context: Context,
    listener: Listener,
) : GestureDetector(context, listener) {

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastUp = 0L
    private var lastDownEvent: MotionEvent? = null

    /**
     * Tracks whether the current gesture moved beyond slop (is a scroll, not a tap).
     * Used to distinguish scroll-end from tap for double-tap prevention.
     */
    private var isScrolling = false

    /**
     * Runnable to execute when a long tap is confirmed.
     */
    private val longTapFn = Runnable { listener.onLongTapConfirmed(lastDownEvent!!) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownEvent?.recycle()
                lastDownEvent = MotionEvent.obtain(ev)
                isScrolling = false

                // This is the key difference with the built-in detector. We have to ignore the
                // event if the last up and current down are too close in time (double tap).
                if (ev.downTime - lastUp > doubleTapTime) {
                    downX = ev.x
                    downY = ev.y
                    handler.postDelayed(longTapFn, longTapTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    handler.removeCallbacks(longTapFn)
                    isScrolling = true
                }
            }
            MotionEvent.ACTION_UP -> {
                // Only update lastUp for tap gestures, not for scroll-end events.
                // This prevents long-press from failing after scrolling when the user
                // tries to long-press again within doubleTapTime of the scroll ending.
                if (!isScrolling) {
                    lastUp = ev.eventTime
                }
                handler.removeCallbacks(longTapFn)
                isScrolling = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longTapFn)
                isScrolling = false
            }
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Custom listener to also include a long tap confirmed
     */
    open class Listener : SimpleOnGestureListener() {
        /**
         * Notified when a long tap occurs with the initial on down [ev] that triggered it.
         */
        open fun onLongTapConfirmed(ev: MotionEvent) {
        }
    }
}
