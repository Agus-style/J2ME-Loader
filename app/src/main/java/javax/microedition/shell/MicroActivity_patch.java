// ============================================================
// PATCH MicroActivity.java
// Tambahkan kode berikut di bagian yang sesuai
// ============================================================

// ---- 1. Tambah import berikut di bagian atas file ----

import android.provider.Settings;
import android.widget.ImageView;
import android.widget.RelativeLayout;

// ---- 2. Tambah field berikut di dalam class MicroActivity ----

private boolean isFloating = false;

// ---- 3. Ganti method onCreateOptionsMenu ----
// Tambah menu item "Float" setelah item screenshot

@Override
public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.midlet_displayable, menu);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        menu.findItem(R.id.action_lock_orientation).setVisible(true);
    }
    if (actionBarEnabled) {
        menu.findItem(R.id.action_ime_keyboard).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.findItem(R.id.action_take_screenshot).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }
    if (inputMethodManager == null) {
        menu.findItem(R.id.action_ime_keyboard).setVisible(false);
    }
    if (ContextHolder.getVk() == null) {
        menu.findItem(R.id.action_submenu_vk).setVisible(false);
    }

    // Tambah tombol Float di menu
    menu.add(Menu.NONE, R.id.action_float_window, Menu.NONE, "Float Window")
        .setIcon(android.R.drawable.ic_menu_zoom)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

    return true;
}

// ---- 4. Tambah handling di onOptionsItemSelected ----
// Di dalam method onOptionsItemSelected, tambah kondisi berikut:

} else if (id == R.id.action_float_window) {
    toggleFloatingWindow();
}

// ---- 5. Tambah method berikut di dalam class MicroActivity ----

private void toggleFloatingWindow() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
        // Minta permission overlay dulu
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, 1234);
        Toast.makeText(this, "Aktifkan 'Display over other apps' untuk fitur ini", Toast.LENGTH_LONG).show();
        return;
    }
    startFloatingWindow();
}

private void startFloatingWindow() {
    // Set callback ke service
    FloatingGameService.callback = new FloatingGameService.FloatingCallback() {
        @Override
        public void onRestoreToFullscreen() {
            // Kembalikan game view ke activity
            runOnUiThread(() -> {
                ViewGroup gameView = binding.displayableContainer;
                if (gameView.getParent() != null && gameView.getParent() != binding.getRoot()) {
                    ((ViewGroup) gameView.getParent()).removeView(gameView);
                    // Masukkan balik ke layout activity
                    ((ViewGroup) binding.getRoot()).addView(gameView);
                }
                isFloating = false;
            });
        }

        @Override
        public ViewGroup getGameView() {
            return binding.displayableContainer;
        }
    };

    // Start service floating
    Intent serviceIntent = new Intent(this, FloatingGameService.class)
            .setAction(FloatingGameService.ACTION_SHOW)
            .putExtra(FloatingGameService.EXTRA_APP_NAME, appName);
    startService(serviceIntent);
    isFloating = true;

    // Pindah ke background supaya floating window bisa dilihat
    moveTaskToBack(true);
}

// ---- 6. Tambah di onResume untuk handle restore dari floating ----
// Di dalam onResume(), tambah:

@Override
public void onResume() {
    super.onResume();
    visible = true;
    MidletThread.resumeApp();
    // Kalau balik dari floating window, pastikan UI normal
    if (isFloating && !FloatingGameService.isRunning) {
        isFloating = false;
    }
}

// ---- 7. Tambah di AndroidManifest.xml ----
/*
Tambah permission:
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

Tambah service declaration di dalam <application>:
<service
    android:name="javax.microedition.shell.FloatingGameService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />
*/
