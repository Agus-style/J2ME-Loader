/*
 * FloatingGameService.java
 * app/src/main/java/javax/microedition/shell/FloatingGameService.java
 *
 * Fitur:
 * - Floating window dengan PixelCopy (tidak hitam)
 * - Geser ke pinggir → bubble hitam otomatis
 * - Tap bubble → muncul lagi
 * - Kunci ukuran (tidak fullscreen saat disentuh)
 * - Background tetap jalan saat layar mati (WakeLock)
 * - Support floating ganda (multiple instance via static list)
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
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

public class FloatingGameService extends Service {

    public static final String ACTION_SHOW = "floating.show";
    public static final String ACTION_STOP = "floating.stop";
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_INSTANCE_ID = "instance_id";

    private static final String CHANNEL_ID = "floating_game_channel";
    private static final int NOTIF_ID = 2001;
    private static final int FPS = 25;
    private static final int BUBBLE_SIZE_DP = 52;
    private static final int EDGE_SNAP_DP = 60; // jarak dari pinggir untuk jadi bubble

    public static boolean isRunning = false;

    // Support multiple instances
    public static final List<FloatingCallback> callbacks = new ArrayList<>();

    private WindowManager windowManager;

    // Floating window utama
    private FrameLayout floatingContainer;
    private ImageView mirrorView;
    private WindowManager.LayoutParams floatingParams;

    // Bubble
    private FrameLayout bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    // State
    private boolean isBubble = false;
    private boolean sizeLocked = false;
    private boolean isMinimizedToBubble = false;
    private int instanceId = 0;
    private String appName = "J2ME Game";

    // Drag
    private int dragInitX, dragInitY;
    private float dragTouchX, dragTouchY;
    private long dragDownTime;

    // Mirror loop
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mirrorLoop = new Runnable() {
        @Override
        public void run() {
            captureMirror();
            if (!isBubble && isRunning) {
                handler.postDelayed(this, 1000 / FPS);
            }
        }
    };

    // WakeLock — layar mati tetap jalan
    private PowerManager.WakeLock wakeLock;

    public interface FloatingCallback {
        ViewGroup getGameView();      // view container game
        String getAppName();
        int getInstanceId();
        void onClose();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        isRunning = true;
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;

        appName = intent.getStringExtra(EXTRA_APP_NAME) != null
                ? intent.getStringExtra(EXTRA_APP_NAME) : "J2ME Game";
        instanceId = intent.getIntExtra(EXTRA_INSTANCE_ID, 0);

        switch (action) {
            case ACTION_SHOW:
                startForeground(NOTIF_ID + instanceId, buildNotification());
                showFloatingWindow();
                break;
            case ACTION_STOP:
                stopSelf();
                break;
        }
        return START_STICKY;
    }

    // ================================================================
    // WAKELOCK — tetap jalan saat layar mati
    // ================================================================

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "J2MELoader:FloatingWakeLock"
            );
            wakeLock.acquire(); // tidak ada timeout — jalan terus
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ================================================================
    // FLOATING WINDOW
    // ================================================================

    private void showFloatingWindow() {
        if (floatingContainer != null) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.68f);
        int height = (int) (dm.heightPixels * 0.60f);

        // Container utama
        floatingContainer = new FrameLayout(this);
        floatingContainer.setBackgroundColor(0xFF000000);

        // Mirror view — tampilkan PixelCopy dari game
        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_XY);
        floatingContainer.addView(mirrorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Touch — teruskan ke game dengan scaling
        final int fWidth = width;
        final int fHeight = height;
        mirrorView.setOnTouchListener((v, event) -> {
            FloatingCallback cb = getCallback();
            if (cb == null) return false;
            ViewGroup gameView = cb.getGameView();
            if (gameView == null || gameView.getWidth() == 0) return false;
            float sx = (float) gameView.getWidth() / fWidth;
            float sy = (float) gameView.getHeight() / fHeight;
            MotionEvent scaled = MotionEvent.obtain(event);
            scaled.setLocation(event.getX() * sx, event.getY() * sy);
            gameView.dispatchTouchEvent(scaled);
            scaled.recycle();
            return true;
        });

        // Control bar
        int barH = (int) (38 * dm.density);
        View bar = buildControlBar(dm.density, sizeLocked);
        floatingContainer.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP));

        // Params
        floatingParams = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = 40;
        floatingParams.y = 120;

        windowManager.addView(floatingContainer, floatingParams);
        isBubble = false;
        startMirrorLoop();
        setupDrag(bar);
    }

    // ================================================================
    // CONTROL BAR
    // ================================================================

    private View buildControlBar(float density, boolean locked) {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xEE1A1A1A);

        int btnSz = (int) (30 * density);
        int margin = (int) (5 * density);

        // 🔒 Tombol kunci ukuran (kiri)
        ImageButton btnLock = new ImageButton(this);
        btnLock.setImageResource(locked
                ? android.R.drawable.ic_lock_lock
                : android.R.drawable.ic_lock_idle_lock);
        btnLock.setBackgroundColor(locked ? 0x99FF9800 : 0x00000000);
        btnLock.setContentDescription("Lock size");
        FrameLayout.LayoutParams lpLock = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpLock.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lpLock.leftMargin = margin;
        btnLock.setLayoutParams(lpLock);
        btnLock.setOnClickListener(v -> {
            sizeLocked = !sizeLocked;
            // Rebuild bar
            floatingContainer.removeView(bar);
            int barH = (int) (38 * density);
            View newBar = buildControlBar(density, sizeLocked);
            floatingContainer.addView(newBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP));
            setupDrag(newBar);
            // Update flag focusable berdasarkan lock
            if (sizeLocked) {
                floatingParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                floatingParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            } else {
                floatingParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                floatingParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            windowManager.updateViewLayout(floatingContainer, floatingParams);
            Toast.makeText(this,
                    sizeLocked ? "🔒 Ukuran terkunci — bisa main!" : "🔓 Ukuran bebas",
                    Toast.LENGTH_SHORT).show();
        });
        bar.addView(btnLock);

        // ⏸ Tombol minimize ke bubble
        ImageButton btnMin = new ImageButton(this);
        btnMin.setImageResource(android.R.drawable.ic_media_pause);
        btnMin.setBackgroundColor(0x00000000);
        btnMin.setContentDescription("Minimize");
        FrameLayout.LayoutParams lpMin = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpMin.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpMin.rightMargin = (btnSz + margin) * 2 + margin;
        btnMin.setLayoutParams(lpMin);
        btnMin.setOnClickListener(v -> minimizeToBubble());
        bar.addView(btnMin);

        // ✕ Tombol close
        ImageButton btnClose = new ImageButton(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setContentDescription("Close");
        FrameLayout.LayoutParams lpClose = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpClose.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpClose.rightMargin = margin;
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(v -> {
            FloatingCallback cb = getCallback();
            if (cb != null) cb.onClose();
            stopSelf();
        });
        bar.addView(btnClose);

        return bar;
    }

    // ================================================================
    // DRAG — geser ke pinggir → bubble otomatis
    // ================================================================

    private void setupDrag(View handle) {
        handle.setOnTouchListener((v, event) -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int edgeSnap = (int) (EDGE_SNAP_DP * dm.density);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragInitX = floatingParams.x;
                    dragInitY = floatingParams.y;
                    dragTouchX = event.getRawX();
                    dragTouchY = event.getRawY();
                    dragDownTime = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    floatingParams.x = dragInitX + (int)(event.getRawX() - dragTouchX);
                    floatingParams.y = dragInitY + (int)(event.getRawY() - dragTouchY);
                    if (floatingContainer != null)
                        windowManager.updateViewLayout(floatingContainer, floatingParams);
                    return true;

                case MotionEvent.ACTION_UP:
                    // Cek apakah di pinggir → jadi bubble
                    boolean nearLeft = floatingParams.x < -edgeSnap;
                    boolean nearRight = floatingParams.x + floatingParams.width > dm.widthPixels + edgeSnap;
                    if (nearLeft || nearRight) {
                        minimizeToBubble();
                    }
                    return true;
            }
            return false;
        });
    }

    // ================================================================
    // PIXELCOPY MIRROR — tidak hitam!
    // ================================================================

    private void captureMirror() {
        if (mirrorView == null) return;
        FloatingCallback cb = getCallback();
        if (cb == null) return;
        ViewGroup gameView = cb.getGameView();
        if (gameView == null || gameView.getWidth() == 0) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Cari SurfaceView di dalam gameView
            SurfaceView sv = findSurfaceView(gameView);
            if (sv != null && sv.getHolder().getSurface().isValid()) {
                Bitmap bmp = Bitmap.createBitmap(
                        sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(sv, bmp, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        handler.post(() -> mirrorView.setImageBitmap(bmp));
                    }
                }, handler);
                return;
            }
        }

        // Fallback — view.draw()
        try {
            Bitmap bmp = Bitmap.createBitmap(
                    gameView.getWidth(), gameView.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            gameView.draw(canvas);
            mirrorView.setImageBitmap(bmp);
        } catch (Exception ignored) {}
    }

    private SurfaceView findSurfaceView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView) return (SurfaceView) child;
            if (child instanceof ViewGroup) {
                SurfaceView found = findSurfaceView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void startMirrorLoop() {
        handler.removeCallbacks(mirrorLoop);
        handler.post(mirrorLoop);
    }

    private void stopMirrorLoop() {
        handler.removeCallbacks(mirrorLoop);
    }

    // ================================================================
    // BUBBLE — minimize ke tombol hitam kecil
    // ================================================================

    private void minimizeToBubble() {
        if (isBubble) return;
        isBubble = true;
        stopMirrorLoop();

        if (floatingContainer != null) {
            windowManager.removeView(floatingContainer);
            floatingContainer = null;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = (int) (BUBBLE_SIZE_DP * dm.density);

        bubbleView = new FrameLayout(this);
        bubbleView.setBackgroundColor(0xFF000000); // hitam

        // Icon play di tengah bubble
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(android.R.drawable.ic_media_play);
        icon.setBackgroundColor(0x00000000);
        bubbleView.addView(icon, new FrameLayout.LayoutParams(
                size, size, Gravity.CENTER));

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
        bubbleParams.y = 400;

        windowManager.addView(bubbleView, bubbleParams);

        // Drag + tap bubble
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int bX, bY;
            float bTX, bTY;
            long bDown;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        bX = bubbleParams.x; bY = bubbleParams.y;
                        bTX = e.getRawX(); bTY = e.getRawY();
                        bDown = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = bX + (int)(e.getRawX() - bTX);
                        bubbleParams.y = bY + (int)(e.getRawY() - bTY);
                        if (bubbleView != null)
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean isTap = System.currentTimeMillis() - bDown < 250
                                && Math.abs(e.getRawX() - bTX) < 15
                                && Math.abs(e.getRawY() - bTY) < 15;
                        if (isTap) restoreFromBubble();
                        return true;
                }
                return false;
            }
        });
    }

    private void restoreFromBubble() {
        if (!isBubble) return;
        isBubble = false;

        if (bubbleView != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
        }
        showFloatingWindow();
    }

    // ================================================================
    // HELPER
    // ================================================================

    private FloatingCallback getCallback() {
        if (callbacks.isEmpty()) return null;
        for (FloatingCallback cb : callbacks) {
            if (cb.getInstanceId() == instanceId) return cb;
        }
        return callbacks.isEmpty() ? null : callbacks.get(0);
    }

    // ================================================================
    // LIFECYCLE
    // ================================================================

    @Override
    public void onDestroy() {
        isRunning = false;
        stopMirrorLoop();
        releaseWakeLock();
        if (floatingContainer != null) {
            windowManager.removeView(floatingContainer);
            floatingContainer = null;
        }
        if (bubbleView != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
        }
        super.onDestroy();
    }

    // ================================================================
    // NOTIFICATION
    // ================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Floating Game", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Game J2ME floating window");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, FloatingGameService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, instanceId, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " (Floating)")
                .setContentText("Game berjalan di background")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
