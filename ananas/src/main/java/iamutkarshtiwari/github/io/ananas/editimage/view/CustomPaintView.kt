package iamutkarshtiwari.github.io.ananas.editimage.view

import amutkarshtiwari.github.io.ananas.MyParcelable
import amutkarshtiwari.github.io.ananas.MyPath
import amutkarshtiwari.github.io.ananas.PaintOptions
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.util.Util
import iamutkarshtiwari.github.io.ananas.R
import io.reactivex.subjects.BehaviorSubject
import java.util.*
import java.util.concurrent.ExecutionException


class CustomPaintView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val MIN_ERASER_WIDTH = 20f

    var mPaths = LinkedHashMap<MyPath, PaintOptions>()
    var mBackgroundBitmap: Bitmap? = null
    var mOldBackgroundBitmap: Bitmap? = null
    var mListener: CanvasListener? = null

    private var mLastPaths = LinkedHashMap<MyPath, PaintOptions>()
    private var mUndonePaths = LinkedHashMap<MyPath, PaintOptions>()
    private var mLastBackgroundBitmap: Bitmap? = null

    private val MIN_ZOOM = 0.8f
    private val MAX_ZOOM = 4.0f

    private var mPaint = Paint()
    private var mPath = MyPath()
    private var mPaintOptions = PaintOptions()

    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f

    private var mPosX = 0f
    private var mPosY = 0f
    private var mDrawX = 0f
    private var mDrawY = 0f
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f

    private var mActivePointerId = MotionEvent.INVALID_POINTER_ID

    private var mCurrBrushSize = 0f
    private var mAllowMovingZooming = true
    private var mIsEraserOn = false
    private var mWasMultitouch = false
    private var mBackgroundColor = 0
    private var mCenter: PointF? = null

    private var mScaleDetector: ScaleGestureDetector? = null
    private var mScaleFactor = 1.0f
    private var df = 0
    private var dt = 0
    private var bitmapRect = Rect()
    private var drawOutside = false
    private val subject = BehaviorSubject.create<Boolean>()

    init {

        mPaint.apply {
            color = mPaintOptions.color
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = mPaintOptions.strokeWidth
            isAntiAlias = true
        }

        mScaleDetector = ScaleGestureDetector(context, ScaleListener())

        subject.distinctUntilChanged().subscribe {
            mPath.moveTo(mCurX, mCurY)
        }

        pathsUpdated()
    }

    fun undo() {
        if (mPaths.isEmpty() && mLastPaths.isNotEmpty()) {
            mPaths = mLastPaths.clone() as LinkedHashMap<MyPath, PaintOptions>
            mBackgroundBitmap = mLastBackgroundBitmap
            mLastPaths.clear()
            pathsUpdated()
            invalidate()
            return
        }

        if (mPaths.isEmpty()) {
            return
        }

        val lastPath = mPaths.values.lastOrNull()
        val lastKey = mPaths.keys.lastOrNull()

        mPaths.remove(lastKey)
        if (lastPath != null && lastKey != null) {
            mUndonePaths[lastKey] = lastPath
            mListener?.toggleRedoVisibility(true)
        }
        pathsUpdated()
        invalidate()
    }

    fun redo() {
        if (mUndonePaths.keys.isEmpty()) {
            mListener?.toggleRedoVisibility(false)
            return
        }

        val lastKey = mUndonePaths.keys.last()
        addPath(lastKey, mUndonePaths.values.last())
        mUndonePaths.remove(lastKey)
        if (mUndonePaths.isEmpty()) {
            mListener?.toggleRedoVisibility(false)
        }
        invalidate()
    }

    fun toggleEraser(isEraserOn: Boolean) {
        mIsEraserOn = isEraserOn
        mPaintOptions.isEraser = isEraserOn
        invalidate()
    }

    fun setColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBackgroundColor(newColor: Int) {
        mBackgroundColor = newColor
        setBackgroundColor(newColor)
        mBackgroundBitmap = null
    }

    fun setBrushSize(newBrushSize: Float) {
        mCurrBrushSize = newBrushSize
    }

    fun setAllowZooming(allowZooming: Boolean) {
        mAllowMovingZooming = allowZooming
    }

    fun getBitmap(): Bitmap {
        reset()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.translate(mPosX, mPosY)
        canvas.scale(mScaleFactor, mScaleFactor, mCenter!!.x, mCenter!!.y)
        canvas.drawColor(Color.TRANSPARENT)

        draw(canvas)
        return bitmap

    }

    private fun reset() {
        mPosX = 0f
        mPosY = 0f
        mScaleFactor = 1.0f
    }

    fun drawBitmap(activity: Activity, path: Any) {
        ensureBackgroundThread {
            val size = Point()
            activity.windowManager.defaultDisplay.getSize(size)
            val options = RequestOptions()
                .format(DecodeFormat.PREFER_ARGB_8888)
                .disallowHardwareConfig()
                .fitCenter()

            try {
                val builder = Glide.with(context)
                    .asBitmap()
                    .load(path)
                    .apply(options)
                    .into(size.x, size.y)

                mBackgroundBitmap = builder.get()

                activity.runOnUiThread {
                    invalidate()
                }
            } catch (e: ExecutionException) {
                val errorMsg = String.format(activity.getString(R.string.failed_to_load_image), path)
            }
        }
    }

    fun addPath(path: MyPath, options: PaintOptions) {
        mPaths[path] = options
        pathsUpdated()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        bitmapRect = canvas.getClipBounds()
        if (mCenter == null) {
            mCenter = PointF(width / 2f, height / 2f)
        }

        canvas.translate(mPosX, mPosY)
        canvas.scale(mScaleFactor, mScaleFactor, mCenter!!.x, mCenter!!.y)

        if (mBackgroundBitmap != null) {
            val left = (width - mBackgroundBitmap!!.width) / 2
            val top = (height - mBackgroundBitmap!!.height) / 2
            df = left
            dt = top
            canvas.drawBitmap(mBackgroundBitmap!!, left.toFloat(), top.toFloat(), null)
        }

//        if ((mDrawY - dt) > 0.0 && mDrawY < (bitmapRect.bottom - bitmapRect.top) && ((mDrawX - df) > 0.0) && mDrawX < (bitmapRect.right - bitmapRect.left)) {
        for ((key, value) in mPaths) {
            changePaint(value)
            canvas.drawPath(key, mPaint)
        }

        println("+++++ key " + canvas.getClipBounds().bottom + " " + canvas.getClipBounds().top + " " + canvas.getClipBounds().left + " " + canvas.getClipBounds().right + " " + canvas.getClipBounds().height() + " " + canvas.getClipBounds().width() + " " + canvas.getClipBounds().centerX() + " " + canvas.getClipBounds().centerY() + " " + canvas.getClipBounds())

        changePaint(mPaintOptions)
        canvas.drawPath(mPath, mPaint)
        // }

        canvas.restore()
    }

    private fun changePaint(paintOptions: PaintOptions) {
        mPaint.color = if (paintOptions.isEraser) mBackgroundColor else paintOptions.color
        mPaint.strokeWidth = paintOptions.strokeWidth
        if (paintOptions.isEraser && mPaint.strokeWidth < MIN_ERASER_WIDTH) {
            mPaint.strokeWidth = MIN_ERASER_WIDTH
        }
    }

    fun clearCanvas() {
        mLastPaths = mPaths.clone() as LinkedHashMap<MyPath, PaintOptions>
        mLastBackgroundBitmap = mBackgroundBitmap
        mBackgroundBitmap = null
        mPath.reset()
        mPaths.clear()
        pathsUpdated()
        invalidate()
    }

    private fun actionDown(x: Float, y: Float) {
        mPath.reset()
        mPath.moveTo(x, y)
        mCurX = x
        mCurY = y
    }

    private fun actionMove(x: Float, y: Float) {
        mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)

        mCurX = x
        mCurY = y
    }

    private fun actionUp() {
        if (!mWasMultitouch) {
            mPath.lineTo(mCurX, mCurY)

            // draw a dot on click
            if (mStartX == mCurX && mStartY == mCurY) {
                mPath.lineTo(mCurX, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY)
            }
        }

        mPaths[mPath] = mPaintOptions
        pathsUpdated()
        mPath = MyPath()
        mPaintOptions = PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth, mPaintOptions.isEraser)
    }

    private fun pathsUpdated() {
        mListener?.toggleUndoVisibility(mPaths.isNotEmpty() || mLastPaths.isNotEmpty())
    }

    fun getDrawingHashCode() = mPaths.hashCode().toLong() + (mBackgroundBitmap?.hashCode()?.toLong()
        ?: 0L)

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (mAllowMovingZooming) {
            mScaleDetector!!.onTouchEvent(event)
        }

        val action = event.action and MotionEvent.ACTION_MASK
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            mActivePointerId = event.getPointerId(0)
        }

        val pointerIndex = event.findPointerIndex(mActivePointerId)
        val x: Float
        val y: Float

        try {
            x = event.getX(pointerIndex)
            y = event.getY(pointerIndex)
        } catch (e: Exception) {
            return true
        }

        var newValueX = x
        var newValueY = y

        if (mAllowMovingZooming) {
            val scaledWidth = width / mScaleFactor
            val touchPercentageX = x / width
            val compensationX = (scaledWidth / 2) * (1 - mScaleFactor)
            newValueX = scaledWidth * touchPercentageX - compensationX - (mPosX / mScaleFactor)

            val scaledHeight = height / mScaleFactor
            val touchPercentageY = y / height
            val compensationY = (scaledHeight / 2) * (1 - mScaleFactor)
            newValueY = scaledHeight * touchPercentageY - compensationY - (mPosY / mScaleFactor)
        }

        mDrawX = newValueX
        mDrawY = newValueY

        subject.onNext((newValueY - dt) > 0.0 && newValueY < (bitmapRect.bottom - bitmapRect.top) && ((newValueX - df) > 0.0) && newValueX < (bitmapRect.right - bitmapRect.left))

        println("+++++ mPosX " + mPosX + " mPosY " + mPosY + " mLastTouchX " + mLastTouchX + " mLastTouchY " + mLastTouchY + " mScaleFactor " + mScaleFactor + " newValueX  " + newValueX + " newValueY " + newValueY + " pointerIndex " + pointerIndex + " x " + x + " Y " + y + " mCurX " + mCurX + " mCurY " + mCurY)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = x
                mStartY = y

                if ((newValueY - dt) > 0.0 && newValueY < (bitmapRect.bottom - bitmapRect.top) && ((newValueX - df) > 0.0) && newValueX < (bitmapRect.right - bitmapRect.left)) {
                    drawOutside = false
                    actionDown(newValueX, newValueY)
                } else {
                    drawOutside = true
                }

                mUndonePaths.clear()
                mListener?.toggleRedoVisibility(false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mAllowMovingZooming || (!mScaleDetector!!.isInProgress && event.pointerCount == 1 && !mWasMultitouch)) {
                    if ((newValueY - dt) > 0.0 && newValueY < (bitmapRect.bottom - bitmapRect.top) && ((newValueX - df) > 0.0) && newValueX < (bitmapRect.right - bitmapRect.left)) {
                        drawOutside = false
                        actionMove(newValueX, newValueY)
                    } else {
                        drawOutside = true
                    }
                }

                if (mAllowMovingZooming && mWasMultitouch) {
                    mPosX += x - mLastTouchX
                    mPosY += y - mLastTouchY
                    invalidate()
                }

                mLastTouchX = x
                mLastTouchY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mActivePointerId = MotionEvent.INVALID_POINTER_ID
                if ((newValueY - dt) > 0.0 && newValueY < (bitmapRect.bottom - bitmapRect.top) && ((newValueX - df) > 0.0) && newValueX < (bitmapRect.right - bitmapRect.left)) {
                    drawOutside = false
                    actionUp()
                } else {
                    drawOutside = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
            MotionEvent.ACTION_POINTER_UP -> {
                val upPointerIndex = (event.action and MotionEvent.ACTION_POINTER_INDEX_MASK shr MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val pointerId = event.getPointerId(upPointerIndex)
                if (pointerId == mActivePointerId) {
                    val newPointerIndex = if (upPointerIndex == 0) 1 else 0

                    mLastTouchX = event.getX(newPointerIndex)
                    mLastTouchY = event.getY(newPointerIndex)

                    mActivePointerId = event.getPointerId(newPointerIndex)
                }
            }
        }

        invalidate()
        return true
    }

    public override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = MyParcelable(superState!!)
        savedState.paths = mPaths
        return savedState
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is MyParcelable) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)
        mPaths = state.paths
        pathsUpdated()
    }

    fun ensureBackgroundThread(callback: () -> Unit) {
        if (Util.isOnMainThread()) {
            Thread {
                callback()
            }.start()
        } else {
            callback()
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            mScaleFactor *= detector.scaleFactor
            mScaleFactor = Math.max(MIN_ZOOM, Math.min(mScaleFactor, MAX_ZOOM))
            setBrushSize(mCurrBrushSize)
            invalidate()
            return true
        }
    }

    interface CanvasListener {
        fun toggleUndoVisibility(visible: Boolean)

        fun toggleRedoVisibility(visible: Boolean)
    }

}
