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
        Path webcamDirPath = tempDir.resolve("webcam");
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");

        new BaselineWebcamSandboxPolicy().apply(processBuilder, context(webcamDirPath));

        assertTrue(Files.isDirectory(webcamDirPath));
        assertEquals(webcamDirPath.toFile(), processBuilder.directory());
    }

    @Test
    void createsProcessBuilderFromCommand() throws Exception {
        Path webcamDirPath = tempDir.resolve("webcam");
        List<String> command = List.of("java", "-version");

        ProcessBuilder processBuilder = new BaselineWebcamSandboxPolicy().createProcessBuilder(command, context(webcamDirPath));

        assertEquals(command, processBuilder.command());
        assertEquals(webcamDirPath.toFile(), processBuilder.directory());
    }

    @Test
    void linuxCommandWrapperFailsWhenSandboxLauncherIsMissing() {
        List<String> command = List.of("java", "-jar", "webcam-app.jar");

        IOException exception = assertThrows(IOException.class,
                () -> new LinuxWebcamSandboxPolicy(path -> false)
                        .wrapCommand(command, context(tempDir.resolve("webcam"))));

        assertTrue(exception.getMessage().contains("Linux webcam sandbox launcher is missing or not executable"));
    }

    @Test
    void linuxCommandWrapperPrependsSandboxLauncherAndLandlockRootsWhenExecutableLauncherExists() throws Exception {
        Path webcamDirPath = tempDir.resolve("webcam");
        Path sandboxLauncherPath = webcamDirPath.resolve(LinuxWebcamSandboxPolicy.SANDBOX_LAUNCHER_FILE_NAME);
        List<String> command = List.of("java", "-jar", "webcam-app.jar");
        LinuxWebcamSandboxPolicy policy = new LinuxWebcamSandboxPolicy(path -> path.equals(sandboxLauncherPath));

        List<String> wrappedCommand = policy.wrapCommand(command, context(webcamDirPath));

        int commandSeparatorIndex = wrappedCommand.indexOf("--");
        assertEquals(sandboxLauncherPath.toAbsolutePath().toString(), wrappedCommand.get(0));
        assertTrue(wrappedCommand.contains("--read-root"));
        assertTrue(wrappedCommand.contains(webcamDirPath.toAbsolutePath().normalize().toString()));
        assertTrue(wrappedCommand.contains("--write-root"));
        assertTrue(commandSeparatorIndex > 0);
        assertEquals(command, wrappedCommand.subList(commandSeparatorIndex + 1, wrappedCommand.size()));
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
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        environment.put("DISPLAY", ":0");
        environment.put("WAYLAND_DISPLAY", "wayland-0");
        environment.put("XDG_RUNTIME_DIR", "/run/user/1000");
        environment.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus");
        environment.put("BISQ_SECRET_TOKEN", "secret");

        new LinuxWebcamSandboxPolicy().apply(processBuilder, context(tempDir.resolve("webcam")));

        assertTrue(environment.containsKey("DISPLAY"));
        assertTrue(environment.containsKey("WAYLAND_DISPLAY"));
        assertTrue(environment.containsKey("XDG_RUNTIME_DIR"));
        assertTrue(environment.containsKey("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals(tempDir.resolve("webcam").resolve("home").toAbsolutePath().toString(), environment.get("HOME"));
        assertFalse(environment.containsKey("BISQ_SECRET_TOKEN"));
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

    private WebcamSandboxContext context(Path webcamDirPath) {
        return new WebcamSandboxContext(webcamDirPath, webcamDirPath.resolve("webcam-app"));
    }
}
