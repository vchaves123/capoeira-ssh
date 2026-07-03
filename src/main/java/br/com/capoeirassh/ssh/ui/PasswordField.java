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

        Button eye = new Button(cmp, SWT.PUSH);
        eye.setText("👁");
        eye.setToolTipText("Hold to show password");

        char[] echo = { txt.getEchoChar() };
        eye.addListener(SWT.MouseDown, e -> txt.setEchoChar((char) 0));
        eye.addListener(SWT.MouseUp,   e -> txt.setEchoChar(echo[0]));

        return txt;
    }
}
