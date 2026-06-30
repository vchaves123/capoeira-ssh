package br.com.quatorzebis.ssh;

import br.com.quatorzebis.ssh.ui.MainWindow;
import org.eclipse.swt.widgets.Display;

public class Main {

    public static void main(String[] args) {
        Display display = new Display();
        try {
            new MainWindow(display).open();
        } finally {
            if (!display.isDisposed()) display.dispose();
        }
    }
}
