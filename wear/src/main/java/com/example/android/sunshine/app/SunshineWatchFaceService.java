package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    public static final String LOG_TAG = SunshineWatchFaceService.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new WatchFaceEngine();
    }

    private class WatchFaceEngine extends Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private GoogleApiClient googleApiClient;

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

        private int mBackgroundColorAmbient = Color.parseColor("black");
        private int mBackgroundInteractiveColor = Color.parseColor("#0288D1");
        private int mTextColor = Color.parseColor("white");

        private static final String PATH = "/simple_watch_face";
        private static final String KEY_HIGH_TEMP = "HIGH_TEMP";
        private static final String KEY_LOW_TEMP = "LOW_TEMP";
        private static final String KEY_WEATHER_ID = "WEATHER_ID";

        private String highTemp;
        private String lowTemp;
        private int weatherIcon = -1;

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

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            initBackground();
            initDisplayText();

            mDisplayTime = new Time();
        }

        @Override
        public void onDestroy() {
            mTimeHandler.removeMessages(MSG_UPDATE_TIME_ID);
            releaseGoogleApiClient();
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
                googleApiClient.connect();
            } else {
                if (mHasTimeZoneReceiverBeenRegistered) {
                    SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneBroadcastReceiver);
                    mHasTimeZoneReceiverBeenRegistered = false;
                }
                releaseGoogleApiClient();
            }
            updateTimer();
        }

        private void releaseGoogleApiClient() {
            Log.d(LOG_TAG, "releaseGoogleApiClient");
            if (googleApiClient != null && googleApiClient.isConnected()) {
                Log.d(LOG_TAG, "releaseGoogleApiClient disconnect");
                googleApiClient.disconnect();
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d(LOG_TAG, "connected GoogleAPI");
            Wearable.DataApi.addListener(googleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(onConnectedResultCallback);
        }


        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.d(LOG_TAG, "onDataChanged");
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        DataItem item = event.getDataItem();
                        processConfigurationFor(item);
                    }
                }
                dataEvents.release();
                invalidateIfNecessary();
            }
        };

        private void processConfigurationFor(DataItem item) {
            Log.d(LOG_TAG, "processConfigurationFor");
            if (PATH.equals(item.getUri().getPath())) {
                Log.d(LOG_TAG, "processConfigurationFor path matched");
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey(KEY_HIGH_TEMP)) {
                    highTemp = dataMap.getString(KEY_HIGH_TEMP);
                    Log.d(LOG_TAG, "high temp is " + highTemp);
                }

                if (dataMap.containsKey(KEY_LOW_TEMP)) {
                    lowTemp = dataMap.getString(KEY_LOW_TEMP);
                    Log.d(LOG_TAG, "low temp is " + lowTemp);
                }

                if (dataMap.containsKey(KEY_WEATHER_ID)) {
                    weatherIcon = getArtResourceForWeatherCondition(
                            dataMap.getInt(KEY_WEATHER_ID));
                    Log.d(LOG_TAG, "weather id is " + weatherIcon);
                }
            }
        }

        /**
         * Helper method to provide the art resource id according to the weather condition id returned
         * by the OpenWeatherMap call.
         *
         * @param weatherId from OpenWeatherMap API response
         * @return resource id for the corresponding image. -1 if no relation is found.
         */
        public int getArtResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_rain;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }

        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                Log.d(LOG_TAG, "onResult");
                for (DataItem item : dataItems) {
                    Log.d(LOG_TAG, "onResult 229");
                    processConfigurationFor(item);
                }

                dataItems.release();
                invalidateIfNecessary();
            }
        };

        private void invalidateIfNecessary() {
            if (isVisible() && !isInAmbientMode()) {
                invalidate();
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(LOG_TAG, "suspended GoogleAPI");
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.e(LOG_TAG, "connectionFailed GoogleAPI");
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            mYOffset = getResources().getDimension(R.dimen.digital_y_offset);
            mXOffset = insets.isRound() ? getResources().getDimension(R.dimen.digital_x_offset_round) :
                    getResources().getDimension(R.dimen.digital_x_offset);
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
//                mTextColorPaint.setColor(mTextColor);
                mBackgroundColorPaint.setColor(mBackgroundColorAmbient);
            } else {
//                mTextColorPaint.setColor(mTextColor);
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
            if (weatherIcon != -1)
                drawWeatherIcon(canvas);
            if (!TextUtils.isEmpty(highTemp))
                drawHighTemp(canvas);
            if (!TextUtils.isEmpty(lowTemp))
                drawLowTemp(canvas);
        }

        private void initBackground() {
            mBackgroundColorPaint = new Paint();
            mBackgroundColorPaint.setColor(mBackgroundInteractiveColor);
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
//            if (isInAmbientMode() || mIsInMuteMode) {
//                timeText += (mDisplayTime.hour < 12) ? "AM" : "PM";
//            } else {
//                timeText += String.format(":%02d", mDisplayTime.second);
//            }
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
//            mTextColorPaint.setTextSize(20.0f);
            canvas.drawText(strDate, mXOffset, mYOffset + 40.0f, mTextColorPaint);
        }

        private void drawHighTemp(Canvas canvas) {
//            mTextColorPaint.setTextSize(20.0f);
            canvas.drawText(highTemp, mXOffset + 20.0f, mYOffset + 60.0f, mTextColorPaint);
        }

        private void drawLowTemp(Canvas canvas) {
//            mTextColorPaint.setTextSize(20.0f);
            canvas.drawText(lowTemp, mXOffset + 40.0f, mYOffset + 60.0f, mTextColorPaint);
        }

        private void drawWeatherIcon(Canvas canvas) {
            Bitmap origBitmap = BitmapFactory.decodeResource(getResources(), weatherIcon);
            float scale = ((float) 50) / (float) origBitmap.getWidth();
            Bitmap scaledBitmap = Bitmap.createScaledBitmap
                    (origBitmap, (int) (origBitmap.getWidth() * scale),
                            (int) (origBitmap.getHeight() * scale), true);
            canvas.drawBitmap(scaledBitmap, mXOffset, mYOffset + 60.0f, null);
        }
    }
}
