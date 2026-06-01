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

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef LANDLOCK_CREATE_RULESET_VERSION
#define LANDLOCK_CREATE_RULESET_VERSION (1U << 0)
#endif

#ifndef LANDLOCK_RULE_PATH_BENEATH
#define LANDLOCK_RULE_PATH_BENEATH 1
#endif

#ifndef LANDLOCK_ACCESS_FS_EXECUTE
#define LANDLOCK_ACCESS_FS_EXECUTE (1ULL << 0)
#define LANDLOCK_ACCESS_FS_WRITE_FILE (1ULL << 1)
#define LANDLOCK_ACCESS_FS_READ_FILE (1ULL << 2)
#define LANDLOCK_ACCESS_FS_READ_DIR (1ULL << 3)
#define LANDLOCK_ACCESS_FS_REMOVE_DIR (1ULL << 4)
#define LANDLOCK_ACCESS_FS_REMOVE_FILE (1ULL << 5)
#define LANDLOCK_ACCESS_FS_MAKE_CHAR (1ULL << 6)
#define LANDLOCK_ACCESS_FS_MAKE_DIR (1ULL << 7)
#define LANDLOCK_ACCESS_FS_MAKE_REG (1ULL << 8)
#define LANDLOCK_ACCESS_FS_MAKE_SOCK (1ULL << 9)
#define LANDLOCK_ACCESS_FS_MAKE_FIFO (1ULL << 10)
#define LANDLOCK_ACCESS_FS_MAKE_BLOCK (1ULL << 11)
#define LANDLOCK_ACCESS_FS_MAKE_SYM (1ULL << 12)
#define LANDLOCK_ACCESS_FS_REFER (1ULL << 13)
#define LANDLOCK_ACCESS_FS_TRUNCATE (1ULL << 14)
#endif

#ifndef __NR_landlock_create_ruleset
#if defined(__x86_64__) || defined(__aarch64__)
#define __NR_landlock_create_ruleset 444
#endif
#endif

#ifndef __NR_landlock_add_rule
#if defined(__x86_64__) || defined(__aarch64__)
#define __NR_landlock_add_rule 445
#endif
#endif

#ifndef __NR_landlock_restrict_self
#if defined(__x86_64__) || defined(__aarch64__)
#define __NR_landlock_restrict_self 446
#endif
#endif

#ifndef PR_SET_NO_NEW_PRIVS
#define PR_SET_NO_NEW_PRIVS 38
#endif

#ifndef PR_GET_NO_NEW_PRIVS
#define PR_GET_NO_NEW_PRIVS 39
#endif

#if defined(__x86_64__)
#define SECCOMP_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define SECCOMP_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#error "Unsupported Linux architecture for webcam sandbox launcher seccomp filter"
#endif

#define SECCOMP_DENY_ERRNO(error_number) (SECCOMP_RET_ERRNO | ((error_number) & SECCOMP_RET_DATA))
#define MAX_LANDLOCK_ROOTS 64

struct bisq_landlock_ruleset_attr {
    uint64_t handled_access_fs;
    uint64_t handled_access_net;
};

struct bisq_landlock_path_beneath_attr {
    uint64_t allowed_access;
    int32_t parent_fd;
} __attribute__((packed));

struct launcher_options {
    const char *read_roots[MAX_LANDLOCK_ROOTS];
    size_t read_root_count;
    const char *write_roots[MAX_LANDLOCK_ROOTS];
    size_t write_root_count;
    int command_index;
    bool has_configured_landlock_roots;
};

static void print_usage(void) {
    fputs("Usage: bisq-webcam-sandbox-launcher [--read-root <path>] [--write-root <path>] -- <command> [args...]\n",
          stderr);
    fputs("       bisq-webcam-sandbox-launcher <command> [args...]\n", stderr);
}

static int add_read_root(struct launcher_options *options, const char *path) {
    if (options->read_root_count >= MAX_LANDLOCK_ROOTS) {
        errno = E2BIG;
        return -1;
    }
    options->read_roots[options->read_root_count++] = path;
    options->has_configured_landlock_roots = true;
    return 0;
}

static int add_write_root(struct launcher_options *options, const char *path) {
    if (options->write_root_count >= MAX_LANDLOCK_ROOTS) {
        errno = E2BIG;
        return -1;
    }
    options->write_roots[options->write_root_count++] = path;
    options->has_configured_landlock_roots = true;
    return 0;
}

