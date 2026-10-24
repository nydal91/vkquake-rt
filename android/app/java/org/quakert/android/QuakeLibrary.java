package org.quakert.android;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The games the app knows about, in its internal storage:
 *   files/libre/id1/     LibreQuake (free, bundled)
 *   files/quake/id1/     the player's Quake data (pak0.pak, pak1.pak, music/...)
 *   files/quake/NAME/    mods, mission packs and map packs (a Quake "game" folder)
 * The engine gets -basedir files/quake (or files/libre) and -game NAME.
 */
final class QuakeLibrary {

    static final class Game {
        final String title;
        final File basedir;
        final String mod;       // -game, or null
        final List<String> maps; // loose maps of a map pack, to pick from (may be empty)
        Game(String title, File basedir, String mod, List<String> maps) {
            this.title = title;
            this.basedir = basedir;
            this.mod = mod;
            this.maps = maps;
        }
        String key() {
            return mod != null ? "mod:" + mod : "base:" + basedir.getName();
        }
    }

    private final File home;

    QuakeLibrary(File home) {
        this.home = home;
    }

    File libreDir() { return new File(home, "libre"); }
    File quakeDir() { return new File(home, "quake"); }

    boolean hasQuake() {
        return new File(quakeDir(), "id1/pak0.pak").isFile();
    }

    boolean hasLibre() {
        return new File(libreDir(), "id1/pak0.pak").isFile();
    }

    private static String modTitle(String dir) {
        switch (dir.toLowerCase(Locale.ROOT)) {
            case "hipnotic": return "Mission Pack 1: Scourge of Armagon";
            case "rogue": return "Mission Pack 2: Dissolution of Eternity";
            case "dopa": return "Dimension of the Past";
            case "mg1": return "Dimension of the Machine";
            default: return dir;
        }
    }

