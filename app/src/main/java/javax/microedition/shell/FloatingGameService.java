/*
 * FloatingGameService.java
 * app/src/main/java/javax/microedition/shell/FloatingGameService.java
 */

package javax.microedition.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class FloatingGameService extends Service {

    public static final String ACTION_SHOW = "floating.show";
    public static final String ACTION_HIDE = "floating.hide";
    public static final String ACTION_STOP = "floating.stop";
    public static final String EXTRA_APP_NAME = "app_name";

    private static final String CHANNEL_ID = "floating_game_channel";
    private static final int NOTIF_ID = 1001;
    private static final int FPS = 30;

    public static boolean isRunning = false;
    public static FloatingCallback callback;

    private WindowManager windowManager;
    private View floatingView;
    private View bubbleView;
    private ImageView mirrorView;

    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams bubbleParams;

    private boolean isMinimized = false;
    private String appName = "J2ME Game";

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    private final Handler mirrorHandler = new Handler(Looper.getMainLooper());
    private final Runnable mirrorRunnable = new Runnable() {
        @Override
        public void run() {
            if (callback != null && mirrorView != null && !isMinimized) {
                Bitmap bmp = callback.getGameBitmap();
                if (bmp != null) {
                    mirrorView.setImageBitmap(bmp);
                }
            }
            if (!isMinimized && isRunning) {
                mirrorHandler.postDelayed(this, 1000 / FPS);
            }
        }
    };

    public interface FloatingCallback {
        // Ambil bitmap frame game saat ini
        Bitmap getGameBitmap();
        // Kembalikan ke fullscreen
        void onRestoreToFullscreen();
        // Teruskan touch ke game
        void dispatchTouchToGame(MotionEvent event);
        // Ukuran game asli untuk scaling touch
        int getGameWidth();
        int getGameHeight();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        isRunning = true;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (action == null) return START_NOT_STICKY;

        appName = intent.getStringExtra(EXTRA_APP_NAME) != null
                ? intent.getStringExtra(EXTRA_APP_NAME) : "J2ME Game";

        switch (action) {
            case ACTION_SHOW:
                startForeground(NOTIF_ID, buildNotification());
                showFloatingWindow();
                break;
            case ACTION_HIDE:
                minimizeToBubble();
                break;
            case ACTION_STOP:
                stopFloating();
                break;
        }
        return START_STICKY;
    }

    private void showFloatingWindow() {
        if (floatingView != null) {
            floatingView.setVisibility(View.VISIBLE);
            if (bubbleView != null) {
                windowManager.removeView(bubbleView);
                bubbleView = null;
            }
            isMinimized = false;
            startMirror();
            return;
        }

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int width = (int) (dm.widthPixels * 0.70f);
        final int height = (int) (dm.heightPixels * 0.65f);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF111111);

        // Mirror view — tampilkan bitmap game
        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_XY);
        container.addView(mirrorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Touch input — scale koordinat ke ukuran game asli
        mirrorView.setOnTouchListener((v, event) -> {
            if (callback == null) return false;
            int gameW = callback.getGameWidth();
            int gameH = callback.getGameHeight();
            if (gameW <= 0 || gameH <= 0) return false;

            float scaleX = (float) gameW / width;
            float scaleY = (float) gameH / height;

            MotionEvent scaled = MotionEvent.obtain(event);
            scaled.setLocation(event.getX() * scaleX, event.getY() * scaleY);
            callback.dispatchTouchToGame(scaled);
            scaled.recycle();
            return true;
        });

        // Control bar
        float density = dm.density;
        int barHeight = (int) (40 * density);
        View controlBar = buildControlBar(density);
        container.addView(controlBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP));

        floatingView = container;

        floatingParams = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = 50;
        floatingParams.y = 100;

        windowManager.addView(floatingView, floatingParams);
        isMinimized = false;
        setupDrag(controlBar);
        startMirror();
    }

    private View buildControlBar(float density) {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xDD1A1A1A);

        int btnSize = (int) (32 * density);
        int margin = (int) (6 * density);

        // Tombol minimize (⏸)
        ImageButton btnMin = makeButton(android.R.drawable.ic_media_pause, "Minimize");
        FrameLayout.LayoutParams lpMin = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpMin.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpMin.rightMargin = (btnSize + margin) * 2 + margin;
        btnMin.setLayoutParams(lpMin);
        btnMin.setOnClickListener(v -> minimizeToBubble());
        bar.addView(btnMin);

        // Tombol fullscreen (⊕)
        ImageButton btnFull = makeButton(android.R.drawable.ic_menu_zoom, "Fullscreen");
        FrameLayout.LayoutParams lpFull = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpFull.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpFull.rightMargin = (btnSize + margin) + margin;
        btnFull.setLayoutParams(lpFull);
        btnFull.setOnClickListener(v -> restoreToFullscreen());
        bar.addView(btnFull);

        // Tombol close (✕)
        ImageButton btnClose = makeButton(android.R.drawable.ic_menu_close_clear_cancel, "Close");
        FrameLayout.LayoutParams lpClose = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpClose.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpClose.rightMargin = margin;
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(v -> stopFloating());
        bar.addView(btnClose);

        return bar;
    }

    private ImageButton makeButton(int iconRes, String desc) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(iconRes);
        btn.setBackgroundColor(0x00000000);
        btn.setContentDescription(desc);
        return btn;
    }

    private void setupDrag(View handle) {
        handle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatingParams.x;
                    initialY = floatingParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    floatingParams.x = initialX + (int)(event.getRawX() - initialTouchX);
                    floatingParams.y = initialY + (int)(event.getRawY() - initialTouchY);
                    if (floatingView != null)
                        windowManager.updateViewLayout(floatingView, floatingParams);
                    return true;
            }
            return false;
        });
    }

    private void startMirror() {
        mirrorHandler.removeCallbacks(mirrorRunnable);
        mirrorHandler.post(mirrorRunnable);
    }

    private void stopMirror() {
        mirrorHandler.removeCallbacks(mirrorRunnable);
    }

    private void minimizeToBubble() {
        if (isMinimized) return;
        isMinimized = true;
        stopMirror();
        if (floatingView != null) floatingView.setVisibility(View.GONE);

        float density = getResources().getDisplayMetrics().density;
        // Bubble kecil 56dp
        int size = (int) (56 * density);

        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackgroundColor(0xCC1565C0);

        ImageButton icon = makeButton(android.R.drawable.ic_media_play, "Restore");
        bubble.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        bubbleView = bubble;

        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 300;
        windowManager.addView(bubbleView, bubbleParams);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int bX, bY;
            float bTX, bTY;
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        bX = bubbleParams.x; bY = bubbleParams.y;
                        bTX = e.getRawX(); bTY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = bX + (int)(e.getRawX() - bTX);
                        bubbleParams.y = bY + (int)(e.getRawY() - bTY);
                        if (bubbleView != null)
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean isTap = System.currentTimeMillis() - downTime < 200
                                && Math.abs(e.getRawX() - bTX) < 10
                                && Math.abs(e.getRawY() - bTY) < 10;
                        if (isTap) {
                            if (bubbleView != null) {
                                windowManager.removeView(bubbleView);
                                bubbleView = null;
                            }
                            if (floatingView != null)
                                floatingView.setVisibility(View.VISIBLE);
                            isMinimized = false;
                            startMirror();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void restoreToFullscreen() {
        stopMirror();
        if (callback != null) callback.onRestoreToFullscreen();
        stopSelf();
    }

    private void stopFloating() {
        stopMirror();
        if (callback != null) callback.onRestoreToFullscreen();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopMirror();
        if (floatingView != null) { windowManager.removeView(floatingView); floatingView = null; }
        if (bubbleView != null) { windowManager.removeView(bubbleView); bubbleView = null; }
        callback = null;
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Floating Game", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, FloatingGameService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " (Floating)")
                .setContentText("Game berjalan di floating window")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
