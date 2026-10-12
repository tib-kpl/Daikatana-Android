package fr.tibkpl.daikatana;

import android.Manifest;
import android.app.Activity;
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

    private TextView status;
    private Button pickButton;
    private Button playButton;

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
        forcePick = ACTION_CHANGE_FOLDER.equals(getIntent().getAction());
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

        if (dataDir != null && !forcePick && pickError == null) {
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
            playButton.setOnClickListener(v -> launchGame(dataDir));
            playButton.requestFocus();
        } else {
            playButton.setVisibility(Button.GONE);
            pickButton.requestFocus();
        }
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
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK || data == null) return;

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