    /** .bsp files in NAME/maps (not in paks) */
    static List<String> looseMaps(File modDir) {
        List<String> out = new ArrayList<>();
        File[] files = new File(modDir, "maps").listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (n.toLowerCase(Locale.ROOT).endsWith(".bsp")) out.add(n.substring(0, n.length() - 4));
        }
        return out;
    }

    private static boolean isGameDir(File d) {
        if (!d.isDirectory()) return false;
        String[] names = d.list();
        if (names == null) return false;
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith(".pak") || l.endsWith(".pk3") || l.equals("progs.dat") || l.equals("maps") || l.equals("progs")) return true;
        }
        return false;
    }

    /** Everything the picker shows. */
    List<Game> games() {
        List<Game> out = new ArrayList<>();
        if (hasQuake()) out.add(new Game("Quake", quakeDir(), null, new ArrayList<>()));
        if (hasLibre()) out.add(new Game("LibreQuake (free)", libreDir(), null, new ArrayList<>()));
        File[] mods = quakeDir().listFiles();
        if (mods != null) {
            Arrays.sort(mods);
            for (File m : mods) {
                if (m.getName().equalsIgnoreCase("id1") || !isGameDir(m)) continue;
                String title = modTitle(m.getName()) + (hasQuake() ? "" : "  (on LibreQuake)");
                // a map pack (no paks or game code of its own): pick a map to start
                boolean mapPack = !new File(m, "progs.dat").isFile() && !new File(m, "pak0.pak").isFile();
                out.add(new Game(title, hasQuake() ? quakeDir() : libreDir(), m.getName(),
                                 mapPack ? looseMaps(m) : new ArrayList<>()));
            }
        }
        return out;
    }

    /** Without Quake, mods run on LibreQuake: link the mod folder into it. */
    void prepare(Game g) throws IOException {
        if (g.mod == null || !g.basedir.equals(libreDir())) return;
        File link = new File(libreDir(), g.mod);
        File target = new File(quakeDir(), g.mod);
        if (link.exists()) return;
        try {
            android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (android.system.ErrnoException e) {
            throw new IOException("can't link " + link + ": " + e.getMessage());
        }
    }

    // --- import

    static final class Imported {
        final String message;
        final String gameKey;      // to select it, or null
        Imported(String message, String gameKey) {
            this.message = message;
            this.gameKey = gameKey;
        }
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return n.isEmpty() ? "mod" : n;
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static boolean isZip(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return r.length() > 4 && r.readInt() == 0x504B0304;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPak(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] id = new byte[4];
            return r.read(id) == 4 && new String(id, "US-ASCII").equals("PACK");
        } catch (IOException e) {
            return false;
        }
    }

    /** Imports a .pak, .bsp or .zip given as a stream with its display name. */
    Imported importFile(InputStream in, String displayName) throws IOException {
        String name = displayName == null ? "imported" : new File(displayName).getName();
        String lower = name.toLowerCase(Locale.ROOT);

        File tmp = new File(home, "import.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            LauncherActivity.copy(in, out);
        }
        try {
            if (lower.endsWith(".zip") || isZip(tmp)) {
                if (GpuDrivers.isDriverZip(tmp) && !zipHasGameData(tmp)) {
                    String driver = new GpuDrivers(home).importZip(tmp, stripExt(name));
                    return new Imported("Added GPU driver " + driver, "driver:" + driver);
                }
                return importZip(tmp, safeName(stripExt(name)));
            }
            if (isPak(tmp)) {
                if (lower.matches("pak[0-9]\\.pak")) {
                    File dir = new File(quakeDir(), "id1");
                    mkdirs(dir);
                    move(tmp, new File(dir, lower));
                    return new Imported("Added " + lower + " to Quake", "base:quake");
                }
                // a mod's single pak
                File dir = new File(quakeDir(), safeName(stripExt(name)));
                mkdirs(dir);
                move(tmp, new File(dir, "pak0.pak"));
                return new Imported("Added " + dir.getName(), "mod:" + dir.getName());
            }
            if (lower.endsWith(".bsp")) {
                File dir = new File(quakeDir(), safeName(stripExt(name)) + "/maps");
                mkdirs(dir);
                move(tmp, new File(dir, safeName(lower)));
                return new Imported("Added map " + stripExt(name), "mod:" + dir.getParentFile().getName());
            }
            return new Imported(name + " isn't a .pak, .bsp or .zip file", null);
        } finally {
            tmp.delete();
        }
    }

    private static boolean isMarker(String lowerPath) {
        String base = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        return base.endsWith(".pak") || base.endsWith(".pk3") || base.equals("progs.dat") || base.endsWith(".bsp");
    }

    private static boolean zipHasGameData(File zip) {
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && isMarker(e.getName().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /** The folder holding a marker file is a game folder ("maps/" belongs to its parent). */
    private static String gameDirOf(String lowerPath) {
        int slash = lowerPath.lastIndexOf('/');
        String dir = slash >= 0 ? lowerPath.substring(0, slash + 1) : "";
        if (dir.endsWith("maps/") || dir.endsWith("progs/")) {
            String p = dir.substring(0, dir.length() - (dir.endsWith("maps/") ? 5 : 6));
            return p;
        }
        return dir;
    }

    /**
     * Zip layouts:
     *  - files at the top (pak0.pak, progs.dat, maps/x.bsp, or x.bsp): a mod named after the zip
     *  - one or more game folders (mymod/..., id1/..., hipnotic/...), maybe below a common folder
     */
    private Imported importZip(File zip, String zipName) throws IOException {
        // the shallowest game folder
        String gameDir = null;
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String p = e.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                names.add(p);
                if (isMarker(p)) {
                    String d = gameDirOf(p);
                    if (gameDir == null || d.length() < gameDir.length()) gameDir = d;
                }
            }
        }
        if (gameDir == null) {
            return new Imported("No Quake files (.pak, .bsp, progs.dat) in " + zipName + ".zip", null);
        }

        // where gameDir's folder sits: entries are taken relative to it
        String root;
        String fixedTarget = null;
        if (gameDir.isEmpty()) {
            root = "";
            fixedTarget = zipName.toLowerCase(Locale.ROOT).equals("id1") ? "id1" : zipName;
        } else {
            String noSlash = gameDir.substring(0, gameDir.length() - 1);
            int s = noSlash.lastIndexOf('/');
            root = s >= 0 ? noSlash.substring(0, s + 1) : "";
        }

        File staging = new File(home, "import.dir");
        LauncherActivity.deleteTree(staging);
        mkdirs(staging);
        List<String> dirs = new ArrayList<>();
        int count = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String p = e.getName().replace('\\', '/');
                String lp = p.toLowerCase(Locale.ROOT);
                if (!lp.startsWith(root)) continue;
                String rel = lp.substring(root.length());
                if (rel.contains("../") || rel.startsWith("/")) continue;
                if (fixedTarget != null) {
                    // loose .bsp files go into maps/
                    if (rel.endsWith(".bsp") && !rel.contains("/")) rel = "maps/" + rel;
                    rel = fixedTarget + "/" + rel;
                }
                String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : null;
                if (top == null) continue; // a readme next to the game folders
                if (!dirs.contains(top)) dirs.add(top);
                File out = new File(staging, rel);
                mkdirs(out.getParentFile());
                try (OutputStream o = new FileOutputStream(out)) {
                    LauncherActivity.copy(z, o);
                }
                count++;
            }
        }
        if (count == 0) {
            LauncherActivity.deleteTree(staging);
            return new Imported("Nothing to import in " + zipName + ".zip", null);
        }

        mkdirs(quakeDir());
        String key = null;
        for (String d : dirs) {
            File src = new File(staging, d);
            File dst = new File(quakeDir(), safeName(d));
            if (d.equals("id1")) {
                // merge: keep the other paks
                mergeInto(src, dst);
                if (key == null) key = "base:quake";
            } else {
                LauncherActivity.deleteTree(dst);
                if (!src.renameTo(dst)) throw new IOException("can't create " + dst);
                if (key == null || key.startsWith("base:")) key = "mod:" + dst.getName();
            }
        }
        LauncherActivity.deleteTree(staging);
        return new Imported("Added " + String.join(", ", dirs), key);
    }

    private static void mergeInto(File src, File dst) throws IOException {
        mkdirs(dst);
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) {
                mergeInto(f, t);
            } else {
                move(f, t);
            }
        }
    }

    static void mkdirs(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("can't create " + d);
    }

    private static void move(File from, File to) throws IOException {
        to.delete();
        if (!from.renameTo(to)) throw new IOException("can't move to " + to);
    }
}
