package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TerminalCell}'s copy/clear semantics — the primitive at the root of the
 * build-30 aliasing bug class (deleteChars() used {@code System.arraycopy} on the cell array
 * instead of {@code copyFrom()}, so cells ended up sharing references instead of values). Never
 * tested in isolation before; only exercised indirectly through {@link TerminalEmulator}.
 */
class TerminalCellTest {

    @Test
    void copyFrom_copiesEveryFieldByValue_notByReference() {
        TerminalCell src = new TerminalCell();
        src.character   = 'X';
        src.fgColor     = 5;
        src.bgColor     = 6;
        src.bold        = true;
        src.underline   = true;
        src.reverse     = true;
        src.blink       = true;
        src.wideTrailer = true;

        TerminalCell dst = new TerminalCell();
        dst.copyFrom(src);

        assertNotSame(src, dst, "copyFrom() must not make dst an alias of src");
        assertEquals(src.character, dst.character);
        assertEquals(src.fgColor, dst.fgColor);
        assertEquals(src.bgColor, dst.bgColor);
        assertEquals(src.bold, dst.bold);
        assertEquals(src.underline, dst.underline);
        assertEquals(src.reverse, dst.reverse);
        assertEquals(src.blink, dst.blink);
        assertEquals(src.wideTrailer, dst.wideTrailer);
    }

    @Test
    void copyFrom_isASnapshot_laterMutatingSrcDoesNotAffectDst() {
        TerminalCell src = new TerminalCell();
        src.character = 'A';
        TerminalCell dst = new TerminalCell();
        dst.copyFrom(src);

        src.character = 'B'; // mutate src after the copy
        assertEquals('A', dst.character, "dst must hold an independent snapshot, not a live view of src");
    }

    @Test
    void copyConstructor_producesAnIndependentSnapshot() {
        TerminalCell src = new TerminalCell();
        src.character = 'Z';
        src.bold = true;

        TerminalCell copy = new TerminalCell(src);
        assertNotSame(src, copy);
        assertEquals('Z', copy.character);
        assertTrue(copy.bold);

        src.character = 'Y';
        assertEquals('Z', copy.character, "the copy constructor must snapshot, not alias, src");
    }

    @Test
    void clear_resetsCharacterToSpace_andAllAttributesToDefaults() {
        TerminalCell cell = new TerminalCell();
        cell.character   = 'Q';
        cell.fgColor     = 3;
        cell.bgColor     = 4;
        cell.bold        = true;
        cell.underline   = true;
        cell.reverse     = true;
        cell.blink       = true;
        cell.wideTrailer = true;

        cell.clear();

        assertEquals(' ', cell.character);
        assertEquals(TerminalEmulator.DEFAULT_COLOR, cell.fgColor);
        assertEquals(TerminalEmulator.DEFAULT_COLOR, cell.bgColor);
        assertFalse(cell.bold);
        assertFalse(cell.underline);
        assertFalse(cell.reverse);
        assertFalse(cell.blink);
        assertFalse(cell.wideTrailer);
    }

    @Test
    void resetAttrs_resetsAttributesOnly_leavesCharacterUntouched() {
        TerminalCell cell = new TerminalCell();
        cell.character = 'Q';
        cell.bold = true;
        cell.resetAttrs();
        assertEquals('Q', cell.character, "resetAttrs() must not touch the character, unlike clear()");
        assertFalse(cell.bold);
    }

    @Test
    void twoIndependentlyConstructedCells_areNeverTheSameObject() {
        // Guards the exact failure mode of the build-30 bug: a naive allocation loop (or a
        // shallow array copy standing in for one) that accidentally shares one instance across
        // what should be independent cells.
        TerminalCell a = new TerminalCell();
        TerminalCell b = new TerminalCell();
        assertNotSame(a, b);
        a.character = 'A';
        assertEquals(' ', b.character, "mutating one cell must never affect an independently constructed one");
    }
}
