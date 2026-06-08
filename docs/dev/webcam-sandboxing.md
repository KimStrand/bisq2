# Webcam QR scanning and sandboxing

Date: 2026-06-08

## Current implementation on main

Bisq QR-code scanning is already split out of the desktop process. The desktop application does not
read frames from the camera directly. Instead, it starts a separate Java webcam helper process and
receives QR scan results over stdio IPC.

This split is useful because the camera and QR parser do not run in the main Bisq process.

## Security goal

The sandboxing work tries to make the webcam helper a least-privilege QR scanner.

Target properties:

- The helper can access the webcam.
- The helper cannot use the network.
- The helper cannot read or write the Bisq data folder.
- The desktop process receives only a small decoded QR-code payload over authenticated IPC.

## Sandboxing idea

The common design is:

1. Keep the current helper-process boundary.
2. Keep the narrow stdio IPC protocol.
3. Add an OS sandbox around the helper process.
4. Grant only the resources required for camera scanning.
5. Do not grant network access.
6. Do not grant the Bisq data directory.

The OS-specific mechanism is different per platform:

| Platform | Proposed mechanism | Intended result |
|---|---|---|
| Linux | Native launcher with `no_new_privs`, seccomp, and Landlock | Keep camera access, block IPv4/IPv6 sockets, restrict filesystem roots |
| Windows | AppContainer / MSIX AppContainer | Keep webcam capability, omit network capability, isolate filesystem |
| macOS | App Sandbox with camera entitlement | Keep camera entitlement, omit network entitlements, rely on App Sandbox file isolation |

## Linux status

Linux works with the current sandboxing approach.

Implementation:

- `LinuxWebcamSandboxPolicy` wraps the webcam helper with `bisq-webcam-sandbox-launcher`.
- The launcher enables `no_new_privs`.
- The launcher installs a seccomp filter that denies `socket(AF_INET, ...)` and
  `socket(AF_INET6, ...)` with `EACCES`.
- The launcher applies Landlock filesystem rules when the kernel supports Landlock.
- The Java policy sets `HOME` to the webcam data directory.

Filesystem model:

- Read roots include the packaged webcam app directory, the Java runtime, and system locations needed
  to run camera/UI/native libraries.
- Write roots include the webcam data directory plus required runtime/device locations such as
  `/dev`, `/run`, `/tmp`, and `/var/tmp`.
- The Bisq data folder is not added to the configured Landlock roots.

Important caveat:

- The launcher currently continues without the filesystem sandbox if Landlock is unavailable or
  cannot be installed. Network blocking fails closed because seccomp installation failure aborts
  launch, but filesystem isolation depends on Landlock support.

Follow-up work:

- Add smoke tests for network denial.
- Add smoke tests for Bisq data-folder denial.
- Decide whether Linux should fail closed when Landlock is unavailable.

## Windows status

Windows is blocked for the current Java/OpenCV helper design.

The desired Windows design was:

- Launch the webcam helper in AppContainer.
- Grant the `webcam` capability.
- Do not grant network capabilities.
- Grant only the packaged helper/runtime files and scoped AppContainer storage.
- Keep QR decoding in the sandboxed helper.

What was fixed and validated:

- The AppContainer launcher starts.
- AppContainer profile creation and process creation work.
- JavaCPP/OpenCV native libraries can be loaded from the read-only packaged webcam directory.
- The previous `Path.toRealPath()` / JavaCPP cache canonicalization problem is avoided by disabling
  JavaCPP cache extraction and loading native libraries from the packaged directory.
- Dependent OpenCV/JavaCPP DLL loading was fixed by putting the packaged webcam directory on `PATH`.
- The helper can start far enough inside AppContainer for Java/OpenCV probing.

Camera validation result:

| Mode | Token/package state | Camera result |
|---|---|---|
| Direct desktop process | No AppContainer, no package identity | Frames flow |
| Full-trust sparse MSIX package | Package identity, no AppContainer | Frames flow |
| Bare AppContainer | AppContainer, no package identity | Camera open denied |
| AppContainer using package SID | AppContainer, no real package identity | Camera open denied |
| Real MSIX `packagedClassicApp` AppContainer | AppContainer plus package identity | OpenCV/MSMF camera open denied |

