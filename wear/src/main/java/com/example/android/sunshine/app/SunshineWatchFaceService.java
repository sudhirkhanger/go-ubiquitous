package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.DateFormat;
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

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    //Hardcoding tag to bypass 23 char long log tag restriction.
    public static final String LOG_TAG = "SunshineWatchFaceServ";

    public static final String KEY_TIMEZONE = "time-zone";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new WatchFaceEngine();
    }

    private class WatchFaceEngine extends Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final String COLON_STRING = ":";

        private GoogleApiClient googleApiClient;

        private static final int MSG_UPDATE_TIME_ID = 42;
        private static final long DEFAULT_UPDATE_RATE_MS = 1000;
        private long mUpdateRateMs = 1000;

        private Time mDisplayTime;

        private Paint backgroundPaint;
        private Paint hourPaint;
        private Paint minutePaint;
        private Paint colonPaint;
        private float colonWidth;
        private Paint datePaint;
        private Paint highTempPaint;
        private Paint lowTempPaint;
//        private Paint mTextColorPaint;

        Calendar calendar;
        Date date;

        private boolean mHasTimeZoneReceiverBeenRegistered = false;
        private boolean mute;
        private boolean mIsLowBitAmbient;

        private float mXOffset;
        private float mYOffset;

        private int backgroundColorAmbient;
        private int backgroundColorInteractive;
        private int textColor;

        private static final String PATH = "/simple_watch_face";
        private static final String KEY_HIGH_TEMP = "HIGH_TEMP";
        private static final String KEY_LOW_TEMP = "LOW_TEMP";
        private static final String KEY_WEATHER_ID = "WEATHER_ID";

        private String highTemp;
        private String lowTemp;
        private int weatherIconRes = -1;

        private boolean lowBitAmbient;

        private static final int MUTE_ALPHA = 100;

        private static final int NORMAL_ALPHA = 255;

        final BroadcastReceiver mTimeZoneBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                mDisplayTime.clear(intent.getStringExtra(KEY_TIMEZONE));
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
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setShowSystemUiTime(false)
                    .build()
            );

            Resources resources = SunshineWatchFaceService.this.getResources();
            Context context = SunshineWatchFaceService.this;

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            backgroundColorAmbient = ContextCompat.getColor(context, R.color.black);
            backgroundColorInteractive = ContextCompat.getColor(context, R.color.light_blue);
            textColor = ContextCompat.getColor(context, R.color.white);

            //Set background color
            backgroundPaint = new Paint();
            backgroundPaint.setColor(backgroundColorInteractive);

            hourPaint = createTextPaint(textColor, BOLD_TYPEFACE);
            minutePaint = createTextPaint(textColor);
            colonPaint = createTextPaint(textColor);
            datePaint = createTextPaint(textColor);
            highTempPaint = createTextPaint(textColor);
            lowTempPaint = createTextPaint(textColor);

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

