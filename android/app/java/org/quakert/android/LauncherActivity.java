package org.quakert.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Starts before the game:
 * - extracts the bundled data (ray tracing data in ovrd/, LibreQuake) to
 *   internal storage once per install/update, as the engine reads plain files;
 * - imports .pak / .bsp / .zip files ("Open with" from a file manager, or
 *   "Add file..."): Quake's own paks, mission packs, mods and maps, see QuakeLibrary;
 * - if the previous run failed, shows its error and log so it can be copied;
 * - lets the player choose a game or mod, then launches GameActivity.
 */
public class LauncherActivity extends Activity {

    static final String TAG = "QuakeRT";
    static final String PREFS = "quakert";
    private static final String PREF_DATA_STAMP = "data_stamp";

    // written by the engine (see Quake/android_sys.c) and by GameActivity
    static final String LOG_FILE = "quake.log";
    static final String ERROR_FILE = "error.txt";
    static final String JAVA_CRASH_FILE = "java_crash.txt";
    static final String RUNNING_FILE = "running.txt";

    private static final String BUNDLED_CHECK = "libre/id1/pak0.pak";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File home = getFilesDir();

        try {
            extractDataIfNeeded(home);
        } catch (IOException e) {
            Log.e(TAG, "Can't extract game data", e);
            writeFile(new File(home, JAVA_CRASH_FILE), "Can't extract game data:\n" + stackTrace(e));
        }

        library = new QuakeLibrary(home);
        drivers = new GpuDrivers(home);
        if (savedInstanceState == null) {
            // not again when the activity is recreated, e.g. on rotation
            importFromIntent(getIntent());
        }

        // "Show log" app shortcut (long-press the icon)
        if (ACTION_SHOW_LOG.equals(getIntent() != null ? getIntent().getAction() : null)) {
            showReport(collectReport(home, true), "Game log");
            return;
        }

