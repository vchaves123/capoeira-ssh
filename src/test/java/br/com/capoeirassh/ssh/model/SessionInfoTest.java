package br.com.capoeirassh.ssh.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SessionInfo#copy()} manually lists every field to copy — the exact shape of bug that
 * silently rots as the class grows: a new field added to {@code SessionInfo} but forgotten in
 * {@code copy()} means edits to a "copy" (e.g. {@code SessionDialog} staging in-progress changes)
 * silently fail to carry that field over, or worse, an object meant to be independent still
 * shares a reference back to the original (the same failure class as the build-30
 * {@code TerminalCell} aliasing bug, and the reason this whole test exists).
 *
 * <p>Rather than manually asserting each field by name (itself prone to "forgot to add the new
 * field to the test too"), this drives the check via reflection over every declared instance
 * field — so a future field added to {@code SessionInfo} is automatically covered without
 * anyone remembering to update this test.
 */
class SessionInfoTest {

    @Test
    void copy_copiesEveryDeclaredField() throws Exception {
        SessionInfo source = new SessionInfo();
        List<Field> fields = instanceFields();
        for (Field f : fields) setDistinctNonDefaultValue(f, source);

        SessionInfo dest = source.copy();

        for (Field f : fields) {
            Object srcVal  = f.get(source);
            Object destVal = f.get(dest);
            if (f.getType() == List.class) continue; // checked separately below
            assertEquals(srcVal, destVal,
                    "field '" + f.getName() + "' was not copied correctly by copy() — expected "
                  + srcVal + " but the copy has " + destVal);
        }
    }

    @Test
    void copy_producesAnIndependentTagsList_notASharedReference() {
        SessionInfo source = new SessionInfo();
        source.tags = new ArrayList<>(List.of("prod", "db"));

        SessionInfo dest = source.copy();
        assertNotSame(source.tags, dest.tags, "copy() must give the copy its OWN tags list");
        assertEquals(source.tags, dest.tags, "contents must match right after copy()");

        // The actual aliasing check: mutating one list afterward must never affect the other —
        // this is exactly the class of bug (build 30) this whole test file is named after.
        source.tags.add("added-to-source-only");
        assertEquals(2, dest.tags.size(), "adding to the source's tags after copy() must not leak into the copy");

        dest.tags.add("added-to-dest-only");
        assertEquals(3, source.tags.size(), "adding to the copy's tags must not leak back into the source");
    }

    @Test
    void copy_id_isCopiedNotRegenerated() {
        SessionInfo source = new SessionInfo();
        String originalId = source.id;
        SessionInfo dest = source.copy();
        assertEquals(originalId, dest.id, "copy() must preserve the same id — it's used as the on-disk filename");
    }

    @Test
    void connectionSummary_ssh_omitsPortWhenDefault() {
        SessionInfo s = new SessionInfo();
        s.host = "example.com";
        s.port = 22;
        assertEquals("example.com", s.connectionSummary());
    }

    @Test
    void connectionSummary_ssh_includesNonDefaultPort() {
        SessionInfo s = new SessionInfo();
        s.host = "example.com";
        s.port = 2222;
        assertEquals("example.com:2222", s.connectionSummary());
    }

    @Test
    void connectionSummary_serial_showsPortAndBaud() {
        SessionInfo s = new SessionInfo();
        s.connectionType = SessionInfo.ConnectionType.SERIAL;
        s.serialPortName = "COM3";
        s.serialBaudRate = 9600;
        assertEquals("COM3 @ 9600", s.connectionSummary());
    }

    @Test
    void label_serial_fallsBackToConnectionSummary_whenNameBlank() {
        SessionInfo s = new SessionInfo();
        s.connectionType = SessionInfo.ConnectionType.SERIAL;
        s.serialPortName = "COM3";
        s.serialBaudRate = 9600;
        s.name = "";
        assertEquals("COM3 @ 9600", s.label());
    }

    @Test
    void label_serial_prefersExplicitName() {
        SessionInfo s = new SessionInfo();
        s.connectionType = SessionInfo.ConnectionType.SERIAL;
        s.serialPortName = "COM3";
        s.name = "Bench scope";
        assertEquals("Bench scope", s.label());
    }

    // -----------------------------------------------------------------------
    // Reflection plumbing
    // -----------------------------------------------------------------------

    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : SessionInfo.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            fields.add(f);
        }
        return fields;
    }

    private static int counter = 0;

    private static void setDistinctNonDefaultValue(Field f, SessionInfo target) throws Exception {
        Class<?> type = f.getType();
        counter++;
        if (type == String.class) {
            f.set(target, "test-value-" + f.getName() + "-" + counter);
        } else if (type == int.class) {
            f.set(target, 1000 + counter);
        } else if (type == boolean.class) {
            f.set(target, !((boolean) f.get(target))); // flip from the default
        } else if (type == List.class) {
            f.set(target, new ArrayList<>(List.of("tag" + counter)));
        } else if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            Object current = f.get(target);
            for (Object c : constants) {
                if (!c.equals(current)) { f.set(target, c); break; }
            }
        } else {
            fail("SessionInfoTest doesn't know how to generate a test value for field '"
                + f.getName() + "' of type " + type + " — add a case above so copy_copiesEveryDeclaredField() "
                + "actually exercises it instead of silently skipping it");
        }
    }
}
