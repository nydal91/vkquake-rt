package org.quakert.android;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Custom Vulkan drivers (e.g. Mesa Turnip for Adreno), in the zip format
 * that adrenotools-based apps use: a meta.json naming the driver library
 * ("libraryName") and the .so itself. Stored in files/gpu_drivers/NAME/,
 * loaded by the engine through libadrenotools (see Quake/rt_vkdriver.c).
 */
final class GpuDrivers {

    static final class Driver {
        final File dir;
        final String library;   // file name of the .so
        final String title;     // shown in the picker
        Driver(File dir, String library, String title) {
            this.dir = dir;
            this.library = library;
            this.title = title;
        }
    }

    private final File root;

    GpuDrivers(File home) {
        root = new File(home, "gpu_drivers");
    }

    /** True if a zip holds a driver (a .so library) rather than game files. */
    static boolean isDriverZip(File zip) {
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".so") || n.endsWith("meta.json")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    List<Driver> list() {
        List<Driver> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            Driver drv = load(d);
            if (drv != null) out.add(drv);
        }
        return out;
    }

    Driver find(String name) {
        if (name == null) return null;
        return load(new File(root, name));
    }

    private static Driver load(File dir) {
        if (!dir.isDirectory()) return null;
        String lib = null, title = dir.getName();
        File meta = new File(dir, "meta.json");
        if (meta.isFile()) {
            try {
                JSONObject j = new JSONObject(LauncherActivity.readSmall(meta));
                lib = j.optString("libraryName", null);
                String name = j.optString("name", "");
                String version = j.optString("driverVersion", "");
                if (!name.isEmpty()) title = name;
                if (!version.isEmpty()) title += "  (" + version + ")";
            } catch (Exception ignored) {
            }
        }
        if (lib == null || !new File(dir, lib).isFile()) {
            lib = null;
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    if (f.getName().endsWith(".so")) {
                        lib = f.getName();
                        break;
                    }
                }
            }
        }
        return lib == null ? null : new Driver(dir, lib, title);
    }

    /** Extracts a driver zip; returns its folder name. */
    String importZip(File zip, String baseName) throws IOException {
        String name = baseName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) name = "driver";
        File dir = new File(root, name);
        LauncherActivity.deleteTree(dir);
        if (!dir.mkdirs()) throw new IOException("can't create " + dir);

        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String file = new File(e.getName()).getName();
                String lower = file.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".so") || lower.equals("meta.json"))) continue;
                try (OutputStream o = new FileOutputStream(new File(dir, file))) {
                    LauncherActivity.copy(z, o);
                }
            }
        }
        if (load(dir) == null) {
            LauncherActivity.deleteTree(dir);
            throw new IOException("no driver library in " + baseName);
        }
        return name;
    }
}