        String report = collectReport(home, false);
        if (report != null) {
            showReport(report, "The game stopped last time");
        } else {
            showPicker();
        }
    }

    // --- game picker

    private static final String PREF_LAST_GAME = "last_game";
    private static final int REQUEST_ADD = 1;

    private QuakeLibrary library;
    private AlertDialog picker;

    private void showPicker() {
        clearReport(getFilesDir());
        final List<QuakeLibrary.Game> games = library.games();
        if (games.isEmpty()) {
            Toast.makeText(this, "No game data found: add Quake's pak0.pak", Toast.LENGTH_LONG).show();
        }

        // last played first
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_GAME, null);
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).key().equals(last)) {
                games.add(0, games.remove(i));
                break;
            }
        }

        // last entry: the GPU driver setting
        String[] titles = new String[games.size() + 1];
        for (int i = 0; i < games.size(); i++) {
            titles[i] = games.get(i).title;
        }
        GpuDrivers.Driver drv = drivers.find(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GPU_DRIVER, null));
        titles[games.size()] = "\u2699  GPU driver: " + (drv != null ? drv.title : "System");

        if (picker != null) {
            picker.dismiss();
        }
        picker = new AlertDialog.Builder(this)
            .setTitle("Choose a game")
            .setItems(titles, (d, which) -> {
                if (which < games.size()) {
                    chooseMap(games.get(which));
                } else {
                    showDriverPicker();
                }
            })
            .setNeutralButton("Add file\u2026", null)
            .setNegativeButton("Quit", (d, w) -> finish())
            .setCancelable(false)
            .create();
        picker.show();
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQUEST_ADD);
        });
        ListView list = picker.getListView();
        if (list != null) {
            list.setOnItemLongClickListener((parent, view, pos, id) -> {
                if (pos < games.size()) {
                    confirmRemove(games.get(pos));
                }
                return true;
            });
        }
    }

    // --- GPU driver

    private static final String PREF_GPU_DRIVER = "gpu_driver";
    private GpuDrivers drivers;

    private void showDriverPicker() {
        if (picker != null) {
            picker.dismiss();
        }
        final List<GpuDrivers.Driver> list = drivers.list();
        String current = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GPU_DRIVER, null);
        String[] titles = new String[list.size() + 1];
        int checked = 0;
        titles[0] = "System driver";
        for (int i = 0; i < list.size(); i++) {
            titles[i + 1] = list.get(i).title;
            if (list.get(i).dir.getName().equals(current)) {
                checked = i + 1;
            }
        }
        picker = new AlertDialog.Builder(this)
            .setTitle("GPU driver")
            .setSingleChoiceItems(titles, checked, (d, which) -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_GPU_DRIVER, which == 0 ? null : list.get(which - 1).dir.getName()).apply();
            })
            .setPositiveButton("Done", (d, w) -> showPicker())
            .setNeutralButton("Import driver\u2026", null)
            .setCancelable(false)
            .create();
        picker.show();
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_ADD_DRIVER);
        });
        ListView lv = picker.getListView();
        if (lv != null) {
            lv.setOnItemLongClickListener((parent, view, pos, id) -> {
                if (pos == 0) {
                    return true;
                }
                final GpuDrivers.Driver g = list.get(pos - 1);
                new AlertDialog.Builder(this)
                    .setTitle("Remove " + g.title + "?")
                    .setPositiveButton("Remove", (d, w) -> {
                        deleteTree(g.dir);
                        showDriverPicker();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }
    }

    private static final int REQUEST_ADD_DRIVER = 2;

    private void confirmRemove(final QuakeLibrary.Game g) {
        final File target = g.mod != null ? new File(library.quakeDir(), g.mod)
                          : g.basedir.equals(library.quakeDir()) ? new File(library.quakeDir(), "id1") : null;
        if (target == null) {
            Toast.makeText(this, "Built-in games can't be removed", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Remove " + g.title + "?")
            .setMessage(g.mod == null ? "This deletes the imported Quake data (id1)." : null)
            .setPositiveButton("Remove", (d, w) -> {
                deleteTree(target);
                if (g.mod != null) {
                    new File(library.libreDir(), g.mod).delete();
                }
                showPicker();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_ADD_DRIVER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                importUri(data.getData());
            }
            showDriverPicker();
            return;
        }
        if (request != REQUEST_ADD || result != RESULT_OK || data == null) {
            return;
        }
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                importUri(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            importUri(data.getData());
        }
        showPicker();
    }

    /** A map pack without a start map: let the player pick a map. */
    private void chooseMap(final QuakeLibrary.Game g) {
        if (g.maps.isEmpty() || g.maps.contains("start")) {
            startGame(g, null);
            return;
        }
        if (g.maps.size() == 1) {
            startGame(g, g.maps.get(0));
            return;
        }
        final String[] titles = new String[g.maps.size() + 1];
        titles[0] = "Main menu";
        for (int i = 0; i < g.maps.size(); i++) {
            titles[i + 1] = g.maps.get(i);
        }
        if (picker != null) {
            picker.dismiss();
        }
        picker = new AlertDialog.Builder(this)
            .setTitle(g.title)
            .setItems(titles, (d, which) -> startGame(g, which == 0 ? null : g.maps.get(which - 1)))
            .setNegativeButton("Back", (d, w) -> showPicker())
            .setCancelable(false)
            .create();
        picker.show();
    }

    private void startGame(QuakeLibrary.Game g, String map) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_GAME, g.key()).apply();
        try {
            library.prepare(g);
        } catch (IOException e) {
            Log.e(TAG, "Can't prepare " + g.title, e);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra("basedir", g.basedir.getAbsolutePath());
        if (g.mod != null) {
            i.putExtra("mod", g.mod);
        }
        if (map != null) {
            i.putExtra("map", map);
        }
        GpuDrivers.Driver drv = drivers.find(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GPU_DRIVER, null));
        if (drv != null) {
            i.putExtra("gpu_driver_dir", drv.dir.getAbsolutePath() + "/");
            i.putExtra("gpu_driver_lib", drv.library);
        }
        // removed by the engine on a clean exit
        writeFile(new File(getFilesDir(), RUNNING_FILE), "running\n");
        startActivity(i);
        finish();
    }

    private void startGame() {
        showPicker();
    }

    // --- error report

    /** Returns a report if the previous run ended with an error, or never reached a clean exit. */
    static final String ACTION_SHOW_LOG = "org.quakert.android.SHOW_LOG";

    private String collectReport(File home, boolean always) {
        String error = readFile(new File(home, ERROR_FILE), 4000);
        String java = readFile(new File(home, JAVA_CRASH_FILE), 8000);
        boolean unclean = new File(home, RUNNING_FILE).isFile();

        if (!always && error == null && java == null && !unclean) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Quake RT ").append(versionLine()).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("SoC: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "?").append("\n\n");
        if (error != null) {
            sb.append("Error:\n").append(error.trim()).append("\n\n");
        }
        if (java != null) {
            sb.append("Java:\n").append(java.trim()).append("\n\n");
        }
        if (!always && error == null && java == null) {
            sb.append("The game closed unexpectedly (native crash).\n\n");
        }
        String log = readTail(new File(home, LOG_FILE), always ? 20000 : 6000);
        if (log != null) {
            sb.append("Log (end):\n").append(log.trim()).append('\n');
        }
        return sb.toString();
    }

    private String versionLine() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void showReport(final String report, String title) {
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Start game", (d, w) -> startGame())
            .setNegativeButton("Close", (d, w) -> { clearReport(getFilesDir()); finish(); })
            .setNeutralButton("Copy", null)
            .setCancelable(false)
            .create();
        dlg.show();
        // keep the dialog open after copying
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Quake RT error report", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
    }

    private static void clearReport(File home) {
        new File(home, ERROR_FILE).delete();
        new File(home, JAVA_CRASH_FILE).delete();
        new File(home, RUNNING_FILE).delete();
    }

    // --- import ("Open with" from a file manager, or "Add file...")

    private void importFromIntent(Intent intent) {
        Uri uri = intent != null ? intent.getData() : null;
        if (uri != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            importUri(uri);
        }
    }

    private void importUri(Uri uri) {
        String name = queryDisplayName(uri);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("can't open " + uri);
            }
            QuakeLibrary.Imported r = library.importFile(in, name);
            if (r.gameKey != null && r.gameKey.startsWith("driver:")) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_GPU_DRIVER, r.gameKey.substring(7)).apply();
            } else if (r.gameKey != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_GAME, r.gameKey).apply();
            }
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Can't import " + uri, e);
            Toast.makeText(this, "Couldn't import " + (name != null ? name : "the file"), Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return uri.getLastPathSegment();
        }
        try (Cursor c = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't query name of " + uri, e);
        }
        return null;
    }

    // --- data extraction

    /** Extracts assets once per APK install/update. */
    private void extractDataIfNeeded(File home) throws IOException {
        long stamp;
        try {
            stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            stamp = -1;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (stamp != -1 && prefs.getLong(PREF_DATA_STAMP, 0) == stamp
                && new File(home, BUNDLED_CHECK).isFile()) {
            return;
        }

        long t = System.currentTimeMillis();
        copyAssetTree(getAssets(), "", home);
        prefs.edit().putLong(PREF_DATA_STAMP, stamp).apply();
        Log.i(TAG, "Extracted game data in " + (System.currentTimeMillis() - t) + " ms");
    }

    private static void copyAssetTree(AssetManager am, String path, File dst) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("can't create " + parent);
            }
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            return;
        }

        for (String child : children) {
            // skip folders the build tools add to every APK
            if (path.isEmpty() && (child.equals("images") || child.equals("webkit"))) {
                continue;
            }
            String childPath = path.isEmpty() ? child : path + "/" + child;
            copyAssetTree(am, childPath, new File(dst, child));
        }
    }

    // --- helpers

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    static String readSmall(File f) {
        return readFile(f, 4096);
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    static void writeFile(File f, String text) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Can't write " + f, e);
        }
    }

    private static String readFile(File f, int maxBytes) {
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), maxBytes)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return null;
        }
    }

    private static String readTail(File f, int maxBytes) {
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - maxBytes);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[maxBytes];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
