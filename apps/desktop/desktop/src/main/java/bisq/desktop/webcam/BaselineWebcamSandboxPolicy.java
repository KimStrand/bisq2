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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class BaselineWebcamSandboxPolicy implements WebcamSandboxPolicy {
    private static final Set<String> COMMON_ALLOWED_ENVIRONMENT_VARIABLE_NAMES = Set.of(
            "HOME",
            "JAVA_HOME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "PATH",
            "TEMP",
            "TMP",
            "TMPDIR");

    @Override
    public void apply(ProcessBuilder processBuilder, WebcamSandboxContext context) throws IOException {
        Files.createDirectories(context.webcamDirPath());
        processBuilder.directory(context.webcamDirPath().toFile());
        retainAllowedEnvironment(processBuilder.environment(), allowedEnvironmentVariableNames());
    }

    protected Set<String> allowedEnvironmentVariableNames() {
        return COMMON_ALLOWED_ENVIRONMENT_VARIABLE_NAMES;
    }

    protected static Set<String> allowedEnvironmentVariableNames(String... additionalNames) {
        Set<String> names = new HashSet<>(COMMON_ALLOWED_ENVIRONMENT_VARIABLE_NAMES);
        names.addAll(Arrays.asList(additionalNames));
        return Collections.unmodifiableSet(names);
    }

    private void retainAllowedEnvironment(Map<String, String> environment, Set<String> allowedNames) {
        environment.keySet().removeIf(name -> !isAllowedEnvironmentVariable(name, allowedNames));
    }

    private boolean isAllowedEnvironmentVariable(String name, Set<String> allowedNames) {
        return allowedNames.stream().anyMatch(allowedName -> allowedName.equalsIgnoreCase(name));
    }
}
