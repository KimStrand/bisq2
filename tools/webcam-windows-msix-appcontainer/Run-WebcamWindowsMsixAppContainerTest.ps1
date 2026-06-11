<#
.SYNOPSIS
    Validates Windows webcam capture inside a real MSIX AppContainer (real package identity + webcam capability).

.DESCRIPTION
    Builds a real MSIX packagedClassicApp around the webcam helper and runs a probe across four modes (direct,
    full-trust package, package-SID AppContainer, real MSIX AppContainer), printing an exit-code matrix.

    Use -Probe to choose what runs inside the sandbox:
      opencv        the original CamProbe (OpenCV VideoCapture/MSMF) - denied inside the AppContainer.
      winrt         WinRtCaptureProbe - WinRT capture only (frames flowing).
      winrt-decode  QrDecodeProbe (--backend winrt) - capture + ZXing decode; prints the decoded QR payload.

    HOW TO TEST THE WINRT CAPTURE PATH:
      1. Prereqs: Windows 10 1809+/11, JDK 21 (JAVA_HOME set), Visual Studio Build Tools 2022 with the Desktop C++
         workload + Windows SDK, and Developer Mode ON (Settings > Privacy & security > For developers) so the MSIX
         package can be registered.
      2. Open "Developer PowerShell for VS 2022" AS ADMINISTRATOR. The VS environment puts cl.exe on PATH so both the
         webcam DLL (Gradle) and the probe-runner compile; admin is required to register the MSIX package.
      3. Ensure a camera is available: attach a webcam, or run OBS Studio > Start Virtual Camera pointed at a sharp,
         well-lit QR code (needed only for winrt-decode to actually decode something).
      4. From the repo root, run one of:
           tools\webcam-windows-msix-appcontainer\Run-WebcamWindowsMsixAppContainerTest.ps1 -Probe winrt
           tools\webcam-windows-msix-appcontainer\Run-WebcamWindowsMsixAppContainerTest.ps1 -Probe winrt-decode -TimeoutSeconds 90
         (The script runs gradlew prepareWindowsWebcamAppContent itself; pass -SkipBuild to reuse an existing build.)
      5. Read the result matrix. The decisive row is real-msix-appcontainer:
           ExitCode 0                 -> WinRT capture works inside the real MSIX AppContainer (design validated).
           winrt-decode, payload line -> the full capture+decode pipeline works in the sandbox.
           ExitCode != 0              -> inspect build\webcam-windows-msix-appcontainer-test\results\
                                         real-msix-appcontainer\probe-output.txt for the winrt_open_fail hr= line.
      Note: camera consent requires real MSIX package identity, so the synthetic-AppContainer launcher used in
      production cannot obtain consent - shipping the helper as a real MSIX package is the validated delivery path.

.EXAMPLE
    Run-WebcamWindowsMsixAppContainerTest.ps1 -Probe winrt-decode -TimeoutSeconds 90
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$CompileOnly,
    [switch]$KeepPackage,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$RuntimeJavaHome,
    [ValidateSet("msmf", "any", "dshow")]
    [string]$Backend = "msmf",
    # opencv: the original CamProbe (VideoCapture/MSMF).
    # winrt: bisq.webcam.service.capture.WinRtCaptureProbe - WinRT capture only (frames flowing).
    # winrt-decode: bisq.webcam.service.capture.QrDecodeProbe --backend winrt - the full pipeline (capture + ZXing
    #   decode) run inside the sandbox, printing decoded_count and the decoded QR payload (first_payload).
    # Use winrt/winrt-decode to validate the design under real MSIX package identity - the case the
    # synthetic-AppContainer launcher cannot exercise because camera consent needs package identity.
    [ValidateSet("opencv", "winrt", "winrt-decode")]
    [string]$Probe = "opencv",
    [int]$Device = 0,
    [int]$Frames = 10,
    [int]$DelayMillis = 100,
    [int]$TimeoutSeconds = 60,
    [string]$PackageName = "Bisq.Webcam.MsixAppContainerProbe",
    [string]$AppContainerPackageName = "Bisq.Webcam.MsixAppContainerProbe.AppContainer",
    [string]$Publisher = "CN=Bisq Webcam Probe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message"
}

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code $LASTEXITCODE)"
    }
}

