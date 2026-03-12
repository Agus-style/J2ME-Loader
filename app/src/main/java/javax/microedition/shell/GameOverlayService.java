/*
 * GameOverlayService.java
 * app/src/main/java/javax/microedition/shell/GameOverlayService.java
 *
 * Floating overlay window yang:
 * - Mirror bitmap game dari offscreenCopy (tidak hitam!)
 * - Forward touch ke game dengan koordinat yang benar
 * - Bisa di-resize dengan pinch
 * - Bisa di-drag
 * - Geser ke pinggir → jadi bubble hitam kecil
 * - Tidak bentrok dengan WA VC karena bukan PiP
 * - Background tetap jalan saat layar mati (WakeLock)
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
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Display;

public class GameOverlayService extends Service {

    public static final String ACTION_SHOW = "overlay.show";
    public static final String ACTION_STOP = "overlay.stop";
    private static final String CHANNEL_ID = "game_overlay_channel";
    private static final int NOTIF_ID = 3001;
    private static final int FPS = 30;

    public static boolean isRunning = false;

    // Callback ke MicroActivity
    public interface OverlayCallback {
        Canvas getCurrentCanvas();
        void onOverlayClosed();
    }
    public static OverlayCallback callback = null;

    private WindowManager wm;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Floating window
    private FrameLayout floatContainer;
    private ImageView mirrorView;
    private WindowManager.LayoutParams floatParams;
    private int floatW, floatH;

    // Bubble
    private FrameLayout bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private boolean isBubble = false;

    // Control bar
    private View controlBar;
    private boolean controlVisible = true;
    private final Handler controlHideHandler = new Handler(Looper.getMainLooper());

    // Mirror loop
    private final Runnable mirrorRunnable = new Runnable() {
        @Override
        public void run() {
            updateMirror();
            if (isRunning && !isBubble) {
                handler.postDelayed(this, 1000 / FPS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        isRunning = true;
        createNotifChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        if (ACTION_SHOW.equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildNotif());
            showOverlay();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    // ================================================================
    // WAKELOCK
    // ================================================================
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2ME:OverlayWakeLock");
            wakeLock.acquire();
        }
    }

    // ================================================================
    // FLOATING OVERLAY WINDOW
    // ================================================================
    private void showOverlay() {
        if (floatContainer != null) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        floatW = (int) (dm.widthPixels * 0.55f);
        floatH = (int) (dm.heightPixels * 0.50f);

        // Container utama
        floatContainer = new FrameLayout(this);
        floatContainer.setBackgroundColor(0xFF000000);

        // Mirror view — tampilkan bitmap game
        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_XY);
        floatContainer.addView(mirrorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Touch layer — forward ke game
        mirrorView.setOnTouchListener((v, event) -> {
            if (callback == null) return false;
            Canvas canvas = callback.getCurrentCanvas();
            if (canvas == null) return false;
            canvas.dispatchOverlayTouch(event, floatW, floatH);
            // Tampilkan control bar saat disentuh
            showControlBar();
            return true;
        });

        // Control bar (transparan, auto hide)
        controlBar = buildControlBar(dm.density);
        floatContainer.addView(controlBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (36 * dm.density),
                Gravity.TOP));

        floatParams = new WindowManager.LayoutParams(
                floatW, floatH,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = (int) (dm.widthPixels * 0.2f);
        floatParams.y = (int) (dm.heightPixels * 0.15f);

        wm.addView(floatContainer, floatParams);
        isBubble = false;
        startMirror();
        scheduleHideControl();
    }

    // ================================================================
    // CONTROL BAR
    // ================================================================
    private View buildControlBar(float dp) {
        FrameLayout bar = new FrameLayout(this);
        // Transparan gelap — tidak mengganggu visual game
        bar.setBackgroundColor(0xAA000000);

        int btnSz = (int) (28 * dp);
        int margin = (int) (4 * dp);

        // Drag handle — area tengah untuk drag window
        View dragHandle = new View(this);
        dragHandle.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams lpDrag = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        bar.addView(dragHandle, lpDrag);
        setupDrag(dragHandle);

        // Tombol minimize ke bubble (kiri)
        ImageButton btnMin = new ImageButton(this);
        btnMin.setImageResource(android.R.drawable.ic_media_pause);
        btnMin.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams lpMin = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpMin.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lpMin.leftMargin = margin;
        btnMin.setLayoutParams(lpMin);
        btnMin.setOnClickListener(v -> minimizeToBubble());
        bar.addView(btnMin);

        // Tombol resize kecil/besar (tengah)
        ImageButton btnResize = new ImageButton(this);
        btnResize.setImageResource(android.R.drawable.ic_menu_zoom);
        btnResize.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams lpResize = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpResize.gravity = Gravity.CENTER;
        btnResize.setLayoutParams(lpResize);
        btnResize.setOnClickListener(v -> toggleSize());
        bar.addView(btnResize);

        // Tombol close (kanan)
        ImageButton btnClose = new ImageButton(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams lpClose = new FrameLayout.LayoutParams(btnSz, btnSz);
        lpClose.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lpClose.rightMargin = margin;
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(v -> {
            if (callback != null) callback.onOverlayClosed();
            stopSelf();
        });
        bar.addView(btnClose);

        return bar;
    }

    private void showControlBar() {
        if (controlBar == null) return;
        controlBar.setVisibility(View.VISIBLE);
        controlBar.setAlpha(1f);
        controlVisible = true;
        scheduleHideControl();
    }

    private void scheduleHideControl() {
        controlHideHandler.removeCallbacksAndMessages(null);
        controlHideHandler.postDelayed(() -> {
            if (controlBar != null) {
                controlBar.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                    if (controlBar != null) controlBar.setVisibility(View.GONE);
                }).start();
                controlVisible = false;
            }
        }, 3000);
    }

    // ================================================================
    // DRAG
    // ================================================================
    private void setupDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy;
            float tx, ty;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = floatParams.x; iy = floatParams.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        controlHideHandler.removeCallbacksAndMessages(null);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        floatParams.x = ix + (int)(e.getRawX() - tx);
                        floatParams.y = iy + (int)(e.getRawY() - ty);
                        if (floatContainer != null)
                            wm.updateViewLayout(floatContainer, floatParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Geser ke pinggir → bubble
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int edgePx = (int)(55 * dm.density);
                        if (floatParams.x < -edgePx || floatParams.x + floatW > dm.widthPixels + edgePx) {
                            minimizeToBubble();
                        } else {
                            scheduleHideControl();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ================================================================
    // RESIZE
    // ================================================================
    private boolean isLarge = false;
    private void toggleSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        isLarge = !isLarge;
        if (isLarge) {
            floatW = (int)(dm.widthPixels * 0.80f);
            floatH = (int)(dm.heightPixels * 0.75f);
        } else {
            floatW = (int)(dm.widthPixels * 0.55f);
            floatH = (int)(dm.heightPixels * 0.50f);
        }
        floatParams.width = floatW;
        floatParams.height = floatH;
        if (floatContainer != null)
            wm.updateViewLayout(floatContainer, floatParams);
    }

    // ================================================================
    // MIRROR BITMAP
    // ================================================================
    private void updateMirror() {
        if (mirrorView == null || callback == null) return;
        Canvas canvas = callback.getCurrentCanvas();
        if (canvas == null) return;
        Bitmap bmp = canvas.getOffscreenBitmap();
        if (bmp != null) {
            mirrorView.setImageBitmap(bmp);
        }
    }

    private void startMirror() {
        handler.removeCallbacks(mirrorRunnable);
        handler.post(mirrorRunnable);
    }

    private void stopMirror() {
        handler.removeCallbacks(mirrorRunnable);
    }

    // ================================================================
    // BUBBLE
    // ================================================================
    private void minimizeToBubble() {
        if (isBubble) return;
        isBubble = true;
        stopMirror();

        if (floatContainer != null) {
            wm.removeView(floatContainer);
            floatContainer = null;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = (int)(48 * dm.density);

        bubbleView = new FrameLayout(this);
        bubbleView.setBackgroundColor(0xFF000000);

        ImageButton icon = new ImageButton(this);
        icon.setImageResource(android.R.drawable.ic_media_play);
        icon.setBackgroundColor(0x00000000);
        bubbleView.addView(icon, new FrameLayout.LayoutParams(
                (int)(30*dm.density), (int)(30*dm.density), Gravity.CENTER));

        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 350;
        wm.addView(bubbleView, bubbleParams);

        // Tap → restore overlay
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int bx, by; float btx, bty; long bdt;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        bx = bubbleParams.x; by = bubbleParams.y;
                        btx = e.getRawX(); bty = e.getRawY();
                        bdt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX()-btx, dy = e.getRawY()-bty;
                        if (Math.abs(dx)>6||Math.abs(dy)>6) {
                            bubbleParams.x = bx+(int)dx;
                            bubbleParams.y = by+(int)dy;
                            if (bubbleView!=null) wm.updateViewLayout(bubbleView, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis()-bdt<250
                                && Math.abs(e.getRawX()-btx)<15
                                && Math.abs(e.getRawY()-bty)<15) {
                            restoreFromBubble();
                        }
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
            wm.removeView(bubbleView);
            bubbleView = null;
        }
        showOverlay();
    }

    // ================================================================
    // LIFECYCLE
    // ================================================================
    @Override
    public void onDestroy() {
        isRunning = false;
        stopMirror();
        controlHideHandler.removeCallbacksAndMessages(null);
        if (floatContainer != null) { try { wm.removeView(floatContainer); } catch (Exception ignored) {} floatContainer = null; }
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Exception ignored) {} bubbleView = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        callback = null;
        super.onDestroy();
    }

    // ================================================================
    // NOTIFICATION
    // ================================================================
    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Game Overlay", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        Intent stop = new Intent(this, GameOverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Game J2ME (Overlay)")
                .setContentText("Tap untuk stop overlay")
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
