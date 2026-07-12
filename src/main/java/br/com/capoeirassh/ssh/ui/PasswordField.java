package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * Password Text widget with a hold-to-reveal eye button.
 * Usage: Text txt = PasswordField.create(parent, layoutData);
 */
public final class PasswordField {

    private PasswordField() {}

    /**
     * Creates a composite containing a password Text and an eye button.
     * The composite uses the supplied layoutData.
     * Returns the inner Text widget.
     */
    public static Text create(Composite parent, Object layoutData) {
        Composite cmp = new Composite(parent, SWT.NONE);
        if (layoutData != null) cmp.setLayoutData(layoutData);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 4;
        cmp.setLayout(gl);

        Text txt = new Text(cmp, SWT.BORDER | SWT.PASSWORD);
        txt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Defense-in-depth: SWT.PASSWORD's echo masking is documented to also block the OS
        // clipboard from receiving a copy on some platforms (e.g. Win32's ES_PASSWORD), but
        // that's a platform-specific side effect, not a guarantee — a GTK/Linux build may not
        // enforce it. Explicitly block the common copy shortcuts here too; paste (Ctrl+V /
        // right-click paste) is intentionally left untouched since the user needs it to paste
        // in a password.
        txt.addListener(SWT.KeyDown, e -> {
            boolean primaryMod = (e.stateMask & SWT.MOD1) != 0;
            if (primaryMod && (e.keyCode == 'c' || e.keyCode == 'C')) e.doit = false;
            if ((e.stateMask & SWT.CTRL) != 0 && e.keyCode == SWT.INSERT) e.doit = false;
        });

        Button eye = new Button(cmp, SWT.PUSH);
        eye.setText("👁");
        eye.setToolTipText("Hold to show password");

        char[] echo = { txt.getEchoChar() };
        eye.addListener(SWT.MouseDown, e -> txt.setEchoChar((char) 0));
        eye.addListener(SWT.MouseUp,   e -> txt.setEchoChar(echo[0]));

        return txt;
    }

    /** Best-effort: overwrites the widget's native buffer before the dialog holding it is
     *  disposed, so a plaintext password/passphrase doesn't linger there indefinitely after
     *  the dialog closes. Safe to call on a disposed/null Text (no-op). Call this right
     *  before dlg.dispose() for every PasswordField-created (or otherwise password-holding)
     *  Text in a dialog. */
    public static void scrub(Text txt) {
        if (txt != null && !txt.isDisposed()) txt.setText("");
    }
}
