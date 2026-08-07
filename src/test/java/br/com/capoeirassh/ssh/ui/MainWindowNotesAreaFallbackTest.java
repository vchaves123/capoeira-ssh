package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code MainWindow}'s release-notes area used to only catch {@code SWTError} (no browser engine
 * available) around {@code ReleaseNotesHtml.render(notesText)} — not a general exception from
 * {@code render()} itself, even though its input is a GitHub release's notes text, content from
 * outside this program and not fully trusted. No concrete input was found that makes
 * {@code render()} throw today, but the asymmetry with how every other external-data path in this
 * codebase (BackupBundle, SessionImporter) handles malformed input broadly was itself the finding.
 *
 * <p>This test exercises the extracted, package-private {@code buildNotesArea} directly with a
 * deliberately-throwing {@code htmlSupplier}, standing in for a hypothetical
 * {@code ReleaseNotesHtml.render()} failure — verifying the fallback produces exactly one plain
 * {@link Text} control (no leftover, half-built {@code Browser}) showing the original notes text.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class MainWindowNotesAreaFallbackTest {

    @Test
    void buildNotesArea_fallsBackToPlainText_whenHtmlSupplierThrows() {
        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            shell.setLayout(new GridLayout(1, false));
            MainWindow mainWindow = new MainWindow(display);

            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            String notesText = "some release notes text";

            assertDoesNotThrow(() -> mainWindow.buildNotesArea(shell, gd, notesText,
                    () -> { throw new RuntimeException("simulated ReleaseNotesHtml.render() failure"); }));

            Control[] children = shell.getChildren();
            assertEquals(1, children.length, "expected exactly one fallback control, no leftover Browser");
            assertInstanceOf(Text.class, children[0]);
            assertEquals(notesText, ((Text) children[0]).getText());
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