The real MSIX AppContainer result is important. MSIX can launch a JVM with both
`TokenIsAppContainer=true` and a real package family name, but OpenCV/MSMF still cannot open the
camera from that process.

Observed forced-MSMF probe result for the real MSIX AppContainer:

```text
token_is_appcontainer=true
package_family_name=Bisq.Webcam.MsixAppContainerProbe.AppContainer_ks1qjdzpnyyh2
open_result=false
is_opened=false
result=camera_open_failed
```

Reasoning:

- The current helper uses JavaCV/OpenCV: `VideoCapture` and `OpenCVFrameGrabber`.
- On Windows that goes through OpenCV native `videoio`, and in the forced test through Media
  Foundation/MSMF.
- It does not use `Windows.Media.Capture.MediaCapture`, the Windows Runtime camera API designed for
  packaged AppContainer camera apps.
- Windows Camera proves that AppContainer plus camera can work, but not through this Java/OpenCV
  helper path.

Conclusion:

- Windows AppContainer is not usable for the existing Java/OpenCV webcam helper.
- Full-trust MSIX proves package identity and camera consent can work, but it is not sandboxed.
- A backend switch does not rescue the current helper; forced `CAP_MSMF` was tested and still
  failed inside AppContainer.

Windows options from here:

1. Prototype a minimal native C++/WinRT or C# `MediaCapture` MSIX AppContainer scanner/probe.
2. If that works, replace the Windows helper with a native standalone scanner that returns only the
   decoded QR payload.
3. Alternatively, use a full-trust camera broker and pass frames to a sandboxed decoder. This keeps
   QR parsing sandboxed but leaves the camera broker trusted.
4. If neither path is acceptable, drop Windows sandboxing for webcam scanning and use a weaker or
   manual/external scan flow.

## macOS status

macOS has a plausible sandboxing design, but we did not try or validate it yet.

The intended macOS design:

- Package the webcam helper as `BisqWebcam.app`.
- Launch that app instead of directly launching the webcam jar.
- Sign the helper with Apple App Sandbox enabled.
- Grant only the camera entitlement.
- Do not grant network client/server entitlements.
- Do not grant access to the Bisq data folder.

Current implementation pieces:

- `MacOsWebcamSandboxPolicy` locates and launches the packaged `BisqWebcam.app` helper.
- `apps/desktop/webcam-app/package/macosx/BisqWebcam.entitlements` enables:
  - `com.apple.security.app-sandbox`
  - `com.apple.security.device.camera`
- The entitlement file does not include network entitlements.

Expected result:

- The helper should be able to access the camera.
- The helper should not be able to use the network without network entitlements.
- The helper should not be able to access the Bisq data folder unless explicitly granted through the
  package or an OS-mediated user grant.

Unknowns:

- We have not validated the signed/package-built helper on macOS.
- We have not confirmed camera permission prompts and runtime camera capture in the sandboxed helper.
- We have not run network-denial or Bisq-data-folder-denial probes.
- We have not verified that final release packaging preserves the intended entitlements.

Follow-up work:

1. Build the signed macOS package.
2. Confirm `BisqWebcam.app` has the App Sandbox and camera entitlements.
3. Run a real QR scan from the packaged helper.
4. Add a network-denial smoke probe.
5. Add a Bisq data-folder access-denial smoke probe.

## Branch pause state

The branch is useful as an investigation branch. It captures:

- The current helper-process model.
- The desired least-privilege sandboxing model.
- A working Linux direction.
- A blocked Windows Java/OpenCV/AppContainer result.
- A plausible but unvalidated macOS App Sandbox direction.

Recommended next step when resuming:

1. Add Linux/macOS denial probes.
2. Decide Linux fail-open versus fail-closed behavior when Landlock is unavailable.
3. Prototype a native Windows `MediaCapture` MSIX AppContainer probe.
4. Decide whether Windows should use a native scanner helper, a full-trust broker plus sandboxed
   decoder, or no sandboxed webcam helper.
