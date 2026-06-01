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
import java.util.List;

interface WebcamSandboxPolicy {
    static WebcamSandboxPolicy create() {
        OS os = OS.getOS();
        switch (os) {
            case LINUX:
                return new LinuxWebcamSandboxPolicy();
            case WINDOWS:
                return new WindowsWebcamSandboxPolicy();
            default:
                return new BaselineWebcamSandboxPolicy();
        }
    }

    default ProcessBuilder createProcessBuilder(List<String> command, WebcamSandboxContext context) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(wrapCommand(command, context));
        apply(processBuilder, context);
        return processBuilder;
    }

    default List<String> wrapCommand(List<String> command, WebcamSandboxContext context) throws IOException {
        return List.copyOf(command);
    }

    void apply(ProcessBuilder processBuilder, WebcamSandboxContext context) throws IOException;
}
