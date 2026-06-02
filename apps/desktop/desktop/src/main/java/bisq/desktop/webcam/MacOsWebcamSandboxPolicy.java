/**
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MacOsWebcamSandboxPolicy extends BaselineWebcamSandboxPolicy {
    static final String HELPER_APP_DIR_NAME = "BisqWebcam.app";
    static final String HELPER_EXECUTABLE_NAME = "BisqWebcam";
    private static final String APP_CONTENT_DIR_NAME = "app";
    private static final String CONTENTS_DIR_NAME = "Contents";
    private static final String MACOS_DIR_NAME = "MacOS";

    private final Optional<Path> helperExecutablePathOverride;

    MacOsWebcamSandboxPolicy() {
        this(Optional.empty());
    }

    MacOsWebcamSandboxPolicy(Path helperExecutablePath) {
        this(Optional.of(helperExecutablePath));
    }

    private MacOsWebcamSandboxPolicy(Optional<Path> helperExecutablePathOverride) {
        this.helperExecutablePathOverride = helperExecutablePathOverride;
    }

    @Override
    public List<String> createProcessCommand(String javaExecutablePath,
                                             Path jarFilePath,
                                             List<String> webcamAppArguments,
                                             WebcamLaunchContext context) throws IOException {
        Path helperExecutablePath = helperExecutablePathOverride.orElseGet(this::packagedHelperExecutablePath);
        if (!Files.isRegularFile(helperExecutablePath) || !Files.isExecutable(helperExecutablePath)) {
            throw new IOException("macOS webcam helper app is missing or not executable: " + helperExecutablePath.toAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(helperExecutablePath.toAbsolutePath().toString());
        command.addAll(webcamAppArguments);
        return List.copyOf(command);
    }

    @Override
    public String logArgument(WebcamLaunchContext context) {
        return "--logToStderr=true";
    }

    @Override
    public void configureProcessBuilder(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException {
        Files.createDirectories(context.logFilePath().getParent());
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(Path.of(context.logFilePath().toAbsolutePath() + ".log").toFile()));
    }

    private Path packagedHelperExecutablePath() {
        String command = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow(() -> new IllegalStateException("Cannot locate current process executable"));
        Path contentsPath = findContentsPath(Path.of(command))
                .orElseThrow(() -> new IllegalStateException("Current process is not running from a macOS app bundle: " + command));
        return contentsPath.resolve(APP_CONTENT_DIR_NAME)
                .resolve(HELPER_APP_DIR_NAME)
                .resolve(CONTENTS_DIR_NAME)
                .resolve(MACOS_DIR_NAME)
                .resolve(HELPER_EXECUTABLE_NAME);
    }

    private Optional<Path> findContentsPath(Path path) {
        Path currentPath = path.toAbsolutePath();
        while (currentPath != null) {
            Path fileName = currentPath.getFileName();
            if (fileName != null && CONTENTS_DIR_NAME.equals(fileName.toString())) {
                return Optional.of(currentPath);
            }
            currentPath = currentPath.getParent();
        }
        return Optional.empty();
    }
}