static int parse_launcher_options(int argc, char *argv[], struct launcher_options *options) {
    memset(options, 0, sizeof(*options));
    options->command_index = 1;

    int index = 1;
    while (index < argc) {
        if (strcmp(argv[index], "--") == 0) {
            options->command_index = index + 1;
            return options->command_index < argc ? 0 : -1;
        }
        if (strcmp(argv[index], "--read-root") == 0) {
            if (index + 1 >= argc || add_read_root(options, argv[index + 1]) != 0) {
                return -1;
            }
            index += 2;
            continue;
        }
        if (strcmp(argv[index], "--write-root") == 0) {
            if (index + 1 >= argc || add_write_root(options, argv[index + 1]) != 0) {
                return -1;
            }
            index += 2;
            continue;
        }

        options->command_index = index;
        return 0;
    }

    errno = EINVAL;
    return -1;
}

static int detect_landlock_abi_version(void) {
    errno = 0;
    long result = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (result < 0) {
        if (errno == ENOSYS || errno == EOPNOTSUPP) {
            return 0;
        }
        return -1;
    }
    if (result > INT_MAX) {
        errno = ERANGE;
        return -1;
    }
    return (int)result;
}

static int print_diagnostics(void) {
    int landlock_abi_version = detect_landlock_abi_version();
    if (landlock_abi_version < 0) {
        fprintf(stderr, "Failed to detect Landlock ABI: %s\n", strerror(errno));
        return 1;
    }

    printf("landlock_abi=%d\n", landlock_abi_version);
    if (landlock_abi_version > 0) {
        puts("landlock_filesystem=available");
    } else {
        puts("landlock_filesystem=unsupported");
    }
    return 0;
}

static int enable_no_new_privs(void) {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return -1;
    }

    int no_new_privs = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    if (no_new_privs != 1) {
        errno = EPERM;
        return -1;
    }

    return 0;
}

static uint64_t landlock_read_execute_access(void) {
    return LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR;
}

static uint64_t landlock_write_access(int landlock_abi_version) {
    uint64_t access = LANDLOCK_ACCESS_FS_WRITE_FILE |
                      LANDLOCK_ACCESS_FS_REMOVE_DIR |
                      LANDLOCK_ACCESS_FS_REMOVE_FILE |
                      LANDLOCK_ACCESS_FS_MAKE_DIR |
                      LANDLOCK_ACCESS_FS_MAKE_REG |
                      LANDLOCK_ACCESS_FS_MAKE_SOCK |
                      LANDLOCK_ACCESS_FS_MAKE_FIFO |
                      LANDLOCK_ACCESS_FS_MAKE_SYM;
    if (landlock_abi_version >= 2) {
        access |= LANDLOCK_ACCESS_FS_REFER;
    }
    if (landlock_abi_version >= 3) {
        access |= LANDLOCK_ACCESS_FS_TRUNCATE;
    }
    return access;
}

static uint64_t landlock_handled_access(int landlock_abi_version) {
    uint64_t access = landlock_read_execute_access() |
                      landlock_write_access(landlock_abi_version) |
                      LANDLOCK_ACCESS_FS_MAKE_CHAR |
                      LANDLOCK_ACCESS_FS_MAKE_BLOCK;
    return access;
}

static bool optional_path_missing(void) {
    return errno == ENOENT || errno == ENOTDIR;
}

static int add_path_beneath_rule(int ruleset_fd, const char *path, uint64_t allowed_access, bool optional) {
    int parent_fd = open(path, O_PATH | O_CLOEXEC);
    if (parent_fd < 0) {
        if (optional && optional_path_missing()) {
            return 0;
        }
        return -1;
    }

    struct bisq_landlock_path_beneath_attr path_beneath = {
            .allowed_access = allowed_access,
            .parent_fd = parent_fd,
    };
    int result = (int)syscall(__NR_landlock_add_rule, ruleset_fd, LANDLOCK_RULE_PATH_BENEATH, &path_beneath, 0);
    int saved_errno = errno;
    close(parent_fd);
    errno = saved_errno;
    return result;
}

