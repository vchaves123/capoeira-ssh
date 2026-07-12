package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionIconType;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;

import java.util.HashMap;
import java.util.Map;

/**
 * Central cache of session icons for SWT.
 *
 * org.eclipse.swt.graphics.Image allocates a native OS bitmap handle and must be
 * disposed explicitly. This class loads each icon once (per size) and reuses the
 * same instance; call {@link #disposeAll()} once at application shutdown, before
 * the Display is disposed.
 */
public final class SessionIconRegistry {

    // Composite key "TYPE_size" (e.g. "LINUX_16") -> already-loaded Image
    private static final Map<String, Image> CACHE = new HashMap<>();

    private SessionIconRegistry() {}

    /** Returns the Image for the given type and size (16, 24 or 32), loading and
     *  caching it from the classpath on first call. */
    public static synchronized Image get(SessionIconType type, int size) {
        String cacheKey = type.name() + "_" + size;
        Image cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isDisposed()) return cached;

        String path = type.getIconPath(size);
        Image image;
        try (var stream = SessionIconRegistry.class.getResourceAsStream(path)) {
            if (stream == null)
                throw new IllegalStateException("Icon not found on classpath: " + path);
            image = new Image(Display.getCurrent(), stream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load icon " + path, e);
        }

        CACHE.put(cacheKey, image);
        return image;
    }

    /** Disposes every cached image. Call once at application shutdown. */
    public static synchronized void disposeAll() {
        for (Image image : CACHE.values()) {
            if (image != null && !image.isDisposed()) image.dispose();
        }
        CACHE.clear();
    }
}