function Resolve-JavaHome {
    param([string]$RequestedJavaHome)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJavaHome)) {
        $resolved = (Resolve-Path $RequestedJavaHome).Path.TrimEnd('\', '/')
        if (-not (Test-Path (Join-Path $resolved "bin\java.exe"))) {
            throw "java.exe was not found under JavaHome: $resolved"
        }
        if (-not (Test-Path (Join-Path $resolved "bin\javac.exe"))) {
            throw "javac.exe was not found under JavaHome: $resolved"
        }
        return $resolved
    }

    $javacCommand = Get-Command "javac.exe" -ErrorAction SilentlyContinue
    if ($null -eq $javacCommand) {
        throw "Could not find javac.exe. Set -JavaHome to a JDK 21 installation."
    }
    return (Resolve-Path (Join-Path (Split-Path -Parent $javacCommand.Source) "..")).Path.TrimEnd('\', '/')
}

function Resolve-JavaRuntimeHome {
    param([string]$RequestedJavaHome)

    if ([string]::IsNullOrWhiteSpace($RequestedJavaHome)) {
        throw "Runtime Java home was not provided."
    }

    $resolved = (Resolve-Path $RequestedJavaHome).Path.TrimEnd('\', '/')
    if (-not (Test-Path (Join-Path $resolved "bin\java.exe"))) {
        throw "java.exe was not found under RuntimeJavaHome: $resolved"
    }
    return $resolved
}

function Find-WebcamContentDir {
    param([string]$RepoRoot)

    $candidates = @(
        (Join-Path $RepoRoot "apps\desktop\webcam-app\build\packaging\windows-app-content\webcam"),
        (Join-Path $RepoRoot "apps\desktop\webcam-app\build\generated\windows-app-content\webcam")
    )

    foreach ($candidate in $candidates) {
        if (-not (Test-Path $candidate)) {
            continue
        }
        $jar = Get-ChildItem -Path $candidate -Filter "webcam-app-*-all.jar" -File -ErrorAction SilentlyContinue |
                Select-Object -First 1
        $launcher = Join-Path $candidate "bisq-webcam-appcontainer-launcher.exe"
        if ($null -ne $jar -and (Test-Path $launcher)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Windows webcam app content was not found. Run without -SkipBuild or build :apps:desktop:webcam-app:prepareWindowsWebcamAppContent first."
}

function ConvertTo-WindowsCommandLineArgument {
    param([string]$Argument)

    if ($Argument.Length -gt 0 -and $Argument -notmatch '[\s"]') {
        return $Argument
    }

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $backslashCount = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq [char]0x5c) {
            $backslashCount++
            continue
        }
        if ($character -eq [char]0x22) {
            [void]$builder.Append('\' * (($backslashCount * 2) + 1))
            [void]$builder.Append('"')
            $backslashCount = 0
            continue
        }
        if ($backslashCount -gt 0) {
            [void]$builder.Append('\' * $backslashCount)
            $backslashCount = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashCount -gt 0) {
        [void]$builder.Append('\' * ($backslashCount * 2))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Join-WindowsCommandLine {
    param([string[]]$Arguments)

    return (($Arguments | ForEach-Object { ConvertTo-WindowsCommandLineArgument $_ }) -join " ")
}

function ConvertTo-CmdArgument {
    param([string]$Argument)

    return '"' + ($Argument -replace '"', '\"') + '"'
}

function Get-VsDevCmdPath {
    $vswherePath = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswherePath)) {
        return $null
    }

    $installationPath = & $vswherePath `
            -latest `
            -products "*" `
            -requires "Microsoft.VisualStudio.Component.VC.Tools.x86.x64" `
            -property installationPath
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($installationPath)) {
        return $null
    }

    $candidate = Join-Path $installationPath "Common7\Tools\VsDevCmd.bat"
    if (Test-Path $candidate) {
        return $candidate
    }
    return $null
}

function Invoke-Cl {
    param([string[]]$Arguments)

    $clCommand = Get-Command "cl.exe" -ErrorAction SilentlyContinue
    if ($null -ne $clCommand) {
        & $clCommand.Source @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "cl.exe failed (exit code $LASTEXITCODE)"
        }
        return
    }

    $vsDevCmdPath = Get-VsDevCmdPath
    if ($null -eq $vsDevCmdPath) {
        throw "Could not find cl.exe or VsDevCmd.bat. Install Visual Studio Build Tools with the x64 C++ toolchain, or run from a Developer PowerShell."
    }

    $argumentLine = (($Arguments | ForEach-Object { ConvertTo-CmdArgument $_ }) -join " ")
    $command = '"' + $vsDevCmdPath + '" -arch=x64 -host_arch=x64 >nul && cl.exe ' + $argumentLine
    & cmd.exe /d /s /c $command
    if ($LASTEXITCODE -ne 0) {
        throw "cl.exe failed (exit code $LASTEXITCODE)"
    }
}

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )

    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Compile-ProbeRunner {
    param(
        [string]$SourcePath,
        [string]$OutputPath
    )

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
    $objectPath = [System.IO.Path]::ChangeExtension($OutputPath, ".obj")
    Invoke-Cl @(
        "/nologo",
        "/W4",
        "/WX",
        "/O2",
        "/DUNICODE",
        "/D_UNICODE",
        "/Fe$OutputPath",
        "/Fo$objectPath",
        $SourcePath,
        "advapi32.lib"
    )
}

function Compile-CamProbe {
    param(
        [string]$JavaHomePath,
        [string]$SourcePath,
        [string]$ClassesDir,
        [string]$ShadowJarPath
    )

    New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
    Invoke-Checked `
            -FilePath (Join-Path $JavaHomePath "bin\javac.exe") `
            -Arguments @("-encoding", "UTF-8", "-cp", $ShadowJarPath, "-d", $ClassesDir, $SourcePath) `
            -FailureMessage "CamProbe.java compilation failed"
}

function Escape-Xml {
    param([string]$Value)

    return [System.Security.SecurityElement]::Escape($Value)
}

function New-SparsePackageManifest {
    param(
        [string]$ExternalRoot,
        [string]$PackageNameValue,
        [string]$PublisherValue,
        [string]$IconSourcePath
    )

    $assetsDir = Join-Path $ExternalRoot "Assets"
    New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "StoreLogo.png")
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "Square150x150Logo.png")
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "Square44x44Logo.png")

    $escapedPackageName = Escape-Xml $PackageNameValue
    $escapedPublisher = Escape-Xml $PublisherValue
    $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10"
  xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap uap10 desktop rescap">
  <Identity Name="$escapedPackageName" Publisher="$escapedPublisher" Version="1.0.0.0" ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>Bisq Webcam MSIX Probe</DisplayName>
    <PublisherDisplayName>Bisq</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
    <uap10:AllowExternalContent>true</uap10:AllowExternalContent>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.19045.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Applications>
    <Application
      Id="App"
      Executable="probe-runner.exe"
      EntryPoint="Windows.FullTrustApplication"
      uap10:RuntimeBehavior="packagedClassicApp"
      uap10:TrustLevel="mediumIL">
      <uap:VisualElements
        DisplayName="Bisq Webcam MSIX Probe"
        Description="Bisq Webcam MSIX Probe"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png" />
      <Extensions>
        <desktop:Extension Category="windows.fullTrustProcess" Executable="probe-runner.exe" />
      </Extensions>
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
    <DeviceCapability Name="webcam" />
  </Capabilities>
</Package>
"@

    $manifestPath = Join-Path $ExternalRoot "AppxManifest.xml"
    Write-Utf8NoBom $manifestPath $manifest
    return $manifestPath
}

function New-AppContainerPackageManifest {
    param(
        [string]$PackageRoot,
        [string]$PackageNameValue,
        [string]$PublisherValue,
        [string]$IconSourcePath
    )

    $assetsDir = Join-Path $PackageRoot "Assets"
    New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "StoreLogo.png")
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "Square150x150Logo.png")
    Copy-Item -Force $IconSourcePath (Join-Path $assetsDir "Square44x44Logo.png")

    $escapedPackageName = Escape-Xml $PackageNameValue
    $escapedPublisher = Escape-Xml $PublisherValue
    $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10"
  IgnorableNamespaces="uap uap10">
  <Identity Name="$escapedPackageName" Publisher="$escapedPublisher" Version="1.0.0.0" ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>Bisq Webcam MSIX AppContainer Probe</DisplayName>
    <PublisherDisplayName>Bisq</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.19045.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Applications>
    <Application
      Id="App"
      Executable="probe-runner.exe"
      EntryPoint="Windows.PartialTrustApplication"
      uap10:RuntimeBehavior="packagedClassicApp"
      uap10:TrustLevel="appContainer">
      <uap:VisualElements
        DisplayName="Bisq Webcam MSIX AppContainer Probe"
        Description="Bisq Webcam MSIX AppContainer Probe"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png" />
    </Application>
  </Applications>
  <Capabilities>
    <DeviceCapability Name="webcam" />
  </Capabilities>
</Package>
"@

    $manifestPath = Join-Path $PackageRoot "AppxManifest.xml"
    Write-Utf8NoBom $manifestPath $manifest
    return $manifestPath
}

function Remove-PackagesByName {
    param([string]$PackageNameValue)

    $existingPackages = @(Get-AppxPackage -Name $PackageNameValue -ErrorAction SilentlyContinue)
    foreach ($existingPackage in $existingPackages) {
        Remove-AppxPackage -Package $existingPackage.PackageFullName -ErrorAction Stop
    }
}

function Register-SparsePackage {
    param(
        [string]$ManifestPath,
        [string]$ExternalRoot,
        [string]$PackageNameValue
    )

    Remove-PackagesByName $PackageNameValue

    try {
        Add-AppxPackage -Register $ManifestPath -ExternalLocation $ExternalRoot -ForceApplicationShutdown
    } catch {
        throw "Failed to register sparse MSIX package. If the error is 0x80073CFF, enable Windows Developer Mode or sideloading for unsigned local packages. Confirm this host also supports packages with external location. $($_.Exception.Message)"
    }

    $package = Get-AppxPackage -Name $PackageNameValue -ErrorAction Stop | Select-Object -First 1
    if ($null -eq $package) {
        throw "Sparse MSIX package registration did not return a package for $PackageNameValue"
    }
    return $package
}

function Register-DevelopmentPackage {
    param(
        [string]$ManifestPath,
        [string]$PackageNameValue
    )

    Remove-PackagesByName $PackageNameValue

    try {
        Add-AppxPackage -Register $ManifestPath -ForceApplicationShutdown
    } catch {
        throw "Failed to register development MSIX package. Confirm Developer Mode is enabled and the manifest is valid. $($_.Exception.Message)"
    }

    $package = Get-AppxPackage -Name $PackageNameValue -ErrorAction Stop | Select-Object -First 1
    if ($null -eq $package) {
        throw "Development MSIX package registration did not return a package for $PackageNameValue"
    }
    return $package
}

function Add-AppActivatorType {
    if ("Bisq.WebcamMsixProbe.AppActivator" -as [type]) {
        return
    }

    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

namespace Bisq.WebcamMsixProbe
{
    [Flags]
    public enum ActivateOptions
    {
        None = 0,
        DesignMode = 1,
        NoErrorUI = 2,
        NoSplashScreen = 4
    }

    [ComImport]
    [Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")]
    public class ApplicationActivationManager
    {
    }

    [ComImport]
    [Guid("2e941141-7f97-4756-ba1d-9decde894a3d")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IApplicationActivationManager
    {
        [PreserveSig]
        int ActivateApplication(
            [MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            [MarshalAs(UnmanagedType.LPWStr)] string arguments,
            ActivateOptions options,
            out uint processId);

        [PreserveSig]
        int ActivateForFile(
            [MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            IntPtr itemArray,
            [MarshalAs(UnmanagedType.LPWStr)] string verb,
            out uint processId);

        [PreserveSig]
        int ActivateForProtocol(
            [MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            IntPtr itemArray,
            out uint processId);
    }

    public static class AppActivator
    {
        public static uint Activate(string appUserModelId, string arguments)
        {
            var manager = (IApplicationActivationManager)new ApplicationActivationManager();
            uint processId;
            int result = manager.ActivateApplication(appUserModelId, arguments, ActivateOptions.NoErrorUI, out processId);
            if (result < 0)
            {
                Marshal.ThrowExceptionForHR(result);
            }
            return processId;
        }
    }
}
"@
}

function Copy-DirectoryContent {
    param(
        [string]$SourceDir,
        [string]$DestinationDir
    )

    $sourceRoot = (Resolve-Path $SourceDir).Path.TrimEnd('\', '/')
    New-Item -ItemType Directory -Force -Path $DestinationDir | Out-Null
    Get-ChildItem -LiteralPath $sourceRoot -Directory -Recurse -Force | ForEach-Object {
        $relativePath = $_.FullName.Substring($sourceRoot.Length).TrimStart('\', '/')
        New-Item -ItemType Directory -Force -Path (Join-Path $DestinationDir $relativePath) | Out-Null
    }
    Get-ChildItem -LiteralPath $sourceRoot -File -Recurse -Force | ForEach-Object {
        $relativePath = $_.FullName.Substring($sourceRoot.Length).TrimStart('\', '/')
        $destinationPath = Join-Path $DestinationDir $relativePath
        $destinationParent = Split-Path -Parent $destinationPath
        New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        if (Test-Path $destinationPath) {
            Remove-Item -LiteralPath $destinationPath -Force
        }
        New-Item -ItemType HardLink -Path $destinationPath -Target $_.FullName | Out-Null
    }
}

function New-PackagedAppContainerLayout {
    param(
        [string]$PackageRoot,
        [string]$RunnerPath,
        [string]$RuntimeJavaHomePath,
        [string]$ContentDir,
        [string]$ClassesDir,
        [string]$PackageNameValue,
        [string]$PublisherValue,
        [string]$IconSourcePath
    )

    if (Test-Path $PackageRoot) {
        Remove-Item -LiteralPath $PackageRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $PackageRoot | Out-Null

    Copy-Item -Force $RunnerPath (Join-Path $PackageRoot "probe-runner.exe")
    Copy-DirectoryContent $RuntimeJavaHomePath (Join-Path $PackageRoot "runtime")
    Copy-DirectoryContent $ContentDir (Join-Path $PackageRoot "webcam")
    Copy-DirectoryContent $ClassesDir (Join-Path $PackageRoot "classes")
    Get-ChildItem -Path $ContentDir -Filter "*.dll" -File | ForEach-Object {
        $destinationPath = Join-Path $PackageRoot $_.Name
        if (Test-Path $destinationPath) {
            Remove-Item -LiteralPath $destinationPath -Force
        }
        New-Item -ItemType HardLink -Path $destinationPath -Target $_.FullName | Out-Null
    }

    return New-AppContainerPackageManifest $PackageRoot $PackageNameValue $PublisherValue $IconSourcePath
}

function Get-PackageLocalStateDir {
    param([string]$PackageFamilyName)

    return Join-Path $env:LOCALAPPDATA "Packages\$PackageFamilyName\LocalState\BisqWebcamMsixProbe"
}

function Clear-ProbeFiles {
    param([string]$ExternalRoot)

    foreach ($name in @("probe-command.txt", "probe-output.txt", "probe-status.txt", "probe-token.txt", "probe-path-prefix.txt")) {
        $path = Join-Path $ExternalRoot $name
        if (Test-Path $path) {
            Remove-Item -Force $path
        }
    }
}

function Write-ProbeConfig {
    param(
        [string]$ExternalRoot,
        [string]$JavaHomePath,
        [string]$ContentDir,
        [string]$ShadowJarPath,
        [string]$ClassesDir
    )

    $javaExecutable = Join-Path $JavaHomePath "bin\java.exe"
    $classpath = $ShadowJarPath + [System.IO.Path]::PathSeparator + $ClassesDir
    # JavaCPP props are needed for both probes: even the WinRT probe constructs a JavaCV Frame, which loads jnijavacpp
    # from the read-only content dir (cache extraction is blocked in the AppContainer).
    if ($Probe -eq "winrt-decode") {
        # Full capture + ZXing decode pipeline inside the sandbox; prints decoded_count and first_payload.
        $commandArguments = @(
            $javaExecutable,
            "-Dorg.bytedeco.javacpp.cacheLibraries=false",
            "-Dorg.bytedeco.javacpp.pathsFirst=true",
            "-Djava.library.path=$ContentDir",
            "-cp",
            $classpath,
            "bisq.webcam.service.capture.QrDecodeProbe",
            "--backend",
            "winrt",
            "--device",
            "$Device",
            "--frames",
            "$Frames"
        )
    } elseif ($Probe -eq "winrt") {
        $commandArguments = @(
            $javaExecutable,
            "-Dorg.bytedeco.javacpp.cacheLibraries=false",
            "-Dorg.bytedeco.javacpp.pathsFirst=true",
            "-Djava.library.path=$ContentDir",
            "-cp",
            $classpath,
            "bisq.webcam.service.capture.WinRtCaptureProbe",
            "--device",
            "$Device",
            "--frames",
            "$Frames"
        )
    } else {
        $commandArguments = @(
            $javaExecutable,
            "-Dorg.bytedeco.javacpp.cacheLibraries=false",
            "-Dorg.bytedeco.javacpp.pathsFirst=true",
            "-Djava.library.path=$ContentDir",
            "-cp",
            $classpath,
            "CamProbe",
            "--device",
            "$Device",
            "--backend",
            $Backend,
            "--frames",
            "$Frames",
            "--delay-ms",
            "$DelayMillis"
        )
    }

    Write-Utf8NoBom (Join-Path $ExternalRoot "probe-command.txt") (Join-WindowsCommandLine $commandArguments)
    Write-Utf8NoBom (Join-Path $ExternalRoot "probe-path-prefix.txt") $ContentDir
}

function Read-KeyValueFile {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path $Path)) {
        return $values
    }

    foreach ($line in Get-Content -Path $Path) {
        if ($line -match '^([^=]+)=(.*)$') {
            $values[$matches[1]] = $matches[2]
        }
    }
    return $values
}

function Wait-ProbeStatus {
    param(
        [string]$ExternalRoot,
        [int]$TimeoutSecondsValue
    )

    $statusPath = Join-Path $ExternalRoot "probe-status.txt"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSecondsValue)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path $statusPath) {
            return [int]((Get-Content -Path $statusPath -Raw).Trim())
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for probe-status.txt after $TimeoutSecondsValue seconds"
}

function Save-ProbeResult {
    param(
        [string]$Mode,
        [string]$ExternalRoot,
        [string]$ResultsDir,
        [int]$ExitCode
    )

    $modeResultsDir = Join-Path $ResultsDir $Mode
    New-Item -ItemType Directory -Force -Path $modeResultsDir | Out-Null
    foreach ($name in @("probe-command.txt", "probe-output.txt", "probe-status.txt", "probe-token.txt", "probe-path-prefix.txt")) {
        $source = Join-Path $ExternalRoot $name
        if (Test-Path $source) {
            Copy-Item -Force $source (Join-Path $modeResultsDir $name)
        }
    }

    $token = Read-KeyValueFile (Join-Path $modeResultsDir "probe-token.txt")
    return [pscustomobject]@{
        Mode = $Mode
        ExitCode = $ExitCode
        TokenIsAppContainer = $token["token_is_appcontainer"]
        PackageFamilyName = $token["package_family_name"]
        Output = Join-Path $modeResultsDir "probe-output.txt"
        Token = Join-Path $modeResultsDir "probe-token.txt"
    }
}

function Invoke-DirectProbe {
    param(
        [string]$RunnerPath,
        [string]$ExternalRoot,
        [string]$ResultsDir,
        [string]$JavaHomePath,
        [string]$ContentDir,
        [string]$ShadowJarPath,
        [string]$ClassesDir
    )

    Clear-ProbeFiles $ExternalRoot
    Write-ProbeConfig $ExternalRoot $JavaHomePath $ContentDir $ShadowJarPath $ClassesDir
    & $RunnerPath
    $exitCode = $LASTEXITCODE
    return Save-ProbeResult "direct" $ExternalRoot $ResultsDir $exitCode
}

function Invoke-FullTrustPackageProbe {
    param(
        [string]$ExternalRoot,
        [string]$ResultsDir,
        [string]$JavaHomePath,
        [string]$ContentDir,
        [string]$ShadowJarPath,
        [string]$ClassesDir,
        [string]$PackageFamilyName,
        [int]$TimeoutSecondsValue
    )

    Clear-ProbeFiles $ExternalRoot
    Write-ProbeConfig $ExternalRoot $JavaHomePath $ContentDir $ShadowJarPath $ClassesDir
    Add-AppActivatorType
    [Bisq.WebcamMsixProbe.AppActivator]::Activate("$PackageFamilyName!App", "") | Out-Null
    $exitCode = Wait-ProbeStatus $ExternalRoot $TimeoutSecondsValue
    return Save-ProbeResult "full-trust-package" $ExternalRoot $ResultsDir $exitCode
}

function Invoke-PackagedAppContainerProbe {
    param(
        [string]$PackageRoot,
        [string]$ResultsDir,
        [string]$PackageFamilyName,
        [int]$TimeoutSecondsValue
    )

    $ioDir = Get-PackageLocalStateDir $PackageFamilyName
    New-Item -ItemType Directory -Force -Path $ioDir | Out-Null
    Clear-ProbeFiles $ioDir

    $packageRuntimeJavaHome = Join-Path $PackageRoot "runtime"
    $packageNativeDir = $PackageRoot
    $packageContentDir = Join-Path $PackageRoot "webcam"
    $packageClassesDir = Join-Path $PackageRoot "classes"
    $packageShadowJarPath = (Get-ChildItem -Path $packageContentDir -Filter "webcam-app-*-all.jar" -File |
            Select-Object -First 1).FullName
    Write-ProbeConfig $ioDir $packageRuntimeJavaHome $packageNativeDir $packageShadowJarPath $packageClassesDir
    Write-Utf8NoBom (Join-Path $PackageRoot "probe-io-dir.txt") $ioDir

    Add-AppActivatorType
    $arguments = Join-WindowsCommandLine @("--io-dir", $ioDir)
    [Bisq.WebcamMsixProbe.AppActivator]::Activate("$PackageFamilyName!App", $arguments) | Out-Null
    $exitCode = Wait-ProbeStatus $ioDir $TimeoutSecondsValue
    return Save-ProbeResult "real-msix-appcontainer" $ioDir $ResultsDir $exitCode
}

function Invoke-PackageSidAppContainerProbe {
    param(
        [string]$AppContainerLauncherPath,
        [string]$RunnerPath,
        [string]$WorkDir,
        [string]$ExternalRoot,
        [string]$ResultsDir,
        [string]$JavaHomePath,
        [string]$ContentDir,
        [string]$ShadowJarPath,
        [string]$ClassesDir,
        [string]$PackageFamilyName,
        [int]$TimeoutSecondsValue
    )

    Clear-ProbeFiles $ExternalRoot
    Write-ProbeConfig $ExternalRoot $JavaHomePath $ContentDir $ShadowJarPath $ClassesDir

    & $AppContainerLauncherPath `
            "--profile-name" $PackageFamilyName `
            "--capability" "webcam" `
            "--grant-read" $JavaHomePath `
            "--grant-read" $ContentDir `
            "--grant-write" $WorkDir `
            "--" `
            $RunnerPath
    $launcherExitCode = $LASTEXITCODE

    $statusPath = Join-Path $ExternalRoot "probe-status.txt"
    if (Test-Path $statusPath) {
        $exitCode = [int]((Get-Content -Path $statusPath -Raw).Trim())
    } elseif ($launcherExitCode -ne 0) {
        $exitCode = $launcherExitCode
    } else {
        $exitCode = Wait-ProbeStatus $ExternalRoot $TimeoutSecondsValue
    }
    return Save-ProbeResult "package-sid-appcontainer" $ExternalRoot $ResultsDir $exitCode
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw "This validation harness must run on Windows."
}

$repoRoot = Get-RepoRoot
$toolDir = $PSScriptRoot
$workDir = Join-Path $repoRoot "build\webcam-windows-msix-appcontainer-test"
$externalRoot = Join-Path $workDir "external"
$appContainerPackageRoot = Join-Path $workDir "real-msix-appcontainer-package"
$classesDir = Join-Path $workDir "classes"
$resultsDir = Join-Path $workDir "results"
$runnerSource = Join-Path $toolDir "probe-runner.c"
$runnerPath = Join-Path $externalRoot "probe-runner.exe"
$camProbeSource = Join-Path $toolDir "CamProbe.java"
$iconSource = Join-Path $repoRoot "apps\desktop\webcam-app\src\main\resources\images\webcam-app-icon.png"
$registeredPackage = $null
$appContainerRegisteredPackage = $null

New-Item -ItemType Directory -Force -Path $externalRoot | Out-Null
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

try {
    $javaHomePath = Resolve-JavaHome $JavaHome
    $runtimeJavaHomePath = if ([string]::IsNullOrWhiteSpace($RuntimeJavaHome)) {
        $javaHomePath
    } else {
        Resolve-JavaRuntimeHome $RuntimeJavaHome
    }

    if (-not $SkipBuild) {
        Write-Step "Building Windows webcam app content"
        Invoke-Checked `
                -FilePath (Join-Path $repoRoot "gradlew.bat") `
                -Arguments @(":apps:desktop:webcam-app:prepareWindowsWebcamAppContent") `
                -FailureMessage "Windows webcam app content build failed"
    }

    $contentDir = Find-WebcamContentDir $repoRoot
    $shadowJar = (Get-ChildItem -Path $contentDir -Filter "webcam-app-*-all.jar" -File | Select-Object -First 1).FullName
    $appContainerLauncher = Join-Path $contentDir "bisq-webcam-appcontainer-launcher.exe"

    Write-Step "Compiling probe runner"
    Compile-ProbeRunner $runnerSource $runnerPath

    Write-Step "Compiling Java camera probe"
    Compile-CamProbe $javaHomePath $camProbeSource $classesDir $shadowJar

    $manifestPath = New-SparsePackageManifest $externalRoot $PackageName $Publisher $iconSource

    Write-Step "Preparing real MSIX AppContainer package layout"
    $appContainerManifestPath = New-PackagedAppContainerLayout `
            $appContainerPackageRoot `
            $runnerPath `
            $runtimeJavaHomePath `
            $contentDir `
            $classesDir `
            $AppContainerPackageName `
            $Publisher `
            $iconSource

    if ($CompileOnly) {
        Write-Host "Compile-only completed."
        Write-Host "Runner: $runnerPath"
        Write-Host "Sparse manifest: $manifestPath"
        Write-Host "Real MSIX AppContainer manifest: $appContainerManifestPath"
        return
    }

    Write-Step "Registering sparse MSIX package"
    $registeredPackage = Register-SparsePackage $manifestPath $externalRoot $PackageName
    $packageFamilyName = $registeredPackage.PackageFamilyName
    Write-Host "PackageFullName: $($registeredPackage.PackageFullName)"
    Write-Host "PackageFamilyName: $packageFamilyName"

    Write-Step "Registering real MSIX AppContainer package"
    $appContainerRegisteredPackage = Register-DevelopmentPackage $appContainerManifestPath $AppContainerPackageName
    $appContainerPackageFamilyName = $appContainerRegisteredPackage.PackageFamilyName
    Write-Host "PackageFullName: $($appContainerRegisteredPackage.PackageFullName)"
    Write-Host "PackageFamilyName: $appContainerPackageFamilyName"

    $results = @()

    Write-Step "Running direct desktop baseline"
    $results += Invoke-DirectProbe $runnerPath $externalRoot $resultsDir $runtimeJavaHomePath $contentDir $shadowJar $classesDir

    Write-Step "Running full-trust packaged baseline"
    $results += Invoke-FullTrustPackageProbe `
            $externalRoot `
            $resultsDir `
            $runtimeJavaHomePath `
            $contentDir `
            $shadowJar `
            $classesDir `
            $packageFamilyName `
            $TimeoutSeconds

    Write-Step "Running package-family AppContainer probe"
    $results += Invoke-PackageSidAppContainerProbe `
            $appContainerLauncher `
            $runnerPath `
            $workDir `
            $externalRoot `
            $resultsDir `
            $runtimeJavaHomePath `
            $contentDir `
            $shadowJar `
            $classesDir `
            $packageFamilyName `
            $TimeoutSeconds

    Write-Step "Running real MSIX AppContainer probe"
    $results += Invoke-PackagedAppContainerProbe `
            $appContainerPackageRoot `
            $resultsDir `
            $appContainerPackageFamilyName `
            $TimeoutSeconds

    Write-Step "Result matrix"
    $results | Format-Table Mode, ExitCode, TokenIsAppContainer, PackageFamilyName -AutoSize
    Write-Host "Detailed output: $resultsDir"

    $fullTrustResult = $results | Where-Object { $_.Mode -eq "full-trust-package" } | Select-Object -First 1
    $appContainerResult = $results | Where-Object { $_.Mode -eq "package-sid-appcontainer" } | Select-Object -First 1
    $realAppContainerResult = $results | Where-Object { $_.Mode -eq "real-msix-appcontainer" } | Select-Object -First 1

    Write-Host "Probe backend: $Probe"
    if ($fullTrustResult.ExitCode -ne 0) {
        Write-Warning "The full-trust packaged baseline did not capture frames. Treat the AppContainer results as inconclusive until package camera consent is working."
    } elseif ($realAppContainerResult.ExitCode -eq 0) {
        if ($Probe -eq "winrt-decode") {
            $decodeValues = Read-KeyValueFile $realAppContainerResult.Output
            $decodedCount = $decodeValues["decoded_count"]
            $payload = $decodeValues["first_payload"]
            Write-Host "PASS: WinRT capture + ZXing decode ran inside the REAL MSIX AppContainer (package identity + webcam capability)." -ForegroundColor Green
            Write-Host "decoded_count=$decodedCount"
            if (-not [string]::IsNullOrEmpty($payload)) {
                Write-Host "Decoded QR payload (inside the sandbox): $payload" -ForegroundColor Green
                Write-Host "Full pipeline validated end-to-end in the sandbox."
            } else {
                Write-Host "Capture+decode ran in the sandbox but no QR was decoded - present a sharp, well-lit QR and re-run." -ForegroundColor Yellow
            }
        } elseif ($Probe -eq "winrt") {
            Write-Host "PASS: WinRT captured frames inside the REAL MSIX AppContainer (package identity + webcam capability)." -ForegroundColor Green
            Write-Host "The in-sandbox Windows design is validated: package the helper as MSIX with the webcam capability."
        } else {
            Write-Host "Real MSIX AppContainer captured frames. This is the remaining viable Windows sandbox path to investigate."
        }
    } elseif ($realAppContainerResult.ExitCode -ne 0 -and ($Probe -eq "winrt" -or $Probe -eq "winrt-decode")) {
        Write-Warning "WinRT did NOT run inside the real MSIX AppContainer. Inspect $resultsDir\real-msix-appcontainer\probe-output.txt for the winrt_open_fail hr= line before concluding a broker is required."
    } elseif ($appContainerResult.ExitCode -eq 0) {
        Write-Warning "Package-family AppContainer captured frames. This contradicts the current findings and should be revalidated with the saved token/output files."
    } else {
        Write-Host "Neither AppContainer path captured frames while the full-trust package baseline worked."
    }
} finally {
    if (-not $KeepPackage -and $null -ne $appContainerRegisteredPackage) {
        Write-Step "Removing real MSIX AppContainer package"
        Remove-AppxPackage -Package $appContainerRegisteredPackage.PackageFullName -ErrorAction SilentlyContinue
    }
    if (-not $KeepPackage -and $null -ne $registeredPackage) {
        Write-Step "Removing sparse MSIX package"
        Remove-AppxPackage -Package $registeredPackage.PackageFullName -ErrorAction SilentlyContinue
    }
}
