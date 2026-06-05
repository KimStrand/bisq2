/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebcamSandboxPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesBaselineWorkingDirectory() throws Exception {
        Path webcamAppDirPath = tempDir.resolve("webcam-app");
        Path webcamDataDirPath = tempDir.resolve("webcam-data");
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");

        new BaselineWebcamSandboxPolicy().apply(processBuilder, context(webcamAppDirPath, webcamDataDirPath));

        assertFalse(Files.exists(webcamAppDirPath));
        assertTrue(Files.isDirectory(webcamDataDirPath));
        assertEquals(webcamDataDirPath.toFile(), processBuilder.directory());
    }

    @Test
    void createsProcessBuilderFromCommand() throws Exception {
        Path webcamAppDirPath = tempDir.resolve("webcam-app");
        Path webcamDataDirPath = tempDir.resolve("webcam-data");
        List<String> command = List.of("java", "-version");

        ProcessBuilder processBuilder = new BaselineWebcamSandboxPolicy().createProcessBuilder(command,
                context(webcamAppDirPath, webcamDataDirPath));

        assertEquals(command, processBuilder.command());
        assertEquals(webcamDataDirPath.toFile(), processBuilder.directory());
    }

    @Test
    void linuxProcessCommandFailsWhenSandboxLauncherIsMissing() {
        IOException exception = assertThrows(IOException.class,
                () -> new LinuxWebcamSandboxPolicy(path -> false)
                        .createProcessCommand("java",
                                Path.of("webcam-app.jar"),
                                List.of(),
                                context(tempDir.resolve("webcam"))));

        assertTrue(exception.getMessage().contains("Linux webcam sandbox launcher is missing or not executable"));
    }

    @Test
    void linuxProcessCommandPrependsSandboxLauncherAndLandlockRootsWhenExecutableLauncherExists() throws Exception {
        Path webcamAppDirPath = tempDir.resolve("webcam-app");
        Path webcamDataDirPath = tempDir.resolve("webcam-data");
        Path sandboxLauncherPath = webcamAppDirPath.resolve(LinuxWebcamSandboxPolicy.SANDBOX_LAUNCHER_FILE_NAME);
        Path jarFilePath = Path.of("webcam-app.jar");
        List<String> webcamAppArguments = List.of("--logFile=log", "--languageTag=en");
        LinuxWebcamSandboxPolicy policy = new LinuxWebcamSandboxPolicy(path -> path.equals(sandboxLauncherPath));

        List<String> command = policy.createProcessCommand("java",
                jarFilePath,
                webcamAppArguments,
                context(webcamAppDirPath, webcamDataDirPath));

        int commandSeparatorIndex = command.indexOf("--");
        assertEquals(sandboxLauncherPath.toAbsolutePath().toString(), command.get(0));
        assertTrue(command.contains("--read-root"));
        assertTrue(command.contains(webcamAppDirPath.toAbsolutePath().normalize().toString()));
        assertTrue(command.contains("--write-root"));
        assertTrue(command.contains(webcamDataDirPath.toAbsolutePath().normalize().toString()));
        assertTrue(commandSeparatorIndex > 0);
        assertEquals(List.of(
                "java",
                "-Duser.home=" + webcamDataDirPath.resolve("home").toAbsolutePath(),
                "-Djava.io.tmpdir=" + webcamDataDirPath.resolve("tmp").toAbsolutePath(),
                "-Dorg.bytedeco.javacpp.cachedir=" + webcamDataDirPath.resolve("javacpp-cache").toAbsolutePath(),
                "-jar",
                jarFilePath.toAbsolutePath().toString(),
                "--logFile=log",
                "--languageTag=en"), command.subList(commandSeparatorIndex + 1, command.size()));
    }

    @Test
    void linuxPolicyExposesExecutableSandboxLauncherResource() {
        WebcamSandboxPolicy.SandboxLauncherResource resource = new LinuxWebcamSandboxPolicy(path -> true)
                .sandboxLauncherResource()
                .orElseThrow();

        assertEquals(LinuxWebcamSandboxPolicy.SANDBOX_LAUNCHER_FILE_NAME, resource.fileName());
        assertTrue(resource.requiresExecutableBit());
    }

    @Test
    void windowsPolicyExposesNonExecutableAppContainerLauncherResource() {
        WebcamSandboxPolicy.SandboxLauncherResource resource = new WindowsWebcamSandboxPolicy(path -> true)
                .sandboxLauncherResource()
                .orElseThrow();

        assertEquals(WindowsWebcamSandboxPolicy.APPCONTAINER_LAUNCHER_FILE_NAME, resource.fileName());
        assertFalse(resource.requiresExecutableBit());
    }

    @Test
    void macOsPolicyUsesStderrLogArgument() {
        assertEquals("--logToStderr=true", new MacOsWebcamSandboxPolicy(tempDir.resolve("helper"))
                .logArgument(context(tempDir.resolve("webcam"))));
    }

    @Test
    void windowsPolicyUsesStderrLogArgument() {
        assertEquals("--logToStderr=true", new WindowsWebcamSandboxPolicy(path -> true)
                .logArgument(context(tempDir.resolve("webcam"))));
    }

    @Test
    void removesEnvironmentVariablesOutsideAllowlist() throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        environment.put("PATH", "/usr/bin");
        environment.put("BISQ_SECRET_TOKEN", "secret");

        new BaselineWebcamSandboxPolicy().apply(processBuilder, context(tempDir.resolve("webcam")));

        assertTrue(environment.containsKey("PATH"));
        assertFalse(environment.containsKey("BISQ_SECRET_TOKEN"));
    }

    @Test
    void preservesLinuxGuiEnvironmentVariables() throws Exception {
        Path webcamDataDirPath = tempDir.resolve("webcam-data");
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        environment.put("DISPLAY", ":0");
        environment.put("WAYLAND_DISPLAY", "wayland-0");
        environment.put("XDG_RUNTIME_DIR", "/run/user/1000");
        environment.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus");
        environment.put("BISQ_SECRET_TOKEN", "secret");

        new LinuxWebcamSandboxPolicy().apply(processBuilder, context(tempDir.resolve("webcam-app"), webcamDataDirPath));

        assertTrue(environment.containsKey("DISPLAY"));
        assertTrue(environment.containsKey("WAYLAND_DISPLAY"));
        assertTrue(environment.containsKey("XDG_RUNTIME_DIR"));
        assertTrue(environment.containsKey("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals(webcamDataDirPath.resolve("home").toAbsolutePath().toString(), environment.get("HOME"));
        assertFalse(environment.containsKey("BISQ_SECRET_TOKEN"));
    }

    @Test
    void macOsProcessCommandUsesPackagedHelperApp() throws Exception {
        Path helperExecutablePath = tempDir.resolve("BisqWebcam.app")
                .resolve("Contents")
                .resolve("MacOS")
                .resolve("BisqWebcam");
        Files.createDirectories(helperExecutablePath.getParent());
        Files.writeString(helperExecutablePath, "helper");
        assertTrue(helperExecutablePath.toFile().setExecutable(true, true));
        List<String> webcamAppArguments = List.of("--logToStderr=true", "--languageTag=en");

        List<String> command = new MacOsWebcamSandboxPolicy(helperExecutablePath)
                .createProcessCommand("java",
                        Path.of("webcam-app.jar"),
                        webcamAppArguments,
                        context(tempDir.resolve("webcam")));

        assertEquals(List.of(
                helperExecutablePath.toAbsolutePath().toString(),
                "--logToStderr=true",
                "--languageTag=en"), command);
    }

    @Test
    void macOsProcessCommandFailsWhenHelperAppIsMissing() {
        List<String> webcamAppArguments = List.of("--logToStderr=true");

        IOException exception = assertThrows(IOException.class,
                () -> new MacOsWebcamSandboxPolicy(tempDir.resolve("missing-helper"))
                        .createProcessCommand("java",
                                Path.of("webcam-app.jar"),
                                webcamAppArguments,
                                context(tempDir.resolve("webcam"))));

        assertTrue(exception.getMessage().contains("macOS webcam helper app is missing or not executable"));
    }

    @Test
    void windowsProcessCommandFailsWhenAppContainerLauncherIsMissing() {
        IOException exception = assertThrows(IOException.class,
                () -> new WindowsWebcamSandboxPolicy(path -> false)
                        .createProcessCommand("java",
                                Path.of("webcam-app.jar"),
                                List.of(),
                                context(tempDir.resolve("webcam"))));

        assertTrue(exception.getMessage().contains("Windows webcam AppContainer launcher is missing"));
    }

    @Test
    void windowsProcessCommandPrependsAppContainerLauncherWhenExecutableLauncherExists() throws Exception {
        Path webcamAppDirPath = tempDir.resolve("webcam-app");
        Path webcamDataDirPath = tempDir.resolve("webcam-data");
        Path appContainerLauncherPath = webcamAppDirPath.resolve(WindowsWebcamSandboxPolicy.APPCONTAINER_LAUNCHER_FILE_NAME);
        Path jarFilePath = Path.of("webcam-app.jar");
        List<String> webcamAppArguments = List.of("--logToStderr=true", "--languageTag=en");
        WindowsWebcamSandboxPolicy policy = new WindowsWebcamSandboxPolicy(path -> path.equals(appContainerLauncherPath));

        List<String> command = policy.createProcessCommand("java",
                jarFilePath,
                webcamAppArguments,
                context(webcamAppDirPath, webcamDataDirPath));

        int commandSeparatorIndex = command.indexOf("--");
        assertEquals(appContainerLauncherPath.toAbsolutePath().toString(), command.get(0));
        assertTrue(command.contains("--profile-name"));
        assertTrue(command.contains("bisq.webcam"));
        assertTrue(command.contains("--capability"));
        assertTrue(command.contains("webcam"));
        assertTrue(command.contains("--grant-read"));
        assertFalse(command.contains("--grant-write"));
        assertTrue(command.contains(webcamAppDirPath.toAbsolutePath().normalize().toString()));
        assertFalse(command.contains(webcamDataDirPath.toAbsolutePath().normalize().toString()));
        assertTrue(command.contains("--appcontainer-storage-scope"));
        assertTrue(command.contains("bisq-test"));
        assertTrue(command.contains("--javacpp-cache-scope"));
        assertTrue(command.contains("webcam-app-1.0.0"));
        assertTrue(commandSeparatorIndex > 0);
        assertEquals(List.of(
                "java",
                "-jar",
                jarFilePath.toAbsolutePath().toString(),
                "--logToStderr=true",
                "--languageTag=en"), command.subList(commandSeparatorIndex + 1, command.size()));
    }

    @Test
    void matchesAllowedEnvironmentVariablesCaseInsensitively() throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        environment.put("Path", "/usr/bin");
        environment.put("SystemRoot", "C:\\Windows");
        environment.put("BISQ_SECRET_TOKEN", "secret");

        new WindowsWebcamSandboxPolicy().apply(processBuilder, context(tempDir.resolve("webcam")));

        assertTrue(environment.containsKey("Path"));
        assertTrue(environment.containsKey("SystemRoot"));
        assertFalse(environment.containsKey("BISQ_SECRET_TOKEN"));
    }

    private WebcamLaunchContext context(Path webcamDirPath) {
        return context(webcamDirPath, webcamDirPath);
    }

    private WebcamLaunchContext context(Path webcamAppDirPath, Path webcamDataDirPath) {
        return new WebcamLaunchContext(webcamAppDirPath,
                webcamDataDirPath,
                webcamDataDirPath.resolve("webcam-app"),
                "bisq-test",
                "1.0.0");
    }
}
