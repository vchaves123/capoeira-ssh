package br.com.quatorzebis.ssh;

import br.com.quatorzebis.ssh.ui.MainWindow;
import org.eclipse.swt.widgets.Display;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Main {

    public static void main(String[] args) {
        redirectConsoleToLog();
        Display display = new Display();
        try {
            new MainWindow(display).open();
        } finally {
            if (!display.isDisposed()) display.dispose();
        }
    }

    private static void redirectConsoleToLog() {
        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".14bis", "log");
            Files.createDirectories(logDir);
            Path file = logDir.resolve("app.log");
            PrintStream ps = new PrintStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
            printStartupBanner();
        } catch (IOException e) {
            // If we can't open the log, keep the original console — not fatal.
        }
    }

    private static void printStartupBanner() {
        String ts  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sep = "=".repeat(72);
        System.out.println();
        System.out.println(sep);
        System.out.printf("  14bis SSH  v%s  build #%d  —  started %s%n",
            BuildInfo.VERSION, BuildInfo.BUILD, ts);
        System.out.println(sep);
        System.out.printf("  Java      : %s  (%s)%n",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"));
        System.out.printf("  JVM       : %s%n",
            System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.printf("  Java home : %s%n",
            System.getProperty("java.home"));
        System.out.printf("  OS        : %s  %s  (%s)%n",
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"));
        System.out.printf("  User home : %s%n",
            System.getProperty("user.home"));
        System.out.printf("  Encoding  : %s%n",
            System.getProperty("file.encoding"));
        System.out.println(sep);
        System.out.println();
    }
}
