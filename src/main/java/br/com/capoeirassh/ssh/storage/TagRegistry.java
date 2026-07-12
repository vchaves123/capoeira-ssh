package br.com.capoeirassh.ssh.storage;

import org.eclipse.swt.graphics.RGB;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Registry of known session tags and their display colors. This is the source of truth for
 * which tags exist — a tag can be created here (Tag Manager) before any session carries it —
 * and what color it renders in. Session files only store which tag NAMES they carry (see
 * {@code SessionInfo.tags}); a tag's existence/color lives here instead, shared across every
 * session the same way {@link SessionDefaults} holds shared appearance defaults.
 */
public final class TagRegistry {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".capoeira", "tags.properties");

    /** Rotated through for a new tag that isn't given an explicit color. */
    private static final RGB[] PALETTE = {
        new RGB(94, 201, 143), new RGB(74, 144, 217), new RGB(232, 132, 74), new RGB(216, 92, 122),
        new RGB(157, 124, 216), new RGB(69, 197, 192), new RGB(201, 168, 60), new RGB(108, 122, 137),
    };

    // Case-insensitive keys — a case-only-different name (e.g. "Prod" after "prod" already
    // exists) is treated as the SAME tag everywhere (exists()/create()/register()/getColor()),
    // instead of silently creating a second entry that a case-insensitive display (like
    // TagManagerDialog's sorted list) then collapses into an invisible, unmanageable orphan.
    private static final TreeMap<String, RGB> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static { load(); }

    private TagRegistry() {}

    /** All known tags, alphabetically (case-insensitive). */
    public static synchronized List<String> getAll() {
        return new ArrayList<>(tags.keySet());
    }

    public static synchronized boolean exists(String tag) {
        return tags.containsKey(tag);
    }

    public static synchronized RGB getColor(String tag) {
        RGB c = tags.get(tag);
        return c != null ? c : PALETTE[0];
    }

    /** Adds the tag with an auto-picked color if it isn't already known. No-op otherwise —
     *  safe to call on every session save so any tag typed directly into a session's Tags
     *  field is registered without the user having to pre-create it in the Tag Manager. */
    public static synchronized void register(String tag) {
        if (tag == null || tag.isBlank() || tags.containsKey(tag)) return;
        tags.put(tag, PALETTE[tags.size() % PALETTE.length]);
        save();
    }

    /** Explicitly creates a tag with a chosen color. @return false if it already exists. */
    public static synchronized boolean create(String tag, RGB color) {
        if (tag == null || tag.isBlank() || tags.containsKey(tag)) return false;
        tags.put(tag, color);
        save();
        return true;
    }

    public static synchronized void setColor(String tag, RGB color) {
        if (tag == null || tag.isBlank() || !tags.containsKey(tag)) return;
        tags.put(tag, color);
        save();
    }

    /** Renaming onto an existing tag merges them — the existing tag's color wins. */
    public static synchronized void rename(String oldName, String newName) {
        if (!tags.containsKey(oldName)) return;
        RGB color = tags.remove(oldName);
        tags.putIfAbsent(newName, color);
        save();
    }

    public static synchronized void remove(String tag) {
        if (tags.remove(tag) != null) save();
    }

    // Sane bounds against a corrupted or hand-edited tags.properties — a real registry never
    // gets anywhere close to these, but without them a crafted file could bloat the in-memory
    // registry (and every UI list rendered from it) unboundedly.
    private static final int MAX_TAGS = 1000;
    private static final int MAX_TAG_NAME_LENGTH = 200;

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) { return; }
        for (String tag : p.getProperty("_order", "").split(",")) {
            if (tag.isBlank() || tag.length() > MAX_TAG_NAME_LENGTH) continue;
            if (tags.size() >= MAX_TAGS) break;
            tags.put(tag, parseRgb(p.getProperty("color." + tag, "")));
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("_order", String.join(",", tags.keySet()));
        for (Map.Entry<String, RGB> e : tags.entrySet()) {
            RGB c = e.getValue();
            p.setProperty("color." + e.getKey(), c.red + "," + c.green + "," + c.blue);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            SecureFiles.write(FILE, baos.toByteArray());
        } catch (IOException ignored) {}
    }

    private static RGB parseRgb(String s) {
        try {
            String[] parts = s.split(",");
            return new RGB(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (Exception e) { return PALETTE[0]; }
    }
}
