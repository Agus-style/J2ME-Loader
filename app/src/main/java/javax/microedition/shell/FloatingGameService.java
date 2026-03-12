/*
 * FloatingGameService.java
 * app/src/main/java/javax/microedition/shell/FloatingGameService.java
 *
 * Floating window dengan screenshot mirror + touch input
 */

package javax.microedition.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
    private static final int SCREENSHOT_FPS = 30; // update tiap ~33ms

    public static boolean isRunning = false;
    public static FloatingCallback callback;

    private WindowManager windowManager;
    private View floatingView;
    private View bubbleView;
    private ImageView mirrorView; // tampilan screenshot game

    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams bubbleParams;

    private boolean isMinimized = false;
    private String appName = "J2ME Game";

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    // Handler untuk screenshot loop
    private final Handler mirrorHandler = new Handler(Looper.getMainLooper());
    private final Runnable mirrorRunnable = new Runnable() {
        @Override
        public void run() {
            updateMirror();
            if (!isMinimized && floatingView != null && isRunning) {
                mirrorHandler.postDelayed(this, 1000 / SCREENSHOT_FPS);
            }
        }
    };

    public interface FloatingCallback {
        void onRestoreToFullscreen();
        ViewGroup getGameView();
        // Untuk touch input — kirim koordinat ke game
        void dispatchTouchToGame(MotionEvent event, float scaleX, float scaleY);
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

    // ============================================================
    // SCREENSHOT MIRROR
    // ============================================================

    private void updateMirror() {
        if (callback == null || mirrorView == null) return;
        ViewGroup gameView = callback.getGameView();
        if (gameView == null || gameView.getWidth() == 0 || gameView.getHeight() == 0) return;

        try {
            Bitmap bmp = Bitmap.createBitmap(gameView.getWidth(), gameView.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            gameView.draw(canvas);
            mirrorView.setImageBitmap(bmp);
        } catch (Exception e) {
            // Gagal ambil screenshot, skip frame ini
        }
    }

    // ============================================================
    // FLOATING WINDOW
    // ============================================================

    private void showFloatingWindow() {
        if (floatingView != null) {
            floatingView.setVisibility(View.VISIBLE);
            if (bubbleView != null) {
                windowManager.removeView(bubbleView);
                bubbleView = null;
            }
            isMinimized = false;
            startMirrorLoop();
            return;
        }

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.70f);
        int height = (int) (dm.heightPixels * 0.65f);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF000000);

        // ImageView untuk mirror screenshot game
        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_XY);
        container.addView(mirrorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Setup touch input di mirror view
        setupTouchInput(mirrorView, width, height);

        // Control bar
        View controls = buildControlOverlay();
        int barHeight = (int) (40 * dm.density);
        container.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP));

        floatingView = container;

        floatingParams = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = 100;
        floatingParams.y = 100;

        windowManager.addView(floatingView, floatingParams);
        isMinimized = false;
        setupDrag(controls);
        startMirrorLoop();
    }

    // ============================================================
    // TOUCH INPUT — teruskan ke game dengan scaling koordinat
    // ============================================================

    private void setupTouchInput(ImageView mirrorView, int floatWidth, int floatHeight) {
        mirrorView.setOnTouchListener((v, event) -> {
            if (callback == null) return false;

            ViewGroup gameView = callback.getGameView();
            if (gameView == null) return false;

            // Hitung skala antara floating window dan ukuran game asli
            float scaleX = (float) gameView.getWidth() / floatWidth;
            float scaleY = (float) gameView.getHeight() / floatHeight;

            // Buat event baru dengan koordinat yang sudah discale
            MotionEvent scaledEvent = MotionEvent.obtain(event);
            scaledEvent.setLocation(event.getX() * scaleX, event.getY() * scaleY);

            callback.dispatchTouchToGame(scaledEvent, scaleX, scaleY);
            scaledEvent.recycle();
            return true;
        });
    }

    private void startMirrorLoop() {
        mirrorHandler.removeCallbacks(mirrorRunnable);
        mirrorHandler.post(mirrorRunnable);
    }

    private void stopMirrorLoop() {
        mirrorHandler.removeCallbacks(mirrorRunnable);
    }

    // ============================================================
    // CONTROL BAR
    // ============================================================

    private View buildControlOverlay() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xCC1A1A1A);
        float density = getResources().getDisplayMetrics().density;
        int btnSize = (int) (36 * density);
        int margin = (int) (4 * density);

        // Tombol minimize
        ImageButton btnMinimize = new ImageButton(this);
        btnMinimize.setImageResource(android.R.drawable.ic_media_pause);
        btnMinimize.setBackgroundColor(0x00000000);
        btnMinimize.setContentDescription("Minimize");
        FrameLayout.LayoutParams lpMin = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpMin.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpMin.rightMargin = btnSize * 2 + margin * 3;
        btnMinimize.setLayoutParams(lpMin);
        btnMinimize.setOnClickListener(v -> minimizeToBubble());
        bar.addView(btnMinimize);

        // Tombol fullscreen
        ImageButton btnFullscreen = new ImageButton(this);
        btnFullscreen.setImageResource(android.R.drawable.ic_menu_zoom);
        btnFullscreen.setBackgroundColor(0x00000000);
        btnFullscreen.setContentDescription("Fullscreen");
        FrameLayout.LayoutParams lpFull = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpFull.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpFull.rightMargin = btnSize + margin * 2;
        btnFullscreen.setLayoutParams(lpFull);
        btnFullscreen.setOnClickListener(v -> restoreToFullscreen());
        bar.addView(btnFullscreen);

        // Tombol close
        ImageButton btnClose = new ImageButton(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setContentDescription("Close");
        FrameLayout.LayoutParams lpClose = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpClose.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpClose.rightMargin = margin;
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(v -> stopFloating());
        bar.addView(btnClose);

        return bar;
    }

    private void setupDrag(View dragHandle) {
        dragHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatingParams.x;
                    initialY = floatingParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    floatingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                    floatingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                    if (floatingView != null) {
                        windowManager.updateViewLayout(floatingView, floatingParams);
                    }
                    return true;
            }
            return false;
        });
    }

    // ============================================================
    // MINIMIZE KE BUBBLE
    // ============================================================

    private void minimizeToBubble() {
        if (isMinimized) return;
        isMinimized = true;
        stopMirrorLoop();

        if (floatingView != null) floatingView.setVisibility(View.GONE);

        float density = getResources().getDisplayMetrics().density;
        int iconSize = (int) (48 * density);

        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackgroundColor(0xCC2196F3);
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(android.R.drawable.ic_media_play);
        icon.setBackgroundColor(0x00000000);
        bubble.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        bubbleView = bubble;

        bubbleParams = new WindowManager.LayoutParams(
                iconSize + 16, iconSize + 16,
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
            int bInitX, bInitY;
            float bTouchX, bTouchY;
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        bInitX = bubbleParams.x;
                        bInitY = bubbleParams.y;
                        bTouchX = event.getRawX();
                        bTouchY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = bInitX + (int) (event.getRawX() - bTouchX);
                        bubbleParams.y = bInitY + (int) (event.getRawY() - bTouchY);
                        if (bubbleView != null)
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - downTime;
                        float dx = Math.abs(event.getRawX() - bTouchX);
                        float dy = Math.abs(event.getRawY() - bTouchY);
                        if (elapsed < 200 && dx < 10 && dy < 10) {
                            // Tap → restore
                            if (bubbleView != null) {
                                windowManager.removeView(bubbleView);
                                bubbleView = null;
                            }
                            if (floatingView != null)
                                floatingView.setVisibility(View.VISIBLE);
                            isMinimized = false;
                            startMirrorLoop();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ============================================================
    // RESTORE & STOP
    // ============================================================

    private void restoreToFullscreen() {
        stopMirrorLoop();
        if (callback != null) callback.onRestoreToFullscreen();
        stopSelf();
    }

    private void stopFloating() {
        stopMirrorLoop();
        if (callback != null) callback.onRestoreToFullscreen();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopMirrorLoop();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
        if (bubbleView != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
        }
        callback = null;
        super.onDestroy();
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Floating Game", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("J2ME game floating window");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, FloatingGameService.class).setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " (Floating)")
                .setContentText("Game berjalan di floating window")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
