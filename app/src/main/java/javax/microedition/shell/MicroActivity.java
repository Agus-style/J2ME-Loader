/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2019-2022 Yury Kharchenko
 * Copyright 2022-2024 Arman Jussupgaliyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell;

import static ru.playsoftware.j2meloader.util.Constants.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import java.util.ArrayList;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.location.LocationProviderImpl;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.ActivityMicroBinding;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean visible;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;

	public ActivityMicroBinding binding;

	// ---- FLOATING WINDOW FIELD ----
	private boolean isFloating = false;
	// FPS asli user — disimpan saat masuk background
	private int userFpsLimit = 0;
	// Flag background running
	private boolean backgroundRunning = false;
	// WakeLock — game tetap jalan saat layar mati
	private PowerManager.WakeLock wakeLock;
	// WifiLock — koneksi WiFi tidak tidur saat layar mati
	private WifiManager.WifiLock wifiLock;
	private boolean pipPinned = false;       // Pin window - tidak bisa digeser
	private boolean pipLocked = false;       // Kunci layar - cegah sentuhan
	private boolean bubbleAutoHide = true;   // Auto hide bubble
	private final Handler bubbleHandler = new Handler(Looper.getMainLooper());

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);

		binding = ActivityMicroBinding.inflate(getLayoutInflater());
		View view = binding.getRoot();
		setContentView(view);
		// WakeLock — game tetap jalan walaupun layar mati
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		if (pm != null) {
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2MELoader:GameRunning");
			wakeLock.acquire();
		}
		// WifiLock — WiFi tidak tidur saat layar mati (penting untuk game online)
		WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
		if (wm != null) {
			wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "J2MELoader:GameWifi");
			wifiLock.acquire();
		}
		setSupportActionBar(binding.toolbar);

		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		if (sp.getBoolean(PREF_ADD_CUTOUT_AREA, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			getWindow().getAttributes().layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog("Invalid intent: app path is null");
				return;
			}
			appPath = data.toString();
		} else {
			appName = getTitle().toString();
			appPath = getApplicationInfo().dataDir + "/files/converted/midlet";
			File dir = new File(appPath);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new RuntimeException("Can't access file system");
			}
		}
		String arguments = intent.getStringExtra(KEY_START_ARGUMENTS);
		if (arguments != null) {
			MidletSystem.setProperty("com.nokia.mid.cmdline", arguments);
			String[] arr = arguments.split(";");
			for (String s: arr) {
				if (s.length() == 0) {
					continue;
				}
				if (s.contains("=")) {
					int i = s.indexOf('=');
					String k = s.substring(0, i);
					String v = s.substring(i + 1);
					MidletSystem.setProperty(k, v);
				} else {
					MidletSystem.setProperty(s, "");
				}
			}
		}
		MidletSystem.setProperty("com.nokia.mid.cmdline.instance", "1");
		microLoader = new MicroLoader(this, appPath);
		if (!microLoader.init()) {
			Config.startApp(this, appName, appPath, true, arguments);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = microLoader.getOrientation();
		if (vk != null) {
			vk.setView(binding.overlayView);
			binding.overlayView.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation);
		menuKey = microLoader.getMenuKeyCode();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

		try {
			loadMIDlet();
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}
	}

	public void lockNightMode() {
		int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (current == Configuration.UI_MODE_NIGHT_YES) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		visible = true;
		MidletThread.resumeApp();
		// Restore FPS normal saat kembali foreground
		if (backgroundRunning && microLoader != null) {
			// 0 = unlimited (default)
			microLoader.setLimitFps(0);
			backgroundRunning = false;
		}
	}

	@Override
	public void onPause() {
		visible = false;
		hideSoftInput();
		if (isInPictureInPictureMode()) {
			// Lagi PiP — tidak pause, biarkan jalan
		} else {
			// Game tetap jalan di background tapi FPS diturunkan biar tidak panas
			if (microLoader != null) {
				// Turunkan ke 5fps di background — cukup untuk jalan, tidak panas
				microLoader.setLimitFps(5);
				backgroundRunning = true;
			}
			// TIDAK pause game — biarkan jalan di background
		}
		super.onPause();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
				current instanceof Canvas) {
			hideSystemUI();
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		switch (orientation) {
			case ORIENTATION_AUTO:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
				break;
			case ORIENTATION_PORTRAIT:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
				break;
			case ORIENTATION_LANDSCAPE:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				break;
			case ORIENTATION_DEFAULT:
			default:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				break;
		}
	}

	private void loadMIDlet() throws Exception {
		LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			throw new Exception("No MIDlets found");
		} else if (size == 1) {
			MidletThread.create(microLoader, midletsClassArray[0]);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(names, (d, n) -> {
					String clazz = classes[n];
					ErrorReporter errorReporter = ACRA.getErrorReporter();
					String report = errorReporter.getCustomData(Constants.KEY_APPCENTER_ATTACHMENT);
					StringBuilder sb = new StringBuilder();
					if (report != null) {
						sb.append(report).append("\n");
					}
					sb.append("Begin app: ").append(names[n]).append(", ").append(clazz);
					errorReporter.putCustomData(Constants.KEY_APPCENTER_ATTACHMENT, sb.toString());
					MidletThread.create(microLoader, clazz);
					MidletThread.resumeApp();
				})
				.setOnCancelListener(d -> {
					d.dismiss();
					MidletThread.notifyDestroyed();
				});
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> MidletThread.notifyDestroyed());
		builder.show();
	}

	private int getToolBarHeight() {
		int[] attrs = new int[]{androidx.appcompat.R.attr.actionBarSize};
		TypedArray ta = obtainStyledAttributes(attrs);
		int toolBarHeight = ta.getDimensionPixelSize(0, -1);
		ta.recycle();
		return toolBarHeight;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
		} else if (!statusBarEnabled) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public void setCurrent(Displayable displayable) {
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	public void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					hideSoftInput();
					MidletThread.destroyApp();
				})
				.setNeutralButton(R.string.action_settings, (d, w) -> {
					hideSoftInput();
					Config.startApp(this, appName, appPath, true);
					MidletThread.destroyApp();
				})
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU)
			if (current instanceof Canvas && binding.displayableContainer.dispatchKeyEvent(event)) {
				return true;
			} else if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					event.startTracking();
					return true;
				} else if (event.isLongPress()) {
					return onKeyLongPress(event.getKeyCode(), event);
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				return onKeyUp(event.getKeyCode(), event);
			}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!actionBarEnabled &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && current instanceof Canvas) {
			showSystemUI();
		}
		super.openOptionsMenu();
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
			showExitConfirmation();
			return true;
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			return false;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
				&& (event.getFlags() & (KeyEvent.FLAG_LONG_PRESS | KeyEvent.FLAG_CANCELED)) == 0) {
			openOptionsMenu();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public void onBackPressed() {
		// Intentionally overridden by empty due to support for back-key remapping.
	}

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
		// ---- FLOATING WINDOW MENU ITEM ----
		menu.add(Menu.NONE, R.id.action_float_window, Menu.NONE, "Float Window")
				.setIcon(android.R.drawable.ic_menu_zoom)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (current instanceof Canvas) {
			menu.setGroupVisible(R.id.action_group_canvas, true);
			VirtualKeyboard vk = ContextHolder.getVk();
			if (vk != null) {
				boolean visible = vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF;
				menu.findItem(R.id.action_layout_edit_finish).setVisible(visible);
			}
		} else {
			menu.setGroupVisible(R.id.action_group_canvas, false);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_exit_midlet) {
			showExitConfirmation();
		} else if (id == R.id.action_save_log) {
			saveLog();
		} else if (id == R.id.action_lock_orientation) {
			if (item.isChecked()) {
				VirtualKeyboard vk = ContextHolder.getVk();
				int orientation = vk != null && vk.isPhone() ? ORIENTATION_PORTRAIT : microLoader.getOrientation();
				setOrientation(orientation);
				item.setChecked(false);
			} else {
				item.setChecked(true);
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
			}
		} else if (id == R.id.action_ime_keyboard) {
			inputMethodManager.toggleSoftInputFromWindow(binding.displayableContainer.getWindowToken(),
					InputMethodManager.SHOW_FORCED, 0);
		} else if (id == R.id.action_take_screenshot) {
			takeScreenshot();
		} else if (id == R.id.action_limit_fps) {
			showLimitFpsDialog();
		} else if (id == R.id.action_float_window) {
			// ---- FLOATING WINDOW HANDLER ----
			toggleFloatingWindow();
		} else if (ContextHolder.getVk() != null) {
			// Handled only when virtual keyboard is enabled
			handleVkOptions(id);
		}
		return true;
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
			showSaveVkAlert(false);
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot((Canvas) current, new SingleObserver<String>() {
			@Override
			public void onSubscribe(@NonNull Disposable d) {
			}

			@Override
			public void onSuccess(@NonNull String s) {
				Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
						+ " " + s, Toast.LENGTH_LONG).show();
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		boolean[] states = vk.getKeysVisibility();
		boolean[] changed = states.clone();
		new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(vk.getKeyNames(), changed, (dialog, which, isChecked) -> {})
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					if (!Arrays.equals(states, changed)) {
						vk.setKeysVisibility(changed);
						showSaveVkAlert(true);
					}
				}).show();
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.CONFIRMATION_REQUIRED);
		builder.setMessage(R.string.pref_vk_save_alert);
		builder.setNegativeButton(android.R.string.no, null);
		AlertDialog dialog = builder.create();

		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			AppCompatCheckBox cb = new AppCompatCheckBox(this);
			cb.setText(R.string.opt_save_screen_params);
			cb.setChecked(keepScreenPreferred);

			TypedValue out = new TypedValue();
			getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, out, true);
			int paddingH = getResources().getDimensionPixelOffset(out.resourceId);
			int paddingT = getResources().getDimensionPixelOffset(androidx.appcompat.R.dimen.abc_dialog_padding_top_material);
			dialog.setView(cb, paddingH, paddingT, paddingH, 0);

			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) -> {
				if (cb.isChecked()) {
					vk.saveScreenParams();
				}
				vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
			});
		} else {
			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) ->
					ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
		}
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(R.array.PREF_VK_TYPE_ENTRIES, vk.getLayout(), null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					vk.setLayout(((AlertDialog) d).getListView().getCheckedItemPosition());
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				});
		builder.show();
	}

	private void showLimitFpsDialog() {
		EditText editText = new EditText(this);
		editText.setHint(R.string.unlimited);
		editText.setInputType(InputType.TYPE_CLASS_NUMBER);
		editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
		editText.setMaxLines(1);
		editText.setSingleLine(true);
		float density = getResources().getDisplayMetrics().density;
		LinearLayout linearLayout = new LinearLayout(this);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = (int) (density * 20);
		params.setMargins(margin, 0, margin, 0);
		linearLayout.addView(editText, params);
		int paddingVertical = (int) (density * 16);
		int paddingHorizontal = (int) (density * 8);
		editText.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
		new AlertDialog.Builder(this)
				.setTitle(R.string.PREF_LIMIT_FPS)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					Editable text = editText.getText();
					int fps = 0;
					try {
						fps = TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text.toString().trim());
					} catch (NumberFormatException ignored) {
					}
					microLoader.setLimitFps(fps);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.reset, ((d, which) -> microLoader.setLimitFps(-1)))
				.show();
	}

	@Override
	public boolean onContextItemSelected(@NonNull MenuItem item) {
		if (current instanceof Form) {
			((Form) current).contextMenuItemSelected(item);
		} else if (current instanceof List) {
			AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
			((List) current).contextMenuItemSelected(item, info.position);
		}

		return super.onContextItemSelected(item);
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	public String getAppName() {
		return appName;
	}

	private class SetCurrentEvent extends SimpleEvent {
		private final Displayable current;
		private final Displayable next;

		private SetCurrentEvent(Displayable current, Displayable next) {
			this.current = current;
			this.next = next;
		}

		@Override
		public void process() {
			closeOptionsMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			if (next instanceof Alert) {
				return;
			}
			binding.displayableContainer.removeAllViews();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
			int toolbarHeight = 0;
			if (next instanceof Canvas) {
				hideSystemUI();
				if (!actionBarEnabled) {
					actionBar.hide();
				} else {
					final String title = next.getTitle();
					actionBar.setTitle(title == null ? appName : title);
					toolbarHeight = (int) (getToolBarHeight() / 1.5);
					layoutParams.height = toolbarHeight;
				}
			} else {
				showSystemUI();
				actionBar.show();
				final String title = next != null ? next.getTitle() : null;
				actionBar.setTitle(title == null ? appName : title);
				toolbarHeight = getToolBarHeight();
				layoutParams.height = toolbarHeight;
			}
			binding.overlayView.setLocation(0, toolbarHeight);
			binding.toolbar.setLayoutParams(layoutParams);
			invalidateOptionsMenu();
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 1) {
			synchronized (LocationProviderImpl.permissionLock) {
				LocationProviderImpl.permissionLock.notify();
			}
			LocationProviderImpl.permissionResult = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
		}
	}

	// ============================================================
	// PiP FLOATING + BUBBLE + FITUR TAMBAHAN
	// ============================================================

	// pipSize: 0=kecil (rasio game), 1=besar (16:9)
	private int pipSize = 0;

	// ---- TOGGLE FLOATING ----
	private void toggleFloatingWindow() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			pipSize = 0;
			doEnterPip();
		} else {
			Toast.makeText(this, "Butuh Android 8.0+", Toast.LENGTH_SHORT).show();
		}
	}

	private static final String ACTION_PIP_RESIZE  = "j2me.pip.resize";
	private static final String ACTION_PIP_SCREENSHOT = "j2me.pip.screenshot";
	private static final String ACTION_PIP_CLOSE  = "j2me.pip.close";

	private BroadcastReceiver pipReceiver;

	private void registerPipReceiver() {
		if (pipReceiver != null) return;
		pipReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(android.content.Context ctx, Intent intent) {
				String action = intent.getAction();
				if (action == null) return;
				switch (action) {
					case ACTION_PIP_RESIZE:
						// Ganti ukuran PiP langsung tanpa keluar
						pipSize = (pipSize + 1) % 2;
						updatePipParams();
						break;
					case ACTION_PIP_SCREENSHOT:
						quickScreenshot();
						break;
					case ACTION_PIP_CLOSE:
						isFloating = false;
						removeBubble();
						// Restore fullscreen
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							runOnUiThread(() -> {
								if (actionBarEnabled) {
									getSupportActionBar().show();
									binding.toolbar.setVisibility(View.GONE);
								}
							});
						}
						moveTaskToBack(false);
						break;
				}
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(ACTION_PIP_RESIZE);
		filter.addAction(ACTION_PIP_SCREENSHOT);
		filter.addAction(ACTION_PIP_CLOSE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(pipReceiver, filter);
		}
	}

	private void unregisterPipReceiver() {
		if (pipReceiver != null) {
			try { unregisterReceiver(pipReceiver); } catch (Exception ignored) {}
			pipReceiver = null;
		}
	}

	private android.app.PictureInPictureParams buildPipParams() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
		android.util.Rational ratio;
		if (pipSize == 1) {
			ratio = new android.util.Rational(16, 9);
		} else {
			int w = binding.displayableContainer.getWidth();
			int h = binding.displayableContainer.getHeight();
			ratio = (w > 0 && h > 0) ? new android.util.Rational(w, h) : new android.util.Rational(3, 4);
		}
		android.app.PictureInPictureParams.Builder builder =
				new android.app.PictureInPictureParams.Builder().setAspectRatio(ratio);

		// Tambah tombol aksi di dalam PiP window (max 3)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			ArrayList<RemoteAction> actions = new ArrayList<>();

			// Tombol 1: Resize (kecil/besar)
			Intent resizeIntent = new Intent(ACTION_PIP_RESIZE);
			PendingIntent resizePi = PendingIntent.getBroadcast(this, 1, resizeIntent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			actions.add(new RemoteAction(
					Icon.createWithResource(this, pipSize == 0
							? android.R.drawable.ic_menu_zoom
							: android.R.drawable.ic_menu_crop),
					pipSize == 0 ? "Besar" : "Kecil",
					pipSize == 0 ? "Perbesar window" : "Perkecil window",
					resizePi));

			// Tombol 2: Screenshot
			Intent ssIntent = new Intent(ACTION_PIP_SCREENSHOT);
			PendingIntent ssPi = PendingIntent.getBroadcast(this, 2, ssIntent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			actions.add(new RemoteAction(
					Icon.createWithResource(this, android.R.drawable.ic_menu_camera),
					"Screenshot",
					"Ambil screenshot game",
					ssPi));

			// Tombol 3: Tutup / kembali fullscreen
			Intent closeIntent = new Intent(ACTION_PIP_CLOSE);
			PendingIntent closePi = PendingIntent.getBroadcast(this, 3, closeIntent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			actions.add(new RemoteAction(
					Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
					"Fullscreen",
					"Kembali ke fullscreen",
					closePi));

			builder.setActions(actions);
		}
		return builder.build();
	}

	private void updatePipParams() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isFloating) {
			android.app.PictureInPictureParams params = buildPipParams();
			if (params != null) setPictureInPictureParams(params);
		}
	}

	private void doEnterPip() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
		registerPipReceiver();
		android.app.PictureInPictureParams params = buildPipParams();
		if (params != null) {
			enterPictureInPictureMode(params);
			isFloating = true;
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPiP, Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPiP, newConfig);
		isFloating = isInPiP;
		if (isInPiP) {
			getSupportActionBar().hide();
			binding.toolbar.setVisibility(View.GONE);
			showBubble();
		} else {
			if (actionBarEnabled) {
				getSupportActionBar().show();
				binding.toolbar.setVisibility(View.VISIBLE);
			}
			removeBubble();
			pipLocked = false;
			pipPinned = false;
		}
	}

	// ---- LANDSCAPE / PORTRAIT LOCK ----
	private void showOrientationLockDialog() {
		String[] opts = {"Auto", "Portrait", "Landscape"};
		new androidx.appcompat.app.AlertDialog.Builder(this)
				.setTitle("🔄 Kunci Orientasi")
				.setItems(opts, (d, which) -> {
					switch (which) {
						case 0: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); break;
						case 1: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); break;
						case 2: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); break;
					}
				}).show();
	}

	// ---- SPEED UP / SLOW DOWN ----
	private float gameSpeed = 1.0f;
	private void showSpeedDialog() {
		float[] speeds = {0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f};
		String[] labels = {"0.5x (Lambat)", "0.75x", "1.0x (Normal)", "1.5x", "2.0x (Cepat)", "3.0x (Turbo)"};
		new androidx.appcompat.app.AlertDialog.Builder(this)
				.setTitle("⚡ Kecepatan Game")
				.setItems(labels, (d, which) -> {
					gameSpeed = speeds[which];
					// Set speed ke MidletThread
					// MidletThread.setGameSpeed(gameSpeed);
					Toast.makeText(this, "Kecepatan: " + labels[which], Toast.LENGTH_SHORT).show();
				}).show();
	}

	// ---- SCREENSHOT ----
	private void quickScreenshot() {
		if (current instanceof Canvas) {
			takeScreenshot();
		} else {
			Toast.makeText(this, "Screenshot hanya tersedia saat game aktif", Toast.LENGTH_SHORT).show();
		}
	}

	// ---- VOLUME GAME ----
	private void showVolumeDialog() {
		AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
		int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);

		android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
		layout.setOrientation(android.widget.LinearLayout.VERTICAL);
		layout.setPadding(60, 20, 60, 20);

		TextView label = new TextView(this);
		label.setText("🔊 Volume: " + cur);
		label.setGravity(Gravity.CENTER);
		layout.addView(label);

		SeekBar seek = new SeekBar(this);
		seek.setMax(max);
		seek.setProgress(cur);
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
				label.setText("🔊 Volume: " + progress);
			}
			@Override public void onStartTrackingTouch(SeekBar sb) {}
			@Override public void onStopTrackingTouch(SeekBar sb) {}
		});
		layout.addView(seek);

		new androidx.appcompat.app.AlertDialog.Builder(this)
				.setTitle("Volume Game")
				.setView(layout)
				.setPositiveButton("OK", null)
				.show();
	}

	// ---- PIN WINDOW (kunci posisi PiP) ----
	private void togglePinWindow() {
		pipPinned = !pipPinned;
		Toast.makeText(this,
				pipPinned ? "📌 Window terkunci posisi" : "📌 Posisi bebas",
				Toast.LENGTH_SHORT).show();
	}

	// ---- KUNCI LAYAR (cegah sentuhan tidak sengaja) ----
	private void toggleLockScreen() {
		pipLocked = !pipLocked;
		if (pipLocked) {
			// Tutup semua input ke game view
			binding.displayableContainer.setOnTouchListener((v, e) -> true);
			Toast.makeText(this, "🔒 Layar terkunci — tahan 2 detik untuk buka", Toast.LENGTH_SHORT).show();
			// Overlay kunci
			showLockOverlay();
		} else {
			binding.displayableContainer.setOnTouchListener(null);
			removeLockOverlay();
			Toast.makeText(this, "🔓 Layar bebas", Toast.LENGTH_SHORT).show();
		}
	}

	private android.view.View lockOverlay;
	private void showLockOverlay() {
		if (lockOverlay != null) return;
		FrameLayout overlay = new FrameLayout(this);
		overlay.setBackgroundColor(0x22FF0000); // merah transparan

		TextView label = new TextView(this);
		label.setText("🔒");
		label.setTextSize(28);
		label.setGravity(Gravity.CENTER);
		overlay.addView(label, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT,
				Gravity.TOP | Gravity.END));

		// Tahan 2 detik untuk buka kunci
		overlay.setOnLongClickListener(v -> {
			toggleLockScreen();
			return true;
		});
		lockOverlay = overlay;
		binding.displayableContainer.addView(lockOverlay, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
	}

	private void removeLockOverlay() {
		if (lockOverlay != null) {
			binding.displayableContainer.removeView(lockOverlay);
			lockOverlay = null;
		}
	}

	// ---- BUBBLE HITAM + AUTO HIDE ----
	private android.view.WindowManager bubbleWm;
	private android.view.View bubbleView;
	private android.view.WindowManager.LayoutParams bubbleWp;
	private boolean bubbleVisible = true;

	private final Runnable hideBubbleRunnable = () -> {
		if (bubbleView != null && bubbleVisible) {
			// Fade ke transparan 20% — tidak hilang total, masih kelihatan samar
			bubbleView.animate().alpha(0.18f).setDuration(600).start();
			bubbleVisible = false;
		}
	};

	private void showBubble() {
		if (bubbleView != null) return;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				&& !Settings.canDrawOverlays(this)) {
			startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
					Uri.parse("package:" + getPackageName())));
			return;
		}
		bubbleWm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
		float dp = getResources().getDisplayMetrics().density;
		int size = (int) (44 * dp);

		FrameLayout bubble = new FrameLayout(this);
		// Transparan sedikit dari awal — tidak solid hitam
		bubble.setBackgroundColor(0xCC000000);

		ImageButton btn = new ImageButton(this);
		btn.setImageResource(android.R.drawable.ic_media_play);
		btn.setBackgroundColor(0x00000000);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				(int)(28*dp), (int)(28*dp), Gravity.CENTER);
		bubble.addView(btn, lp);
		bubbleView = bubble;
		bubbleVisible = true;
		bubbleView.setAlpha(1f);

		bubbleWp = new android.view.WindowManager.LayoutParams(
				size, size,
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
						? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
						: android.view.WindowManager.LayoutParams.TYPE_PHONE,
				android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
				android.graphics.PixelFormat.TRANSLUCENT);
		bubbleWp.gravity = Gravity.TOP | Gravity.START;
		bubbleWp.x = 0;
		bubbleWp.y = 300;
		bubbleWm.addView(bubbleView, bubbleWp);

		// Auto hide setelah 5 detik (lebih lama dari sebelumnya)
		scheduleHideBubble();

		btn.setOnClickListener(v -> {
			// Selalu tampilkan penuh dulu saat diklik
			bubbleView.animate().alpha(1f).setDuration(150).start();
			bubbleVisible = true;
			bubbleHandler.removeCallbacks(hideBubbleRunnable);
			showBubbleMenu();
		});

		btn.setOnLongClickListener(v -> {
			pipSize = (pipSize + 1) % 2;
			removeBubble();
			doEnterPip();
			return true;
		});

		// Drag bubble — sentuh area bubble mana saja
		bubble.setOnTouchListener(new android.view.View.OnTouchListener() {
			int ix, iy;
			float tx, ty;
			long downTime;
			@Override
			public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
				switch (e.getAction()) {
					case android.view.MotionEvent.ACTION_DOWN:
						// Muncul kembali saat disentuh
						bubbleView.animate().alpha(1f).setDuration(150).start();
						bubbleVisible = true;
						bubbleHandler.removeCallbacks(hideBubbleRunnable);
						ix = bubbleWp.x; iy = bubbleWp.y;
						tx = e.getRawX(); ty = e.getRawY();
						downTime = System.currentTimeMillis();
						return true;
					case android.view.MotionEvent.ACTION_MOVE:
						if (pipPinned) return true;
						float dx = e.getRawX() - tx;
						float dy = e.getRawY() - ty;
						if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
							bubbleWp.x = ix + (int) dx;
							bubbleWp.y = iy + (int) dy;
							if (bubbleView != null)
								bubbleWm.updateViewLayout(bubbleView, bubbleWp);
						}
						return true;
					case android.view.MotionEvent.ACTION_UP:
						// Tap cepat = buka menu
						if (System.currentTimeMillis() - downTime < 200
								&& Math.abs(e.getRawX()-tx) < 10
								&& Math.abs(e.getRawY()-ty) < 10) {
							showBubbleMenu();
						}
						// Jadwalkan hide lagi setelah 5 detik
						scheduleHideBubble();
						return true;
				}
				return false;
			}
		});
	}

	private void scheduleHideBubble() {
		bubbleHandler.removeCallbacks(hideBubbleRunnable);
		// Auto hide setelah 5 detik — lebih lama, tidak cepat hilang
		bubbleHandler.postDelayed(hideBubbleRunnable, 5000);
	}

	// Menu saat tap bubble — semua fitur di sini
	private void showBubbleMenu() {
		// Tampilkan bubble dulu
		if (bubbleView != null) {
			bubbleView.animate().alpha(1f).setDuration(200).start();
			bubbleVisible = true;
		}
		bubbleHandler.removeCallbacks(hideBubbleRunnable);

		String[] items = {
				"🔄 Ganti Ukuran PiP (kecil/besar)",
				"🔄 Kunci Orientasi",
				"⚡ Kecepatan Game",
				"📸 Screenshot",
				"🔊 Volume",
				pipPinned ? "📌 Lepas Pin Window" : "📌 Pin Window",
				pipLocked ? "🔓 Buka Kunci Layar" : "🔒 Kunci Layar",
				"❌ Tutup Menu"
		};

		// Buat dialog di atas semua window
		android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
		builder.setTitle("🎮 Kontrol Game")
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0: pipSize = (pipSize+1)%2; removeBubble(); doEnterPip(); break;
						case 1: showOrientationLockDialog(); break;
						case 2: showSpeedDialog(); break;
						case 3: quickScreenshot(); break;
						case 4: showVolumeDialog(); break;
						case 5: togglePinWindow(); break;
						case 6: toggleLockScreen(); break;
						case 7: scheduleHideBubble(); break;
					}
				});
		builder.show();
	}

	private void removeBubble() {
		bubbleHandler.removeCallbacks(hideBubbleRunnable);
		if (bubbleView != null && bubbleWm != null) {
			try { bubbleWm.removeView(bubbleView); } catch (Exception ignored) {}
			bubbleView = null;
		}
	}

	@Override
	protected void onDestroy() {
		removeBubble();
		removeLockOverlay();
		unregisterPipReceiver();
		// Lepas WakeLock saat game selesai
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
		}
		// Lepas WifiLock saat game selesai
		if (wifiLock != null && wifiLock.isHeld()) {
			wifiLock.release();
		}
		binding = null;
		super.onDestroy();
	}
}
