package br.com.capoeirassh.ssh.storage;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link TagRegistry}: CRUD (create/register/setColor/rename/remove), case-insensitive
 * tag names, and the {@code tags.properties} persistence round-trip — none of which had a test
 * before, same as the file-backed classes it mirrors ({@code SessionStorage},
 * {@code AppearanceSettings}, {@code SessionDefaults}).
 *
 * <p>Unlike those classes, {@code TagRegistry}'s in-memory state is a {@code static final} map
 * that {@code load()} only ever adds to (never clears) — safe for its real one-shot
 * {@code static { load(); }} at class-load time, but each test here must reset both the map
 * (via reflection) and the on-disk file itself, or state would leak across tests.
 *
 * <p>Safety mechanism: same as every other storage test in this project — {@code user.home} is
 * redirected to {@code target/test-home} for the forked test JVM (see pom.xml), and
 * {@code TagRegistry.FILE} is a {@code static final Path} resolved from it at class-load time.
 * {@link #verifyRedirectedAndReset()} asserts the redirection took effect before touching
 * anything, aborting the whole class otherwise.
 */
class TagRegistryTest {

    private static Path file;

    @BeforeEach
    void verifyRedirectedAndReset() throws Exception {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to touch a real tags.properties. Run via `mvn test`.");
        file = Path.of(home, ".capoeira", "tags.properties");
        Files.deleteIfExists(file);
        clearInMemoryTags();
    }

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(file);
        clearInMemoryTags();
    }

    @SuppressWarnings("unchecked")
    private static void clearInMemoryTags() throws Exception {
        Field f = TagRegistry.class.getDeclaredField("tags");
        f.setAccessible(true);
        ((Map<String, RGB>) f.get(null)).clear();
    }

    private static void reload() throws Exception {
        Method m = TagRegistry.class.getDeclaredMethod("load");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static RGB paletteFirst() throws Exception {
        Field f = TagRegistry.class.getDeclaredField("PALETTE");
        f.setAccessible(true);
        return ((RGB[]) f.get(null))[0];
    }

    // -----------------------------------------------------------------------
    // Basic CRUD
    // -----------------------------------------------------------------------

    @Test
    void create_addsTagWithGivenColor() {
        RGB color = new RGB(10, 20, 30);
        assertTrue(TagRegistry.create("prod", color));
        assertTrue(TagRegistry.exists("prod"));
        assertEquals(color, TagRegistry.getColor("prod"));
        assertTrue(TagRegistry.getAll().contains("prod"));
    }

    @Test
    void create_returnsFalse_andDoesNotOverwriteColor_ifTagAlreadyExists() {
        RGB first  = new RGB(10, 20, 30);
        RGB second = new RGB(40, 50, 60);
        assertTrue(TagRegistry.create("prod", first));
        assertFalse(TagRegistry.create("prod", second), "creating an already-known tag must fail");
        assertEquals(first, TagRegistry.getColor("prod"), "the original color must survive the rejected create()");
    }

    @Test
    void getColor_ofUnknownTag_returnsDefaultPaletteColor_notNullOrException() throws Exception {
        assertEquals(paletteFirst(), TagRegistry.getColor("never-created"));
    }

    @Test
    void register_autoAssignsAColor_forANewTag() {
        TagRegistry.register("staging");
        assertTrue(TagRegistry.exists("staging"));
        assertNotNull(TagRegistry.getColor("staging"));
    }

    @Test
    void register_isNoOp_andDoesNotOverwriteColor_ifTagAlreadyExists() {
        RGB explicit = new RGB(1, 2, 3);
        TagRegistry.create("staging", explicit);
        TagRegistry.register("staging"); // must not reassign a palette color over the explicit one
        assertEquals(explicit, TagRegistry.getColor("staging"));
    }

    @Test
    void setColor_updatesAnExistingTag() {
        TagRegistry.create("prod", new RGB(1, 1, 1));
        RGB updated = new RGB(9, 9, 9);
        TagRegistry.setColor("prod", updated);
        assertEquals(updated, TagRegistry.getColor("prod"));
    }

    @Test
    void setColor_isNoOp_ifTagDoesNotExist() {
        TagRegistry.setColor("ghost", new RGB(9, 9, 9));
        assertFalse(TagRegistry.exists("ghost"), "setColor() must never create a tag that doesn't exist");
    }

    @Test
    void remove_deletesAnExistingTag() {
        TagRegistry.create("prod", new RGB(1, 2, 3));
        TagRegistry.remove("prod");
        assertFalse(TagRegistry.exists("prod"));
    }

    @Test
    void remove_isNoOp_ifTagDoesNotExist() {
        assertDoesNotThrow(() -> TagRegistry.remove("never-existed"));
    }

    // -----------------------------------------------------------------------
    // Rename
    // -----------------------------------------------------------------------

    @Test
    void rename_movesEntryToNewName_preservingColor() {
        RGB color = new RGB(5, 6, 7);
        TagRegistry.create("prod", color);
        TagRegistry.rename("prod", "production");
        assertFalse(TagRegistry.exists("prod"));
        assertTrue(TagRegistry.exists("production"));
        assertEquals(color, TagRegistry.getColor("production"));
    }

    @Test
    void rename_isNoOp_ifOldNameDoesNotExist() {
        TagRegistry.rename("ghost", "whatever");
        assertFalse(TagRegistry.exists("whatever"));
    }

    @Test
    void rename_ontoAnExistingTag_merges_keepingTheEXISTINGTargetsColor() {
        RGB sourceColor = new RGB(1, 1, 1);
        RGB targetColor = new RGB(2, 2, 2);
        TagRegistry.create("prod", sourceColor);
        TagRegistry.create("production", targetColor);
        TagRegistry.rename("prod", "production");
        assertFalse(TagRegistry.exists("prod"));
        assertTrue(TagRegistry.exists("production"));
        assertEquals(targetColor, TagRegistry.getColor("production"),
                "renaming onto an existing tag must merge into it, keeping the EXISTING tag's color, "
              + "not the renamed-away source's");
    }

    // -----------------------------------------------------------------------
    // Case-insensitivity
    // -----------------------------------------------------------------------

    @Test
    void tagNames_areCaseInsensitive_acrossExistsCreateAndGetColor() {
        RGB color = new RGB(11, 22, 33);
        TagRegistry.create("Prod", color);
        assertTrue(TagRegistry.exists("prod"), "exists() must be case-insensitive");
        assertTrue(TagRegistry.exists("PROD"));
        assertFalse(TagRegistry.create("prod", new RGB(99, 99, 99)),
                "create() with a different case of an existing tag must be rejected as a duplicate");
        assertEquals(color, TagRegistry.getColor("PROD"),
                "getColor() must resolve case-insensitively to the original entry");
    }

    // -----------------------------------------------------------------------
    // Persistence round-trip
    // -----------------------------------------------------------------------

    @Test
    void persistedTags_surviveAReload_withNamesAndColorsIntact() throws Exception {
        TagRegistry.create("prod", new RGB(10, 20, 30));
        TagRegistry.create("staging", new RGB(40, 50, 60));
        TagRegistry.create("dev", new RGB(70, 80, 90));

        clearInMemoryTags(); // simulate a fresh process: nothing in memory, only what's on disk
        reload();

        List<String> all = TagRegistry.getAll();
        assertEquals(3, all.size());
        assertTrue(all.contains("prod") && all.contains("staging") && all.contains("dev"));
        assertEquals(new RGB(10, 20, 30), TagRegistry.getColor("prod"));
        assertEquals(new RGB(40, 50, 60), TagRegistry.getColor("staging"));
        assertEquals(new RGB(70, 80, 90), TagRegistry.getColor("dev"));
    }

    @Test
    void load_withNoFileOnDisk_leavesRegistryEmpty() throws Exception {
        assertFalse(Files.exists(file));
        reload();
        assertTrue(TagRegistry.getAll().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Corruption bounds (crafted/hand-edited tags.properties)
    // -----------------------------------------------------------------------

    @Test
    void load_capsTagCountAtMaxTags_insteadOfGrowingUnbounded() throws Exception {
        int maxTags = staticInt("MAX_TAGS");
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < maxTags + 50; i++) {
            if (i > 0) order.append(',');
            order.append("tag").append(i);
        }
        writeRawProperties(Map.of("_order", order.toString()));

        reload();

        assertEquals(maxTags, TagRegistry.getAll().size(),
                "a crafted file with more than MAX_TAGS entries must be capped, not loaded in full");
    }

    @Test
    void load_skipsATagNameLongerThanMaxLength() throws Exception {
        int maxLen = staticInt("MAX_TAG_NAME_LENGTH");
        String tooLong = "x".repeat(maxLen + 1);
        writeRawProperties(Map.of("_order", "short," + tooLong));

        reload();

        assertTrue(TagRegistry.exists("short"));
        assertFalse(TagRegistry.getAll().contains(tooLong),
                "a tag name longer than MAX_TAG_NAME_LENGTH must be skipped, not loaded");
    }

    @Test
    void load_withMalformedColorValue_fallsBackToDefaultPaletteColor() throws Exception {
        writeRawProperties(Map.of("_order", "prod", "color.prod", "not-a-color"));

        reload();

        assertTrue(TagRegistry.exists("prod"));
        assertEquals(paletteFirst(), TagRegistry.getColor("prod"),
                "an unparseable color value must fall back to the default palette color, not throw");
    }

    @Test
    void load_withAMalformedUnicodeEscape_doesNotThrow_leavesRegistryEmpty() throws Exception {
        // Properties.load() throws IllegalArgumentException (NOT IOException) for a bad
        // \\uXXXX escape — built via char-concatenation, not a literal escape sequence in this
        // source file, since javac itself pre-processes \\u before tokenizing (an invalid hex
        // sequence written literally fails to compile).
        Files.createDirectories(file.getParent());
        String malformed = "_order=abc" + '\\' + "uZZZZ" + "\n";
        Files.write(file, malformed.getBytes("ISO-8859-1"));

        assertDoesNotThrow(TagRegistryTest::reload,
                "a corrupted tags.properties (bad unicode escape) must not crash load() — "
              + "IllegalArgumentException from Properties.load() was previously uncaught, and "
              + "since load() runs from TagRegistry's static initializer, this could take down "
              + "the whole class (ExceptionInInitializerError) the first time anything touched it");
        assertTrue(TagRegistry.getAll().isEmpty(), "a load() that bails out must leave no partial state");
    }

    // -----------------------------------------------------------------------
    // Comma in a tag name would corrupt the comma-joined "_order" property
    // -----------------------------------------------------------------------

    @Test
    void create_rejectsATagNameContainingAComma() {
        // "_order" joins every tag name with "," and splits on "," to parse it back — a tag
        // name containing a comma would silently fragment into extra bogus tags on the next
        // load, exactly like TagManagerDialog.createTag()'s UI-level guard already prevents,
        // but TagRegistry itself (the actual API boundary) did not.
        assertFalse(TagRegistry.create("a,b", new RGB(1, 2, 3)),
                "create() must reject a tag name containing a comma");
        assertFalse(TagRegistry.exists("a,b"));
    }

    @Test
    void register_rejectsATagNameContainingAComma() {
        TagRegistry.register("a,b");
        assertFalse(TagRegistry.exists("a,b"), "register() must reject a tag name containing a comma");
    }

    @Test
    void rename_rejectsRenamingOntoATagNameContainingAComma() {
        TagRegistry.create("prod", new RGB(1, 2, 3));
        TagRegistry.rename("prod", "a,b");
        assertTrue(TagRegistry.exists("prod"), "the rejected rename must leave the original tag alone");
        assertFalse(TagRegistry.exists("a,b"));
    }

    @Test
    void commaRejection_preventsTheOrderCorruptionRoundTrip() throws Exception {
        // The actual end-to-end scenario: without the guards above, two tags could silently
        // collapse into three different ones after a reload. With the guards, the malicious/
        // accidental comma-containing tag is simply never created in the first place.
        TagRegistry.create("a,b", new RGB(1, 2, 3)); // rejected — see create() test above
        TagRegistry.create("c", new RGB(4, 5, 6));

        clearInMemoryTags();
        reload();

        assertEquals(List.of("c"), TagRegistry.getAll(),
                "only the legitimately-created tag must survive — no fragments from a comma-corrupted _order");
    }

    private static int staticInt(String name) throws Exception {
        Field f = TagRegistry.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static void writeRawProperties(Map<String, String> entries) throws Exception {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        entries.forEach(p::setProperty);
        try (var out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }
}