//            initDisplayText();

            mDisplayTime = new Time();

            calendar = Calendar.getInstance();
            date = new Date();
        }

        @Override
        public void onDestroy() {
            mTimeHandler.removeMessages(MSG_UPDATE_TIME_ID);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
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
                    int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                    weatherIconRes = getArtResourceForWeatherCondition(
                            weatherId);
                    Log.d(LOG_TAG, "weather id is " + weatherId);
                }
            }
        }

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
                return R.drawable.art_snow;
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

            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float itemTextSize = resources.getDimension(R.dimen.digital_item_text_size);

            hourPaint.setTextSize(timeTextSize);
            minutePaint.setTextSize(timeTextSize);
            colonPaint.setTextSize(timeTextSize);
            colonWidth = colonPaint.measureText(COLON_STRING);
            datePaint.setTextSize(itemTextSize);
            highTempPaint.setTextSize(itemTextSize);
            lowTempPaint.setTextSize(itemTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            hourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + lowBitAmbient);
            }

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

            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onAmbientModeChanged: " + inAmbientMode);
            }

            hourPaint.setColor(textColor);
            minutePaint.setColor(textColor);
            datePaint.setColor(textColor);
            highTempPaint.setColor(textColor);
            lowTempPaint.setColor(textColor);
            backgroundPaint.setColor(inAmbientMode
                    ? backgroundColorAmbient : backgroundColorInteractive);

            if (mIsLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                hourPaint.setAntiAlias(antiAlias);
                minutePaint.setAntiAlias(antiAlias);
                datePaint.setAntiAlias(antiAlias);
                colonPaint.setAntiAlias(antiAlias);
                highTempPaint.setAntiAlias(antiAlias);
                lowTempPaint.setAntiAlias(antiAlias);
            }

            invalidate();
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;

            mUpdateRateMs = inMuteMode ? TimeUnit.MINUTES.toMillis(1) : DEFAULT_UPDATE_RATE_MS;

            if (mute != inMuteMode) {
                mute = inMuteMode;
                int alpha = (inMuteMode) ? MUTE_ALPHA : NORMAL_ALPHA;
                hourPaint.setAlpha(alpha);
                minutePaint.setAlpha(alpha);
                datePaint.setAlpha(alpha);
                colonPaint.setAlpha(alpha);
                highTempPaint.setAlpha(alpha);
                lowTempPaint.setAlpha(alpha);
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            super.onDraw(canvas, bounds);

            long now = System.currentTimeMillis();
            calendar.setTimeInMillis(now);
            date.setTime(now);

            mDisplayTime.setToNow();

            canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);

            draw(canvas);
        }

        private void updateTimer() {
            mTimeHandler.removeMessages(MSG_UPDATE_TIME_ID);
            if (isVisible() && !isInAmbientMode()) {
                mTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME_ID);
            }
        }

        private void draw(Canvas canvas) {
            float x = mXOffset;
            float y = mYOffset;

            String hourString = formatTwoDigitNumber(calendar.get(Calendar.HOUR_OF_DAY));
            canvas.drawText(hourString, x, mYOffset, hourPaint);
            x += hourPaint.measureText(hourString);

            canvas.drawText(COLON_STRING, x, mYOffset, colonPaint);
            x += colonWidth;

            String minuteString = formatTwoDigitNumber(calendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, minutePaint);

            String date = String.valueOf(DateFormat.format("EEE, MMM d yyyy", calendar.getTime()));

            Rect timeBounds = new Rect();
            hourPaint.getTextBounds(hourString, 0, hourString.length(), timeBounds);
            y += timeBounds.height();
            x = mXOffset;
            canvas.drawText(date, x, y, datePaint);

            Rect dateBound = new Rect();
            datePaint.getTextBounds(date, 0, date.length(), dateBound);
            y += dateBound.height();
            float scaleFactor = getResources().getDimension(R.dimen.digital_item_text_size);

            if (weatherIconRes != -1) {
                Log.d(LOG_TAG, "weather icon update");
                Bitmap origBitmap = BitmapFactory.decodeResource(getResources(), weatherIconRes);
                float scale = scaleFactor / (float) origBitmap.getHeight();
                Bitmap scaledBitmap = Bitmap.createScaledBitmap
                        (origBitmap, (int) (origBitmap.getWidth() * scale),
                                (int) (origBitmap.getHeight() * scale), true);
                canvas.drawBitmap(scaledBitmap, mXOffset, y, null);
            }

            x = mXOffset + getResources().getDimension(R.dimen.digital_item_text_size);
            y += getResources().getDimension(R.dimen.digital_item_text_size);

            if (!TextUtils.isEmpty(highTemp)) {
                canvas.drawText(highTemp, x, y, highTempPaint);
                x += highTempPaint.measureText(highTemp);
            }

            if (!TextUtils.isEmpty(lowTemp)) {
                canvas.drawText(lowTemp, x, y, lowTempPaint);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }
    }
}
