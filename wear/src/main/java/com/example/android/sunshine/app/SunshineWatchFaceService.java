package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    @Override
    public Engine onCreateEngine() {
        return new WatchFaceEngine();
    }

    private class WatchFaceEngine extends Engine {

        //Member variables
        private Typeface WATCH_TEXT_TYPEFACE = Typeface.create(Typeface.SERIF, Typeface.NORMAL);

        private static final int MSG_UPDATE_TIME_ID = 42;
        private static final long DEFAULT_UPDATE_RATE_MS = 1000;
        private long mUpdateRateMs = 1000;

        private Time mDisplayTime;

        private Paint mBackgroundColorPaint;
        private Paint mTextColorPaint;

        private boolean mHasTimeZoneReceiverBeenRegistered = false;
        private boolean mIsInMuteMode;
        private boolean mIsLowBitAmbient;

        private float mXOffset;
        private float mYOffset;

        private int mBackgroundColor = Color.parseColor("black");
        private int mBackgroundInteractiveColor = Color.parseColor("#0288D1");
        private int mTextColor = Color.parseColor("red");

        final BroadcastReceiver mTimeZoneBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mDisplayTime.clear(intent.getStringExtra("time-zone"));
                mDisplayTime.setToNow();
            }
        };

        private final Handler mTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME_ID: {
                        invalidate();
                        if (isVisible() && !isInAmbientMode()) {
                            long currentTimeMillis = System.currentTimeMillis();
                            long delay = mUpdateRateMs - (currentTimeMillis % mUpdateRateMs);
                            mTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME_ID, delay);
                        }
                        break;
                    }
                }
            }
        };

        //Overridden methods
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setShowSystemUiTime(false)
                    .build()
            );

            initBackground();
            initDisplayText();

            mDisplayTime = new Time();
        }

        @Override
        public void onDestroy() {
            mTimeHandler.removeMessages(MSG_UPDATE_TIME_ID);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                if (!mHasTimeZoneReceiverBeenRegistered) {

                    IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
                    SunshineWatchFaceService.this.registerReceiver(mTimeZoneBroadcastReceiver, filter);

                    mHasTimeZoneReceiverBeenRegistered = true;
                }

                mDisplayTime.clear(TimeZone.getDefault().getID());
                mDisplayTime.setToNow();
            } else {
                if (mHasTimeZoneReceiverBeenRegistered) {
                    SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneBroadcastReceiver);
                    mHasTimeZoneReceiverBeenRegistered = false;
                }
            }

            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            mYOffset = getResources().getDimension(R.dimen.digital_y_offset);

            if (insets.isRound()) {
                mXOffset = getResources().getDimension(R.dimen.digital_x_offset_round);
            } else {
                mXOffset = getResources().getDimension(R.dimen.digital_x_offset);
            }
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            if (properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false)) {
                mIsLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();

            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (inAmbientMode) {
                mTextColorPaint.setColor(Color.parseColor("white"));
                mBackgroundColorPaint.setColor(mBackgroundColor);
            } else {
                mTextColorPaint.setColor(Color.parseColor("white"));
                mBackgroundColorPaint.setColor(mBackgroundInteractiveColor);
            }

            if (mIsLowBitAmbient) {
                mTextColorPaint.setAntiAlias(!inAmbientMode);
            }

            invalidate();
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean isDeviceMuted = (interruptionFilter == android.support.wearable.watchface.WatchFaceService.INTERRUPTION_FILTER_NONE);

            if (isDeviceMuted) {
                mUpdateRateMs = TimeUnit.MINUTES.toMillis(1);
            } else {
                mUpdateRateMs = DEFAULT_UPDATE_RATE_MS;
            }

            if (mIsInMuteMode != isDeviceMuted) {
                mIsInMuteMode = isDeviceMuted;
                int alpha = (isDeviceMuted) ? 100 : 255;
                mTextColorPaint.setAlpha(alpha);
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            super.onDraw(canvas, bounds);

            mDisplayTime.setToNow();

            drawBackground(canvas, bounds);
            drawTimeText(canvas);
            drawDate(canvas);
        }

        private void initBackground() {
            mBackgroundColorPaint = new Paint();
            mBackgroundColorPaint.setColor(mBackgroundColor);
        }

        private void initDisplayText() {
            mTextColorPaint = new Paint();
            mTextColorPaint.setColor(mTextColor);
            mTextColorPaint.setTypeface(WATCH_TEXT_TYPEFACE);
            mTextColorPaint.setAntiAlias(true);
            mTextColorPaint.setTextSize(getResources().getDimension(R.dimen.digital_text_size));
        }

        private void updateTimer() {
            mTimeHandler.removeMessages(MSG_UPDATE_TIME_ID);
            if (isVisible() && !isInAmbientMode()) {
                mTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME_ID);
            }
        }

        private void drawBackground(Canvas canvas, Rect bounds) {
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundColorPaint);
        }

        private void drawTimeText(Canvas canvas) {
            String timeText = getHourString() + ":" + String.format("%02d", mDisplayTime.minute);
            if (isInAmbientMode() || mIsInMuteMode) {
                timeText += (mDisplayTime.hour < 12) ? "AM" : "PM";
            } else {
                timeText += String.format(":%02d", mDisplayTime.second);
            }
            canvas.drawText(timeText, mXOffset, mYOffset, mTextColorPaint);
        }

        private String getHourString() {
            if (mDisplayTime.hour % 12 == 0)
                return "12";
            else if (mDisplayTime.hour <= 12)
                return String.valueOf(mDisplayTime.hour);
            else
                return String.valueOf(mDisplayTime.hour - 12);
        }

        private void drawDate(Canvas canvas) {
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy");
            String strDate = sdf.format(calendar.getTime());
            mTextColorPaint.setTextSize(20.0f);
            canvas.drawText(strDate, mXOffset, mYOffset + 40.0f, mTextColorPaint);
        }
    }
}
