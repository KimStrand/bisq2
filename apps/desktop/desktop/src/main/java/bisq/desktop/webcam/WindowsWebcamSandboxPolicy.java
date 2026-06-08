/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.webcam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

final class WindowsWebcamSandboxPolicy extends NativeWebcamLauncherSandboxPolicy {
    static final String APPCONTAINER_LAUNCHER_FILE_NAME = "bisq-webcam-appcontainer-launcher.exe";
    private static final String APPCONTAINER_PROFILE_NAME = "bisq.webcam";
    private static final String WEBCAM_CAPABILITY_NAME = "webcam";
    private static final String JAVACPP_CACHE_SCOPE_PREFIX = "webcam-app-";
    private static final String APP_CONTENT_DIR_NAME = "app";
    private static final String WEBCAM_APP_CONTENT_DIR_NAME = "webcam";
    private static final Set<String> ALLOWED_ENVIRONMENT_VARIABLE_NAMES = allowedEnvironmentVariableNames(
            "APPDATA",
            "CommonProgramFiles",
            "CommonProgramFiles(x86)",
            "CommonProgramW6432",
            "HOMEDRIVE",
            "HOMEPATH",
            "LOCALAPPDATA",
            "ProgramData",
            "ProgramFiles",
            "ProgramFiles(x86)",
            "ProgramW6432",
            "SystemDrive",
            "SystemRoot",
            "USERPROFILE",
            "WINDIR");
    private final Optional<Path> packagedWebcamAppDirPathOverride;

    WindowsWebcamSandboxPolicy() {
        this(Files::isRegularFile);
    }

    WindowsWebcamSandboxPolicy(Predicate<Path> appContainerLauncherExecutablePredicate) {
        this(appContainerLauncherExecutablePredicate, Optional.empty());
    }

    WindowsWebcamSandboxPolicy(Predicate<Path> appContainerLauncherExecutablePredicate,
                               Optional<Path> packagedWebcamAppDirPathOverride) {
        super(APPCONTAINER_LAUNCHER_FILE_NAME,
                false,
                appContainerLauncherExecutablePredicate,
                "Windows webcam AppContainer launcher is missing");
        this.packagedWebcamAppDirPathOverride = packagedWebcamAppDirPathOverride;
    }

    @Override
    protected Set<String> allowedEnvironmentVariableNames() {
        return ALLOWED_ENVIRONMENT_VARIABLE_NAMES;
    }

    @Override
    public Optional<Path> packagedWebcamAppDirPath() {
        if (packagedWebcamAppDirPathOverride.isPresent()) {
            return packagedWebcamAppDirPathOverride;
        }

        for (Path candidate : packagedWebcamAppDirPathCandidates()) {
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public void configureProcessBuilder(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException {
        Files.createDirectories(context.logFilePath().getParent());
        Path launcherLogFilePath = context.logFilePath().resolveSibling("webcam-launcher.log");
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(launcherLogFilePath.toFile()));
    }

    @Override
    public String logArgument(WebcamLaunchContext context) {
        return "--logToStderr=true";
    }

    @Override
    protected List<String> jvmArguments(WebcamLaunchContext context) {
        // Load the OpenCV/JavaCPP native libraries directly from the read-only packaged webcam dir instead of
        // extracting them to a writable cache. JavaCPP's cache setup canonicalizes the cache path via
        // Path.toRealPath() on Windows (Loader.getCacheDir -> getCanonicalFile), which fails with
        // AccessDeniedException inside the AppContainer: canonicalization needs read/traverse access along the whole
        // path chain up to the volume root, which the container is not granted. Disabling the cache
        // (cacheLibraries=false) skips getCacheDir() entirely, and pathsFirst=true makes findLibrary search
        // java.library.path first and return file: URLs that loadLibrary() System.load()s straight from disk.
        String nativeLibraryPath = context.webcamAppDirPath().toAbsolutePath().normalize().toString();
        return List.of(
                "-Dorg.bytedeco.javacpp.cacheLibraries=false",
                "-Dorg.bytedeco.javacpp.pathsFirst=true",
                "-Djava.library.path=" + nativeLibraryPath);
    }

    @Override
    public void apply(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException {
        super.apply(processBuilder, context);
        // System.load() loads each OpenCV/JavaCPP DLL from the packaged dir by absolute path, but Windows resolves
        // that DLL's own dependent imports (e.g. jniopenblas_nolapack.dll -> libopenblas_nolapack.dll) using the
        // standard search order, which does not include the loaded module's own directory. Put the packaged webcam
        // dir on PATH so those co-located dependents resolve. The dir is already AppContainer-read-granted, so this
        // does not widen the sandbox.
        String nativeLibraryPath = context.webcamAppDirPath().toAbsolutePath().normalize().toString();
        Map<String, String> environment = processBuilder.environment();
        String existingPath = environment.get("PATH");
        String newPath = existingPath == null || existingPath.isBlank()
                ? nativeLibraryPath
                : nativeLibraryPath + File.pathSeparator + existingPath;
        environment.put("PATH", newPath);
    }

    @Override
    protected void addLauncherArguments(List<String> wrappedCommand, WebcamLaunchContext context) {
        wrappedCommand.add("--profile-name");
        wrappedCommand.add(APPCONTAINER_PROFILE_NAME);
        wrappedCommand.add("--capability");
        wrappedCommand.add(WEBCAM_CAPABILITY_NAME);
        wrappedCommand.add("--grant-read");
        wrappedCommand.add(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().toString());
        wrappedCommand.add("--grant-read");
        wrappedCommand.add(context.webcamAppDirPath().toAbsolutePath().normalize().toString());
        wrappedCommand.add("--appcontainer-storage-scope");
        wrappedCommand.add(context.appName());
        wrappedCommand.add("--javacpp-cache-scope");
        wrappedCommand.add(JAVACPP_CACHE_SCOPE_PREFIX + context.webcamAppVersion());
    }

    private List<Path> packagedWebcamAppDirPathCandidates() {
        List<Path> candidates = new ArrayList<>();
        ProcessHandle.current()
                .info()
                .command()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::getParent)
                .ifPresent(path -> addAppContentCandidates(candidates, path));

        Path javaHomePath = Path.of(System.getProperty("java.home")).toAbsolutePath();
        Path javaHomeParentPath = javaHomePath.getParent();
        if (javaHomeParentPath != null) {
            addAppContentCandidates(candidates, javaHomeParentPath);
        }
        return List.copyOf(candidates);
    }

    private void addAppContentCandidates(List<Path> candidates, Path appImageDirPath) {
        Path appDirPath = appImageDirPath.resolve(APP_CONTENT_DIR_NAME).resolve(WEBCAM_APP_CONTENT_DIR_NAME);
        Path rootDirPath = appImageDirPath.resolve(WEBCAM_APP_CONTENT_DIR_NAME);
        addCandidate(candidates, appDirPath);
        addCandidate(candidates, rootDirPath);
    }

    private void addCandidate(List<Path> candidates, Path candidate) {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!candidates.contains(normalizedCandidate)) {
            candidates.add(normalizedCandidate);
        }
    }
}
