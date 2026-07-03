14bis SSH — no-JRE bundle
=========================

This package contains the application and all its libraries (for every
supported OS/architecture), but NOT a Java runtime. You need Java 21 or
newer already installed.

  - Don't have Java? Get it free at https://adoptium.net (Temurin 21+).

To run:

  Windows  ->  double-click run.bat
  Linux    ->  ./run.sh
  macOS    ->  ./run.sh

Both scripts auto-detect your OS and CPU architecture (x86_64 / aarch64)
and pick the matching SWT library from lib/swt/ automatically.

If you'd rather use a fully self-contained installer/build that already
bundles a Java runtime, see the other assets on the release page.
