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

import bisq.common.platform.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

interface WebcamSandboxPolicy {
    record SandboxLauncherResource(String fileName, boolean requiresExecutableBit) {
    }

    static WebcamSandboxPolicy create() {
        OS os = OS.getOS();
        switch (os) {
            case LINUX:
                return new LinuxWebcamSandboxPolicy();
            case MAC_OS:
                return new MacOsWebcamSandboxPolicy();
            case WINDOWS:
                return new WindowsWebcamSandboxPolicy();
            default:
                return new BaselineWebcamSandboxPolicy();
        }
    }

    List<String> createProcessCommand(String javaExecutablePath,
                                      Path jarFilePath,
                                      List<String> webcamAppArguments,
                                      WebcamLaunchContext context) throws IOException;

    default ProcessBuilder createProcessBuilder(List<String> command, WebcamLaunchContext context) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        apply(processBuilder, context);
        configureProcessBuilder(processBuilder, context);
        return processBuilder;
    }

    default Optional<SandboxLauncherResource> sandboxLauncherResource() {
        return Optional.empty();
    }

    String logArgument(WebcamLaunchContext context);

    void configureProcessBuilder(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException;

    void apply(ProcessBuilder processBuilder, WebcamLaunchContext context) throws IOException;
}
