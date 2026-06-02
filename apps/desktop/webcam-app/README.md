# Webcam app

The webcam application runs as independent jvm process to reduce security risks from the many dependencies it uses (many
native drivers).
We start the app from the desktop application and communicate the resulting QR code and simple control
commands like `shutdown` over stdin/stdout pipes, without opening a network socket.
As there is no build dependency from desktop to the webcam app to avoid the dependency inclusion we need to build a jar
file zip it and copy it into desktop so that it can be used by the Bisq app.
We use the java executable which is included in the binary. We unzip the zip file from the resources into the
application directory and use that path for the Processbuilder.

## Sandbox policy

The webcam process should run with the smallest practical set of permissions. The target policy is:

- Allow camera access.
- Allow stdin/stdout IPC with the desktop process.
- Allow reading the extracted webcam jar and required runtime resources.
- Allow writing webcam helper logs only if helper file logging is enabled.
- Deny network access.
- Deny access to the Bisq main data directory, wallet data, and other private application data.
- Deny broad filesystem reads and writes where the operating system supports it.
- Deny privilege escalation and unnecessary child process creation where the operating system supports it.

The exact enforcement mechanism is OS-specific and should preserve the same policy intent on Linux, Windows, and macOS.

The desktop launcher applies baseline hardening: a dedicated working directory and a minimized environment. The sandbox
policy then constructs the final process command for the active OS before the process is started.

## Launch flow by OS

### Linux

Flow: `Bisq desktop -> bisq-webcam-sandbox-launcher -> java -jar webcam-app`.

On Linux builds, Gradle compiles and packages `bisq-webcam-sandbox-launcher` into the webcam zip. The desktop extractor
verifies the extracted resources against the packaged zip, makes the launcher executable, and fails startup if the
launcher is missing or not executable. The Linux policy builds the final command with the native launcher first, then the
Java webcam command after `--`.

The launcher sets `no_new_privs` before the webcam JVM starts. This prevents privilege gain through later child process
execution. It denies new IPv4 and IPv6 sockets with seccomp while leaving Unix domain sockets available for Linux desktop
session plumbing. It also attempts a fail-open Landlock filesystem restriction with explicit roots: reads are allowed for
the webcam working directory, the active Java runtime, selected system runtime directories, and the X11 authority file
when present; writes are limited to the webcam working directory, `/dev`, `/run`, `/tmp`, and `/var/tmp`.

### macOS

Flow: `Bisq.app -> embedded BisqWebcam.app -> webcam JVM inside the helper app`.

On macOS builds, Gradle generates a separate `BisqWebcam.app` helper image, adds the webcam app icon to that helper, and
signs it with App Sandbox and camera entitlements. The desktop packaging embeds the helper app into the main app payload
at `Bisq.app/Contents/app/BisqWebcam.app`. The macOS policy launches the helper executable directly instead of launching
the extracted webcam jar with `java -jar`.

Network entitlements are intentionally omitted, so network access should be denied by the macOS App Sandbox. The helper
writes logs to stderr, and the desktop process redirects stderr into the normal `webcam/webcam-app.log` file so the
helper does not need write access to the Bisq data directory.

### Windows

Flow: `Bisq desktop -> bisq-webcam-appcontainer-launcher.exe -> java -jar webcam-app`.

On Windows builds, Gradle compiles and packages `bisq-webcam-appcontainer-launcher.exe` into the webcam zip. The desktop
extractor verifies the extracted resources against the packaged zip. The Windows policy builds the final command with the
AppContainer launcher first, then the Java webcam command after `--`.

The launcher requests an AppContainer profile with the `webcam` capability only. Network capabilities are intentionally
omitted. The launcher grants the AppContainer access to the webcam working directory and the active Java runtime path
before starting the webcam JVM. This is an AppContainer test path and must be validated on Windows with real camera
access.

## How to build to make it accessible in desktop?

To include the resources in the desktop application it requires to run the gradle task `processWebcamForDesktop` in the
webcam project (subproject of desktop).
This task creates a shadow jar, makes a zip and copies the zip to the build directory in the `desktop:desktop` project.
The target directory is `apps/desktop/desktop/build/generated/src/main/resources/webcam-app`.
It also copies the `version.txt` file from the `desktop:webcam` projects root directory to the same resource directory.

On macOS installer builds, `generateInstallers` also runs `generateMacOsWebcamHelperApp` and embeds the resulting
`BisqWebcam.app` helper into the main application payload.
