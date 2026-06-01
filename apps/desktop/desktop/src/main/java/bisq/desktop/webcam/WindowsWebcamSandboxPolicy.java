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

import java.util.Set;

final class WindowsWebcamSandboxPolicy extends BaselineWebcamSandboxPolicy {
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

    @Override
    protected Set<String> allowedEnvironmentVariableNames() {
        return ALLOWED_ENVIRONMENT_VARIABLE_NAMES;
    }
}