static int add_default_landlock_rules(int ruleset_fd,
                                      uint64_t read_execute_access,
                                      uint64_t read_write_access) {
    return add_path_beneath_rule(ruleset_fd, "/", read_execute_access, false) == 0 &&
           add_path_beneath_rule(ruleset_fd, ".", read_write_access, false) == 0 &&
           add_path_beneath_rule(ruleset_fd, "/dev", read_write_access, true) == 0 &&
           add_path_beneath_rule(ruleset_fd, "/run", read_write_access, true) == 0 &&
           add_path_beneath_rule(ruleset_fd, "/tmp", read_write_access, true) == 0 &&
           add_path_beneath_rule(ruleset_fd, "/var/tmp", read_write_access, true) == 0
           ? 0
           : -1;
}

static int add_configured_landlock_rules(int ruleset_fd,
                                         const struct launcher_options *options,
                                         uint64_t read_execute_access,
                                         uint64_t read_write_access) {
    for (size_t index = 0; index < options->read_root_count; index++) {
        if (add_path_beneath_rule(ruleset_fd, options->read_roots[index], read_execute_access, true) != 0) {
            return -1;
        }
    }
    for (size_t index = 0; index < options->write_root_count; index++) {
        if (add_path_beneath_rule(ruleset_fd, options->write_roots[index], read_write_access, true) != 0) {
            return -1;
        }
    }
    return 0;
}

static int restrict_filesystem_with_landlock(int landlock_abi_version, const struct launcher_options *options) {
    uint64_t read_execute_access = landlock_read_execute_access();
    uint64_t write_access = landlock_write_access(landlock_abi_version);
    uint64_t read_write_access = read_execute_access | write_access;
    struct bisq_landlock_ruleset_attr ruleset_attr = {
            .handled_access_fs = landlock_handled_access(landlock_abi_version),
            .handled_access_net = 0,
    };

    int ruleset_fd = (int)syscall(__NR_landlock_create_ruleset, &ruleset_attr, sizeof(ruleset_attr), 0);
    if (ruleset_fd < 0) {
        return -1;
    }

    int rules_result = options->has_configured_landlock_roots
                       ? add_configured_landlock_rules(ruleset_fd, options, read_execute_access, read_write_access)
                       : add_default_landlock_rules(ruleset_fd, read_execute_access, read_write_access);
    if (rules_result != 0) {
        int saved_errno = errno;
        close(ruleset_fd);
        errno = saved_errno;
        return -1;
    }

    int result = (int)syscall(__NR_landlock_restrict_self, ruleset_fd, 0);
    int saved_errno = errno;
    close(ruleset_fd);
    errno = saved_errno;
    return result;
}

static void try_install_filesystem_landlock(const struct launcher_options *options) {
    int landlock_abi_version = detect_landlock_abi_version();
    if (landlock_abi_version < 0) {
        fprintf(stderr, "Failed to detect Landlock ABI, continuing without filesystem sandbox: %s\n", strerror(errno));
        return;
    }
    if (landlock_abi_version == 0) {
        return;
    }

    if (restrict_filesystem_with_landlock(landlock_abi_version, options) != 0) {
        fprintf(stderr, "Failed to install Landlock filesystem sandbox, continuing without it: %s\n", strerror(errno));
    }
}

static int install_network_seccomp_filter(void) {
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SECCOMP_AUDIT_ARCH, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 4),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_DENY_ERRNO(EACCES)),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
            .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
            .filter = filter,
    };

    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
}

int main(int argc, char *argv[]) {
    if (argc == 2 && strcmp(argv[1], "--diagnose") == 0) {
        return print_diagnostics();
    }

    struct launcher_options options;
    if (argc < 2 || parse_launcher_options(argc, argv, &options) != 0) {
        print_usage();
        return 64;
    }

    if (enable_no_new_privs() != 0) {
        fprintf(stderr, "Failed to enable no_new_privs: %s\n", strerror(errno));
        return 126;
    }

    try_install_filesystem_landlock(&options);

    if (install_network_seccomp_filter() != 0) {
        fprintf(stderr, "Failed to install network seccomp filter: %s\n", strerror(errno));
        return 126;
    }

    execv(argv[options.command_index], &argv[options.command_index]);
    fprintf(stderr, "Failed to execute webcam app command: %s\n", strerror(errno));
    return errno == ENOENT ? 127 : 126;
}
