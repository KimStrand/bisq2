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
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class WindowsWebcamSandboxPolicy extends NativeWebcamLauncherSandboxPolicy {
    static final String APPCONTAINER_LAUNCHER_FILE_NAME = "bisq-webcam-appcontainer-launcher.exe";
    private static final String APPCONTAINER_PROFILE_NAME = "bisq.webcam";
    private static final String WEBCAM_CAPABILITY_NAME = "webcam";
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

    WindowsWebcamSandboxPolicy() {
        this(Files::isRegularFile);
    }

    WindowsWebcamSandboxPolicy(Predicate<Path> appContainerLauncherExecutablePredicate) {
        super(APPCONTAINER_LAUNCHER_FILE_NAME,
                false,
                appContainerLauncherExecutablePredicate,
                "Windows webcam AppContainer launcher is missing");
    }

    @Override
    protected Set<String> allowedEnvironmentVariableNames() {
        return ALLOWED_ENVIRONMENT_VARIABLE_NAMES;
    }

    @Override
    public void configureProcessBuilder(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException {
        Files.createDirectories(context.logFilePath().getParent());
        Path launcherLogFilePath = context.logFilePath().resolveSibling("webcam-launcher.log");
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(launcherLogFilePath.toFile()));
    }

    @Override
    protected void addLauncherArguments(List<String> wrappedCommand, WebcamLaunchContext context) {
        wrappedCommand.add("--profile-name");
        wrappedCommand.add(APPCONTAINER_PROFILE_NAME);
        wrappedCommand.add("--capability");
        wrappedCommand.add(WEBCAM_CAPABILITY_NAME);
        wrappedCommand.add("--grant-read");
        wrappedCommand.add(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().toString());
        wrappedCommand.add("--grant-write");
        wrappedCommand.add(context.webcamDirPath().toAbsolutePath().normalize().toString());
    }
}
