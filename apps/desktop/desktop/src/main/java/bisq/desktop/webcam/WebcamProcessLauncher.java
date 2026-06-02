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

import bisq.common.file.FileMutatorUtils;
import bisq.common.locale.LanguageRepository;
import bisq.common.platform.OS;
import bisq.common.threading.ExecutorFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static bisq.common.threading.ExecutorFactory.commonForkJoinPool;

@Slf4j
public class WebcamProcessLauncher {
    private final Path webcamDirPath;
    private final WebcamAppResourceProvider webcamAppResourceProvider;
    private final WebcamSandboxPolicy sandboxPolicy;
    private Optional<Process> runningProcess = Optional.empty();

    public WebcamProcessLauncher(Path appDataDirPath) {
        this.webcamDirPath = appDataDirPath.resolve("webcam");
        this.webcamAppResourceProvider = new WebcamAppResourceProvider(webcamDirPath);
        this.sandboxPolicy = WebcamSandboxPolicy.create();
    }

    public CompletableFuture<Process> start(String sessionSecret) {
        ExecutorService launchExecutor = ExecutorFactory.newSingleThreadExecutor("WebcamProcessLauncher");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path jarFilePath = webcamAppResourceProvider.prepareWebcamAppResources();

                Path logFilePath = webcamDirPath.resolve("webcam-app");
                String logFileParam = OS.isMacOs()
                        ? "--logToStderr=true"
                        : "--logFile=" + URLEncoder.encode(logFilePath.toAbsolutePath().toString(), StandardCharsets.UTF_8);
                String languageTagParam = "--languageTag=" + LanguageRepository.getDefaultLanguageTag();

                String pathToJavaExe = System.getProperty("java.home") + "/bin/java";
                List<String> command = createWebcamAppCommand(pathToJavaExe, jarFilePath, logFileParam, languageTagParam);
                WebcamSandboxContext sandboxContext = new WebcamSandboxContext(webcamDirPath, logFilePath);
                ProcessBuilder processBuilder = sandboxPolicy.createProcessBuilder(command, sandboxContext);
                if (OS.isMacOs()) {
                    Files.createDirectories(logFilePath.getParent());
                    processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(Path.of(logFilePath.toAbsolutePath().toString() + ".log").toFile()));
                } else {
                    processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
                }
                log.info("Launching webcam app process");
                Process process = processBuilder.start();
                sendSessionSecret(process, sessionSecret);
                setRunningProcess(process);
                log.info("Webcam app process successfully launched");
                return process;
            } catch (Exception e) {
                log.error("Launching process failed", e);
                throw new RuntimeException(e);
            }
        }, launchExecutor).whenComplete((process, throwable) -> launchExecutor.shutdown());
    }

    private List<String> createWebcamAppCommand(String pathToJavaExe,
                                                Path jarFilePath,
                                                String logFileParam,
                                                String languageTagParam) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(pathToJavaExe);
        if (OS.isMacOs()) {
            String iconPath = webcamDirPath + "/webcam-app-icon.png";
            Path bisqIconPath = Paths.get(iconPath);
            if (!Files.exists(bisqIconPath)) {
                FileMutatorUtils.resourceToFile("images/webcam/webcam-app-icon@2x.png", bisqIconPath);
            }
            command.add("-Xdock:icon=" + iconPath);
        }
        if (OS.isLinux()) {
            Path webcamHomePath = webcamDirPath.resolve("home");
            Path webcamTempPath = webcamDirPath.resolve("tmp");
            Path javaCppCachePath = webcamDirPath.resolve("javacpp-cache");
            Files.createDirectories(webcamHomePath);
            Files.createDirectories(webcamTempPath);
            Files.createDirectories(javaCppCachePath);
            command.add("-Duser.home=" + webcamHomePath.toAbsolutePath());
            command.add("-Djava.io.tmpdir=" + webcamTempPath.toAbsolutePath());
            command.add("-Dorg.bytedeco.javacpp.cachedir=" + javaCppCachePath.toAbsolutePath());
        }
        command.add("-jar");
        command.add(jarFilePath.toAbsolutePath().toString());
        command.add(logFileParam);
        command.add(languageTagParam);
        return command;
    }

    private void sendSessionSecret(Process process, String sessionSecret) {
        // Child stdin is reserved for this one-shot IPC secret bootstrap.
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(sessionSecret);
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            process.destroyForcibly();
            throw new RuntimeException("Sending webcam IPC session secret failed", e);
        }
    }

    public CompletableFuture<Boolean> shutdown() {
        Optional<Process> processToShutdown = getAndClearRunningProcess();
        return CompletableFuture.supplyAsync(() -> processToShutdown.map(process -> {
            log.info("Shutting down webcam app process");
            process.destroy();
            boolean terminatedGracefully = false;
            try {
                terminatedGracefully = process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Thread got interrupted at shutdown", e);
                Thread.currentThread().interrupt(); // Restore interrupted state
            }

            if (process.isAlive()) {
                log.warn("Stopping webcam app process gracefully did not terminate it. We destroy it forcibly.");
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    log.warn("Thread got interrupted while waiting after forced shutdown", e);
                    Thread.currentThread().interrupt();
                }
                terminatedGracefully = false;
            }
            return terminatedGracefully;
        }).orElse(true), commonForkJoinPool());
    }

    private synchronized void setRunningProcess(Process process) {
        runningProcess = Optional.of(process);
    }

    private synchronized Optional<Process> getAndClearRunningProcess() {
        Optional<Process> process = runningProcess;
        runningProcess = Optional.empty();
        return process;
    }
}
