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

package bisq.common.threading;

public class ThreadName {
    // ThreadLocal is not shared across different threads.
    private static final ThreadLocal<String> originalNameThreadLocal = new ThreadLocal<>();

    public static void set(Object host) {
        set(host.getClass());
    }

    public static void set(Object host, String details) {
        set(host.getClass(), details);
    }

    public static void set(Class<?> hostClass) {
        setName(hostClass.getSimpleName());
    }

    public static void set(Class<?> hostClass, String details) {
        set(hostClass.getSimpleName(), details);
    }

    public static void set(String hostName, String details) {
        setName(hostName + "." + details);
    }

    public static void setName(String name) {
        Thread.currentThread().setName(getOriginalName() + ":" + name);
    }

    public static String getOriginalName() {
        String originalName = originalNameThreadLocal.get();
        if (originalName == null) {
            originalName = Thread.currentThread().getName();
            originalNameThreadLocal.set(originalName);
        }
        return originalName;
    }
}
