/*
 * FloatingGameService.java
 * Tambahkan ke: app/src/main/java/javax/microedition/shell/FloatingGameService.java
 *
 * Service untuk menampilkan game J2ME dalam floating window overlay
 * Tanpa Shizuku — pakai TYPE_APPLICATION_OVERLAY biasa
 */

package javax.microedition.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import ru.playsoftware.j2meloader.R;

public class FloatingGameService extends Service {

    public static final String ACTION_SHOW = "floating.show";
    public static final String ACTION_HIDE = "floating.hide";
    public static final String ACTION_STOP = "floating.stop";
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_APP_PATH = "app_path";

    private static final String CHANNEL_ID = "floating_game_channel";
    private static final int NOTIF_ID = 1001;

    // Apakah floating window sedang aktif
    public static boolean isRunning = false;

    private WindowManager windowManager;

    // Container floating window utama
    private View floatingView;
    // Bubble kecil saat di-minimize
    private View bubbleView;

    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams bubbleParams;

    // View yang berisi game — diambil dari MicroActivity
    public static ViewGroup gameContainer;
    // Callback ke MicroActivity
    public static FloatingCallback callback;

    private boolean isMinimized = false;
    private String appName = "J2ME Game";

    // Posisi drag
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    public interface FloatingCallback {
        void onRestoreToFullscreen();
        ViewGroup getGameView();
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
            // Sudah ada, tampilkan saja
            floatingView.setVisibility(View.VISIBLE);
            if (bubbleView != null) {
                windowManager.removeView(bubbleView);
                bubbleView = null;
            }
            isMinimized = false;
            return;
        }

        // Buat container floating window
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF000000);

        // Tambah game view dari MicroActivity
        if (callback != null) {
            ViewGroup gameView = callback.getGameView();
            if (gameView != null && gameView.getParent() != null) {
                ((ViewGroup) gameView.getParent()).removeView(gameView);
            }
            if (gameView != null) {
                container.addView(gameView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }

        // Tambah overlay kontrol (tombol minimize, close, fullscreen)
        View controls = buildControlOverlay();
        container.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        floatingView = container;

        // Hitung ukuran default: 70% layar
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.70f);
        int height = (int) (dm.heightPixels * 0.65f);

        floatingParams = new WindowManager.LayoutParams(
                width,
                height,
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

        // Setup drag pada control bar
        setupDrag(controls);
    }

    private View buildControlOverlay() {
        // Control bar di atas floating window: [drag handle] [minimize] [fullscreen] [close]
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xCC1A1A1A);
        int barHeight = (int) (40 * getResources().getDisplayMetrics().density);
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));

        int btnSize = (int) (36 * getResources().getDisplayMetrics().density);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);

        // Tombol minimize (sembunyikan jadi bubble)
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

        // Tombol fullscreen (kembali ke Activity)
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

    private void minimizeToBubble() {
        if (isMinimized) return;
        isMinimized = true;

        // Sembunyikan floating window
        if (floatingView != null) {
            floatingView.setVisibility(View.GONE);
        }

        // Tampilkan bubble kecil
        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackgroundColor(0xCC2196F3); // biru

        // Icon game di bubble
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(android.R.drawable.ic_media_play);
        icon.setBackgroundColor(0x00000000);
        int iconSize = (int) (48 * getResources().getDisplayMetrics().density);
        bubble.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        bubbleView = bubble;

        bubbleParams = new WindowManager.LayoutParams(
                iconSize + 16,
                iconSize + 16,
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

        // Tap bubble → restore floating window
        bubbleView.setOnClickListener(v -> {
            windowManager.removeView(bubbleView);
            bubbleView = null;
            floatingView.setVisibility(View.VISIBLE);
            isMinimized = false;
        });

        // Drag bubble
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
                        if (bubbleView != null) {
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - downTime;
                        float dx = Math.abs(event.getRawX() - bTouchX);
                        float dy = Math.abs(event.getRawY() - bTouchY);
                        if (elapsed < 200 && dx < 10 && dy < 10) {
                            // Ini tap, bukan drag → restore
                            if (bubbleView != null) {
                                windowManager.removeView(bubbleView);
                                bubbleView = null;
                            }
                            if (floatingView != null) {
                                floatingView.setVisibility(View.VISIBLE);
                            }
                            isMinimized = false;
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void restoreToFullscreen() {
        // Kembalikan game view ke MicroActivity
        if (callback != null) {
            callback.onRestoreToFullscreen();
        }
        stopSelf();
    }

    private void stopFloating() {
        if (callback != null) {
            callback.onRestoreToFullscreen();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
        if (bubbleView != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
        }
        callback = null;
        gameContainer = null;
        super.onDestroy();
    }

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
        Intent stopIntent = new Intent(this, FloatingGameService.class)
                .setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " (Floating)")
                .setContentText("Game sedang berjalan di floating window")
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
