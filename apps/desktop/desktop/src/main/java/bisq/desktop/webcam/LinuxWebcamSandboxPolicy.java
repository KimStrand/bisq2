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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class LinuxWebcamSandboxPolicy extends NativeWebcamLauncherSandboxPolicy {
    static final String SANDBOX_LAUNCHER_FILE_NAME = "bisq-webcam-sandbox-launcher";
    private static final Set<String> ALLOWED_ENVIRONMENT_VARIABLE_NAMES = allowedEnvironmentVariableNames(
            "DBUS_SESSION_BUS_ADDRESS",
            "DESKTOP_SESSION",
            "DISPLAY",
            "GDK_BACKEND",
            "GTK_IM_MODULE",
            "QT_IM_MODULE",
            "QT_QPA_PLATFORM",
            "WAYLAND_DISPLAY",
            "XAUTHORITY",
            "XDG_CURRENT_DESKTOP",
            "XDG_RUNTIME_DIR",
            "XDG_SESSION_TYPE",
            "XMODIFIERS");

    LinuxWebcamSandboxPolicy() {
        this(path -> Files.isRegularFile(path) && Files.isExecutable(path));
    }

    LinuxWebcamSandboxPolicy(Predicate<Path> sandboxLauncherExecutablePredicate) {
        super(SANDBOX_LAUNCHER_FILE_NAME,
                true,
                sandboxLauncherExecutablePredicate,
                "Linux webcam sandbox launcher is missing or not executable");
    }

    @Override
    public void apply(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException {
        super.apply(processBuilder, context);
        Path sandboxHomePath = context.webcamDirPath().resolve("home");
        Files.createDirectories(sandboxHomePath);
        processBuilder.environment().put("HOME", sandboxHomePath.toAbsolutePath().toString());
    }

    @Override
    protected Set<String> allowedEnvironmentVariableNames() {
        return ALLOWED_ENVIRONMENT_VARIABLE_NAMES;
    }

    @Override
    protected void addLauncherArguments(List<String> wrappedCommand, WebcamLaunchContext context) {
        addLandlockReadRootArguments(wrappedCommand, context);
        addLandlockWriteRootArguments(wrappedCommand, context);
    }

    private void addLandlockReadRootArguments(List<String> wrappedCommand, WebcamLaunchContext context) {
        LinkedHashSet<Path> readRoots = new LinkedHashSet<>();
        readRoots.add(context.webcamDirPath());
        readRoots.add(Path.of(System.getProperty("java.home")));

        addExistingPath(readRoots, "/bin");
        addExistingPath(readRoots, "/sbin");
        addExistingPath(readRoots, "/etc");
        addExistingPath(readRoots, "/usr");
        addExistingPath(readRoots, "/lib");
        addExistingPath(readRoots, "/lib64");
        addExistingPath(readRoots, "/opt");
        addExistingPath(readRoots, "/nix/store");
        addExistingPath(readRoots, "/snap");
        addExistingPath(readRoots, "/app");
        addExistingPath(readRoots, "/proc");
        addExistingPath(readRoots, "/sys");
        addExistingPath(readRoots, "/var/cache");
        addExistingPath(readRoots, "/var/lib/flatpak");

        String xAuthorityPath = System.getenv("XAUTHORITY");
        if (xAuthorityPath != null && !xAuthorityPath.isBlank()) {
            readRoots.add(Path.of(xAuthorityPath));
        }

        readRoots.forEach(path -> addRootArgument(wrappedCommand, "--read-root", path));
    }

    private void addLandlockWriteRootArguments(List<String> wrappedCommand, WebcamLaunchContext context) {
        LinkedHashSet<Path> writeRoots = new LinkedHashSet<>();
        writeRoots.add(context.webcamDirPath());
        addExistingPath(writeRoots, "/dev");
        addExistingPath(writeRoots, "/run");
        addExistingPath(writeRoots, "/tmp");
        addExistingPath(writeRoots, "/var/tmp");

        writeRoots.forEach(path -> addRootArgument(wrappedCommand, "--write-root", path));
    }

    private void addExistingPath(Set<Path> paths, String path) {
        Path resolvedPath = Path.of(path);
        if (Files.exists(resolvedPath)) {
            paths.add(resolvedPath);
        }
    }

    private void addRootArgument(List<String> wrappedCommand, String argumentName, Path path) {
        wrappedCommand.add(argumentName);
        wrappedCommand.add(path.toAbsolutePath().normalize().toString());
    }
}
