package fr.tibkpl.daikatana;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;

/**
 * Ecran de demarrage : verifie que les fichiers du jeu sont presents au chemin memorise.
 * Si oui, lance directement le jeu ; sinon demande a l'utilisateur ou ils se trouvent.
 */
public class LauncherActivity extends Activity {

    static final String ACTION_CHANGE_FOLDER = "fr.tibkpl.daikatana.CHANGE_FOLDER";

    private static final int REQ_PICK_FOLDER = 1;
    private static final int REQ_PERMISSION = 2;
    static final String ACTION_SETTINGS = "fr.tibkpl.daikatana.SETTINGS";
    static final String EXTRA_RETURN_TO_GAME = "return_to_game";

    private TextView status;
    private Button pickButton;
    private Button playButton;
    private Button restartButton;
    private Button languageButton;
    private Button resolutionButton;

    /** Vrai quand on vient du raccourci "Changer le dossier" : pas de lancement auto. */
    private boolean forcePick;
    /** Message d'erreur du dernier choix de dossier, affiche au prochain refresh. */
    private String pickError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        status = findViewById(R.id.status);
        pickButton = findViewById(R.id.pick);
        playButton = findViewById(R.id.play);
        restartButton = findViewById(R.id.restart);
        restartButton.setOnClickListener(v -> { setResult(Activity.RESULT_FIRST_USER); finish(); });
        languageButton = findViewById(R.id.language);
        languageButton.setOnClickListener(v -> cycleLanguage());
        updateLanguageButton();
        resolutionButton = findViewById(R.id.resolution);
        resolutionButton.setOnClickListener(v -> openGraphicsSettings());
        findViewById(R.id.controller).setOnClickListener(v -> ControllerSettings.show(this));
        updateResolutionButton();
        forcePick = ACTION_CHANGE_FOLDER.equals(getIntent().getAction()) || ACTION_SETTINGS.equals(getIntent().getAction());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!hasStorageAccess()) {
            status.setText(R.string.status_need_permission);
            playButton.setVisibility(Button.GONE);
            pickButton.setText(R.string.btn_grant);
            pickButton.setOnClickListener(v -> requestStorageAccess());
            return;
        }

        pickButton.setText(R.string.btn_pick);
        pickButton.setOnClickListener(v -> openFolderPicker());

        String saved = GameData.getSavedPath(this);
        File dataDir = saved == null ? null : GameData.findDataDir(new File(saved));

        if (dataDir != null && !forcePick && pickError == null && !getIntent().getBooleanExtra(EXTRA_RETURN_TO_GAME, false)) {
            launchGame(dataDir);
            return;
        }

        if (pickError != null) {
            status.setText(pickError);
        } else if (dataDir != null) {
            status.setText(getString(R.string.status_found, dataDir.getAbsolutePath()));
        } else if (saved != null) {
            status.setText(getString(R.string.status_saved_missing, saved,
                    String.join(", ", GameData.missingFiles(new File(saved)))));
        } else {
            status.setText(getString(R.string.status_no_folder,
                    String.join(", ", GameData.REQUIRED_FILES)));
        }

        if (dataDir != null) {
            playButton.setVisibility(Button.VISIBLE);
            boolean returnToGame = getIntent().getBooleanExtra(EXTRA_RETURN_TO_GAME, false);
            playButton.setText(returnToGame ? R.string.btn_return_game : R.string.btn_play);
            playButton.setOnClickListener(v -> { if (returnToGame) finish(); else launchGame(dataDir); });
            playButton.requestFocus();
        } else {
            playButton.setVisibility(Button.GONE);
            pickButton.requestFocus();
        }
    }

    /** Langue suivante du jeu (menus et textes traduits par libdkbridge.so). */
    private void cycleLanguage() {
        String current = GameData.getLanguage(this);
        int i = java.util.Arrays.asList(GameData.LANGUAGES).indexOf(current);
        GameData.saveLanguage(this, GameData.LANGUAGES[(i + 1) % GameData.LANGUAGES.length]);
        updateLanguageButton();
        resolutionButton = findViewById(R.id.resolution);
        resolutionButton.setOnClickListener(v -> openGraphicsSettings());
        findViewById(R.id.controller).setOnClickListener(v -> ControllerSettings.show(this));
        updateResolutionButton();
    }

    private void updateLanguageButton() {
        languageButton.setText(getString(R.string.btn_language, GameData.languageName(GameData.getLanguage(this))));
    }

    private void openGraphicsSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView driverLabel = new TextView(this);
        driverLabel.setText(R.string.graphics_driver);
        content.addView(driverLabel);
        Spinner driver = new Spinner(this);
        String[] rendererIds = {"auto", "turnip", "software"};
        String[] rendererLabels = getResources().getStringArray(R.array.graphics_drivers);
        driver.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rendererLabels));
        for (int i = 0; i < rendererIds.length; i++) if (rendererIds[i].equals(GameData.getRenderer(this))) driver.setSelection(i);
        content.addView(driver);
        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText(R.string.graphics_resolution);
        content.addView(resolutionLabel);
        Spinner resolution = new Spinner(this);
        resolution.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.graphics_resolutions)));
        for (int i = 0; i < GameData.RENDER_HEIGHTS.length; i++)
            if (GameData.RENDER_HEIGHTS[i] == GameData.getRenderHeight(this)) resolution.setSelection(i);
        content.addView(resolution);
        CheckBox fps = new CheckBox(this);
        fps.setText(R.string.graphics_fps);
        fps.setChecked(GameData.showFps(this));
        content.addView(fps);
        TextView help = new TextView(this);
        help.setText(R.string.graphics_help);
        help.setTextSize(12);
        content.addView(help);
        new AlertDialog.Builder(this).setTitle(R.string.graphics_title).setView(content)
                .setPositiveButton(R.string.graphics_apply, (d, which) -> {
                    GameData.saveGraphics(this, GameData.RENDER_HEIGHTS[resolution.getSelectedItemPosition()],
                            rendererIds[driver.getSelectedItemPosition()], fps.isChecked());
                    updateResolutionButton();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateResolutionButton() {
        resolutionButton.setText(getString(R.string.graphics_summary, GameData.getRenderHeight(this),
                getResources().getStringArray(R.array.graphics_drivers)[
                        "software".equals(GameData.getRenderer(this)) ? 2 : "turnip".equals(GameData.getRenderer(this)) ? 1 : 0]));
    }

    private void launchGame(File dataDir) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_DATA_DIR, dataDir.getAbsolutePath());
        startActivity(intent);
        finish();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_PICK_FOLDER) return;

        File picked = GameData.treeUriToFile(data.getData());
        if (picked == null) {
            pickError = getString(R.string.status_pick_unsupported);
            return;
        }
        File dataDir = GameData.findDataDir(picked);
        if (dataDir == null) {
            pickError = getString(R.string.status_pick_invalid, picked.getAbsolutePath(),
                    String.join(", ", GameData.missingFiles(picked)));
            return;
        }
        GameData.savePath(this, dataDir);
        pickError = null;
        forcePick = false;
        // onResume() -> refresh() lance le jeu avec le nouveau chemin
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }
}
