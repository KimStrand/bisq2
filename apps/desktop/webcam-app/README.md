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

The desktop launcher applies baseline hardening: a dedicated working directory and a minimized environment. Linux builds
additionally package a native launcher that sets `no_new_privs` before the JVM starts, denies new IPv4/IPv6 sockets, and
attempts a fail-open Landlock filesystem restriction. Broader OS process sandboxing and strict private-data read isolation
remain target policy items until further OS-specific enforcement is implemented.

The launcher routes the webcam Java command through the sandbox policy before creating the process, so an OS-specific
pre-JVM sandbox launcher can wrap the command.

On Linux builds, Gradle compiles and packages `bisq-webcam-sandbox-launcher` into the webcam zip. The desktop extractor
makes that launcher executable, and the Linux policy prepends it to the Java command. Missing or non-executable launcher
files fail startup instead of falling back to an unsandboxed `java -jar` launch.

The Linux launcher sets `no_new_privs` before the webcam JVM starts. This prevents privilege gain through later child
process execution. The launcher denies new IPv4 and IPv6 sockets with seccomp while leaving Unix domain sockets
available for Linux desktop session plumbing. When launched by the desktop policy, it also attempts a
fail-open Landlock filesystem restriction with explicit roots: reads are allowed for the webcam working directory, the
active Java runtime, selected system runtime directories, and the X11 authority file when present;
writes are limited to the webcam working directory, `/dev`, `/run`, `/tmp`, and `/var/tmp`. Direct launcher use without
explicit roots keeps the broader read compatibility fallback.

On macOS builds, Gradle generates a separate `BisqWebcam.app` helper image and signs it with App Sandbox and camera
entitlements. The desktop packaging embeds that helper app into the main app payload, and the macOS policy launches the
helper executable instead of `java -jar`. Network entitlements are intentionally omitted. The helper writes logs to
stderr, and the desktop process redirects stderr into the normal `webcam/webcam-app.log` file so the helper does not need
write access to the Bisq data directory.

## How to build to make it accessible in desktop?

To include the resources in the desktop application it requires to run the gradle task `processWebcamForDesktop` in the
webcam project (subproject of desktop).
This task creates a shadow jar, makes a zip and copies the zip to the build directory in the `desktop:desktop` project.
The target directory is `apps/desktop/desktop/build/generated/src/main/resources/webcam-app`.
It also copies the `version.txt` file from the `desktop:webcam` projects root directory to the same resource directory.

On macOS installer builds, `generateInstallers` also runs `generateMacOsWebcamHelperApp` and embeds the resulting
`BisqWebcam.app` helper into the main application payload.
