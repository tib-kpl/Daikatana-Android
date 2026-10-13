package fr.tibkpl.daikatana;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Ecran du jeu : plein ecran immersif sur toute la surface (16:9 et ecrans plus larges,
 * zone de l'encoche comprise). Prepare les fichiers, lance le moteur dans le runtime Linux
 * et relie affichage, manette/clavier et son.
 */
public class GameActivity extends Activity {

    static final String EXTRA_DATA_DIR = "data_dir";

    private static final String TAG = "Daikatana";

    private FrameLayout root;
    private TextView message;
    private TextView fpsCounter;
    private GameRuntime runtime;
    private InputBridge input;
    private AudioBridge audio;
    private volatile boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControllerSettings.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(0xFFEEEEEE);
        message.setTextSize(16);
        message.setPadding(48, 48, 48, 48);
        message.setText(R.string.game_preparing);
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        addSettingsButton();
        hideSystemBars();

        String path = getIntent().getStringExtra(EXTRA_DATA_DIR);
        File dataDir = path == null ? null : GameData.findDataDir(new File(path));
        if (dataDir == null) {
            // Fichiers disparus entre-temps : retour au lanceur qui redemandera le dossier.
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }

        new Thread(() -> {
            try {
                GameInstaller.LaunchConfig config = GameInstaller.prepare(this, dataDir,
                        step -> runOnUiThread(() -> message.setText(step)));
                runOnUiThread(() -> startGame(config));
            } catch (Exception e) {
                Log.e(TAG, "Preparation", e);
                runOnUiThread(() -> message.setText(getString(R.string.game_error, e.getMessage())));
            }
        }, "daikatana-prepare").start();
    }

    private void startGame(GameInstaller.LaunchConfig config) {
        if (isFinishing()) return;
        runtime = new GameRuntime(this, config);
        try {
            audio = new AudioBridge(runtime.audioFifo());
            audio.start();
            InputBridge.debug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            input = new InputBridge();

            Point screen = screenSize();
            // Hauteur de rendu choisie (largeur au format de l'ecran, donc 16:9 sur un ecran
            // 16:9) ; l'image est ensuite agrandie a la taille de l'ecran.
            int height = Math.min(GameData.getRenderHeight(this), screen.y);
            int width = Math.round(height * (float) screen.x / screen.y) & ~1;

            runtime.framebufferFile().delete();
            FramebufferView view = new FramebufferView(this, runtime.framebufferFile());
            root.addView(view, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            boolean gpu = runtime.gpuAvailable() && !"software".equals(GameData.getRenderer(this)) && !GameData.isGpuFailed(this);
            Log.i(TAG, "Rendu " + (gpu ? "GPU (Turnip + Zink)" : "logiciel") + ", " + width + "x" + height);
            java.lang.Process process = runtime.start(width, height, input.port(), gpu);
            message.setText(R.string.game_starting);
            message.postDelayed(() -> message.setVisibility(View.GONE), 4000);
            watch(process, gpu);
            addFpsCounter();
        } catch (Exception e) {
            Log.e(TAG, "Lancement", e);
            message.setText(getString(R.string.game_error, e.getMessage()));
        }
    }

    /** Ferme l'ecran quand le jeu se termine ; affiche le journal s'il plante au demarrage. */
    private void watch(java.lang.Process process, boolean gpu) {
        long startedAt = System.currentTimeMillis();
        Thread watcher = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (finishing) return;
            boolean early = System.currentTimeMillis() - startedAt < 10_000;
            String tail = runtime.logTail(25);
            Log.i(TAG, "Moteur termine (code " + code + ")\n" + tail);
            runOnUiThread(() -> {
                if (early && code != 0 && gpu) {
                    // Le rendu GPU ne fonctionne pas sur cet appareil : relance en rendu logiciel
                    Log.w(TAG, "Echec du rendu GPU, passage en rendu logiciel");
                    GameData.setGpuFailed(this, true);
                    recreate();
                } else if (early && code != 0) {
                    message.setVisibility(View.VISIBLE);
                    message.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    message.setTextSize(12);
                    message.setText(getString(R.string.game_exited, code, tail));
                } else {
                    finish();
                }
            });
        }, "daikatana-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void addSettingsButton() {
        android.widget.ImageButton gear = new android.widget.ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setBackgroundColor(0x00000000);
        gear.setAlpha(0.45f);
        gear.setFocusable(false);
        gear.setContentDescription(getString(R.string.graphics_title));
        int size = Math.round(40 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        gear.setOnClickListener(v -> startActivityForResult(new Intent(this, LauncherActivity.class)
                .setAction(LauncherActivity.ACTION_SETTINGS).putExtra(LauncherActivity.EXTRA_RETURN_TO_GAME, true), 101));
        root.addView(gear, lp);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == Activity.RESULT_FIRST_USER) recreate();
    }

    private void addFpsCounter() {
        TextView fps = new TextView(this);
        fpsCounter = fps;
        fps.setVisibility(GameData.showFps(this) ? View.VISIBLE : View.GONE);
        fps.setTextColor(0xffffffff);
        fps.setBackgroundColor(0x99000000);
        fps.setTextSize(12);
        fps.setPadding(12, 6, 12, 6);
        fps.setFocusable(false);
        root.addView(fps, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        File stats = new File(getFilesDir(), "render-stats/render-fps.txt");
        fps.post(new Runnable() {
            @Override public void run() {
                if (finishing || isFinishing() || isDestroyed()) return;
                String value = "—";
                try {
                    String reading = new String(java.nio.file.Files.readAllBytes(stats.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (reading.matches("[0-9]+\\.[0-9]")) value = reading;
                } catch (Exception ignored) { }
                fps.setText(value + " FPS");
                fps.postDelayed(this, 1000);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = ControllerInput.key(event);
        boolean system = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_HOME;
        if (!system && input != null && input.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (input != null && input.onMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    /** Appli en arriere-plan ou ecran verrouille : jeu fige et son coupe. */
    @Override
    protected void onPause() {
        super.onPause();
        if (input != null) input.setPaused(true);
        if (audio != null) audio.setPaused(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fpsCounter != null) fpsCounter.setVisibility(GameData.showFps(this) ? View.VISIBLE : View.GONE);
        if (audio != null) audio.setPaused(false);
        if (input != null) input.setPaused(false);
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (runtime != null) runtime.stop();
        if (audio != null) audio.stop();
        if (input != null) input.close();
        super.onDestroy();
    }

    /** Resolution reelle de l'ecran. */
    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            size.set(bounds.width(), bounds.height());
        } else {
            getWindowManager().getDefaultDisplay().getRealSize(size);
        }
        if (size.x < size.y) size.set(size.y, size.x); // toujours en paysage
        return size;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
