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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

abstract class NativeWebcamLauncherSandboxPolicy extends BaselineWebcamSandboxPolicy {
    private final String launcherFileName;
    private final boolean launcherRequiresExecutableBit;
    private final Predicate<Path> launcherExecutablePredicate;
    private final String missingLauncherMessagePrefix;

    NativeWebcamLauncherSandboxPolicy(String launcherFileName,
                                      boolean launcherRequiresExecutableBit,
                                      Predicate<Path> launcherExecutablePredicate,
                                      String missingLauncherMessagePrefix) {
        this.launcherFileName = launcherFileName;
        this.launcherRequiresExecutableBit = launcherRequiresExecutableBit;
        this.launcherExecutablePredicate = launcherExecutablePredicate;
        this.missingLauncherMessagePrefix = missingLauncherMessagePrefix;
    }

    @Override
    public final List<String> createProcessCommand(String javaExecutablePath,
                                                   Path jarFilePath,
                                                   List<String> webcamAppArguments,
                                                   WebcamLaunchContext context) throws IOException {
        Path launcherPath = context.webcamDirPath().resolve(launcherFileName);
        if (!launcherExecutablePredicate.test(launcherPath)) {
            throw new IOException(missingLauncherMessagePrefix + ": " + launcherPath.toAbsolutePath());
        }

        List<String> webcamCommand = super.createProcessCommand(javaExecutablePath, jarFilePath, webcamAppArguments, context);
        List<String> command = new ArrayList<>(webcamCommand.size() + 16);
        command.add(launcherPath.toAbsolutePath().toString());
        addLauncherArguments(command, context);
        command.add("--");
        command.addAll(webcamCommand);
        return List.copyOf(command);
    }

    @Override
    public final Optional<SandboxLauncherResource> sandboxLauncherResource() {
        return Optional.of(new SandboxLauncherResource(launcherFileName, launcherRequiresExecutableBit));
    }

    @Override
    protected List<String> jvmArguments(WebcamLaunchContext context) throws IOException {
        Path webcamHomePath = context.webcamDirPath().resolve("home");
        Path webcamTempPath = context.webcamDirPath().resolve("tmp");
        Path javaCppCachePath = context.webcamDirPath().resolve("javacpp-cache");
        Files.createDirectories(webcamHomePath);
        Files.createDirectories(webcamTempPath);
        Files.createDirectories(javaCppCachePath);
        return List.of(
                "-Duser.home=" + webcamHomePath.toAbsolutePath(),
                "-Djava.io.tmpdir=" + webcamTempPath.toAbsolutePath(),
                "-Dorg.bytedeco.javacpp.cachedir=" + javaCppCachePath.toAbsolutePath());
    }

    protected abstract void addLauncherArguments(List<String> wrappedCommand, WebcamLaunchContext context) throws IOException;
}
