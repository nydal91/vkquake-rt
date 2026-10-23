package org.quakert.android;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;

/**
 * Runs the engine (libmain.so) through SDL. Started by LauncherActivity,
 * which prepares the data and passes the game folder.
 */
public class GameActivity extends SDLActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final File home = getFilesDir();

        // Java exceptions (also those thrown into the SDL thread) end up in a
        // report that LauncherActivity shows on the next start
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            LauncherActivity.writeFile(new File(home, LauncherActivity.JAVA_CRASH_FILE),
                "Thread: " + thread.getName() + "\n" + LauncherActivity.stackTrace(e));
            if (previous != null) {
                previous.uncaughtException(thread, e);
            }
        });

        try {
            // the engine changes to $HOME: ovrd/ (ray tracing data) and the logs are there
            Os.setenv("HOME", home.getAbsolutePath(), true);

            // custom GPU driver, loaded by the engine through libadrenotools
            String driverDir = getIntent() != null ? getIntent().getStringExtra("gpu_driver_dir") : null;
            String driverLib = getIntent() != null ? getIntent().getStringExtra("gpu_driver_lib") : null;
            if (driverDir != null && driverLib != null) {
                File tmp = new File(getCodeCacheDir(), "adrenotools");
                tmp.mkdirs();
                Os.setenv("QUAKE_GPU_DRIVER_DIR", driverDir, true);
                Os.setenv("QUAKE_GPU_DRIVER_LIB", driverLib, true);
                Os.setenv("QUAKE_GPU_DRIVER_TMP", tmp.getAbsolutePath() + "/", true);
                Os.setenv("QUAKE_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir + "/", true);
            } else {
                Os.unsetenv("QUAKE_GPU_DRIVER_DIR");
                Os.unsetenv("QUAKE_GPU_DRIVER_LIB");
            }
        } catch (ErrnoException e) {
            Log.e(LauncherActivity.TAG, "Can't set the environment", e);
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "RayTracedGL1", "main" };
    }

    @Override
    protected String[] getArguments() {
        String basedir = getIntent() != null ? getIntent().getStringExtra("basedir") : null;
        if (basedir == null) {
            basedir = new File(getFilesDir(), "libre").getAbsolutePath();
        }
        ArrayList<String> args = new ArrayList<>();
        args.add("-basedir");
        args.add(basedir);
        args.add("-current"); // the whole screen
        String mod = getIntent() != null ? getIntent().getStringExtra("mod") : null;
        if (mod != null) {
            args.add("-game");
            args.add(mod);
        }
        String map = getIntent() != null ? getIntent().getStringExtra("map") : null;
        if (map != null) {
            args.add("+map");
            args.add(map);
        }
        Log.i(LauncherActivity.TAG, "Arguments: " + args);
        return args.toArray(new String[0]);
    }
}
