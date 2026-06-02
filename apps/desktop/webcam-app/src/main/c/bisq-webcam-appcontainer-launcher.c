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

#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0602
#endif

#include <windows.h>
#include <aclapi.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <userenv.h>
#include <wchar.h>

#define MAX_CAPABILITIES 8
#define MAX_GRANT_ROOTS 32
#define DEFAULT_PROFILE_NAME L"bisq.webcam"
#define DEFAULT_CAPABILITY_NAME L"webcam"

struct launcher_options {
    const wchar_t *profile_name;
    const wchar_t *capabilities[MAX_CAPABILITIES];
    DWORD capability_count;
    const wchar_t *read_roots[MAX_GRANT_ROOTS];
    DWORD read_root_count;
    const wchar_t *write_roots[MAX_GRANT_ROOTS];
    DWORD write_root_count;
    int command_index;
};

struct wide_buffer {
    wchar_t *data;
    size_t length;
    size_t capacity;
};

typedef BOOL(WINAPI *derive_capability_sids_from_name_func)(LPCWSTR,
                                                            PSID **,
                                                            DWORD *,
                                                            PSID **,
                                                            DWORD *);

static void print_usage(void) {
    fputws(L"Usage: bisq-webcam-appcontainer-launcher.exe "
           L"[--profile-name <name>] [--capability <name>] "
           L"[--grant-read <path>] [--grant-write <path>] -- <command> [args...]\n",
           stderr);
    fputws(L"       bisq-webcam-appcontainer-launcher.exe --diagnose\n", stderr);
}

static void print_last_error(const wchar_t *message) {
    DWORD error = GetLastError();
    wchar_t *formatted_message = NULL;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER |
                   FORMAT_MESSAGE_FROM_SYSTEM |
                   FORMAT_MESSAGE_IGNORE_INSERTS,
                   NULL,
                   error,
                   0,
                   (LPWSTR)&formatted_message,
                   0,
                   NULL);
    fwprintf(stderr, L"%ls: error=%lu", message, error);
    if (formatted_message != NULL) {
        fwprintf(stderr, L" %ls", formatted_message);
        LocalFree(formatted_message);
    } else {
        fputwc(L'\n', stderr);
    }
}

static void print_hresult(const wchar_t *message, HRESULT result) {
    fwprintf(stderr, L"%ls: hresult=0x%08lx\n", message, (unsigned long)result);
}

static bool add_capability(struct launcher_options *options, const wchar_t *capability_name) {
    if (options->capability_count >= MAX_CAPABILITIES) {
        SetLastError(ERROR_TOO_MANY_NAMES);
        return false;
    }
    options->capabilities[options->capability_count++] = capability_name;
    return true;
}

static bool add_read_root(struct launcher_options *options, const wchar_t *path) {
    if (options->read_root_count >= MAX_GRANT_ROOTS) {
        SetLastError(ERROR_TOO_MANY_NAMES);
        return false;
    }
    options->read_roots[options->read_root_count++] = path;
    return true;
}

static bool add_write_root(struct launcher_options *options, const wchar_t *path) {
    if (options->write_root_count >= MAX_GRANT_ROOTS) {
        SetLastError(ERROR_TOO_MANY_NAMES);
        return false;
    }
    options->write_roots[options->write_root_count++] = path;
    return true;
}

static bool parse_launcher_options(int argc, wchar_t *argv[], struct launcher_options *options) {
    ZeroMemory(options, sizeof(*options));
    options->profile_name = DEFAULT_PROFILE_NAME;
    options->command_index = 1;

    int index = 1;
    while (index < argc) {
        if (wcscmp(argv[index], L"--") == 0) {
            options->command_index = index + 1;
            break;
        }
        if (wcscmp(argv[index], L"--profile-name") == 0) {
            if (index + 1 >= argc) {
                SetLastError(ERROR_INVALID_PARAMETER);
                return false;
            }
            options->profile_name = argv[index + 1];
            index += 2;
            continue;
        }
        if (wcscmp(argv[index], L"--capability") == 0) {
            if (index + 1 >= argc || !add_capability(options, argv[index + 1])) {
                return false;
            }
            index += 2;
            continue;
        }
        if (wcscmp(argv[index], L"--grant-read") == 0) {
            if (index + 1 >= argc || !add_read_root(options, argv[index + 1])) {
                return false;
            }
            index += 2;
            continue;
        }
        if (wcscmp(argv[index], L"--grant-write") == 0) {
            if (index + 1 >= argc || !add_write_root(options, argv[index + 1])) {
                return false;
            }
            index += 2;
            continue;
        }

        options->command_index = index;
        break;
    }

    if (options->command_index >= argc) {
        SetLastError(ERROR_INVALID_PARAMETER);
        return false;
    }
    if (options->capability_count == 0 && !add_capability(options, DEFAULT_CAPABILITY_NAME)) {
        return false;
    }
    return true;
}

static derive_capability_sids_from_name_func load_derive_capability_sids_from_name(void) {
    HMODULE module = GetModuleHandleW(L"api-ms-win-security-base-l1-2-2.dll");
    if (module == NULL) {
        module = GetModuleHandleW(L"kernel32.dll");
    }
    if (module == NULL) {
        SetLastError(ERROR_PROC_NOT_FOUND);
        return NULL;
    }

    FARPROC procedure = GetProcAddress(module, "DeriveCapabilitySidsFromName");
    if (procedure == NULL) {
        SetLastError(ERROR_PROC_NOT_FOUND);
        return NULL;
    }
    return (derive_capability_sids_from_name_func)procedure;
}

static bool get_capability_sid_from_name(const wchar_t *capability_name, PSID *capability_sid) {
    PSID *capability_group_sids = NULL;
    DWORD capability_group_sid_count = 0;
    PSID *capability_sids = NULL;
    DWORD capability_sid_count = 0;
    *capability_sid = NULL;

    derive_capability_sids_from_name_func derive_capability_sids_from_name = load_derive_capability_sids_from_name();
    if (derive_capability_sids_from_name == NULL) {
        return false;
    }

    if (!derive_capability_sids_from_name(capability_name,
                                          &capability_group_sids,
                                          &capability_group_sid_count,
                                          &capability_sids,
                                          &capability_sid_count)) {
        return false;
    }

    for (DWORD index = 0; index < capability_group_sid_count; index++) {
        LocalFree(capability_group_sids[index]);
    }
    LocalFree(capability_group_sids);

    if (capability_sid_count < 1) {
        LocalFree(capability_sids);
        SetLastError(ERROR_NOT_FOUND);
        return false;
    }

    *capability_sid = capability_sids[0];
    for (DWORD index = 1; index < capability_sid_count; index++) {
        LocalFree(capability_sids[index]);
    }
    LocalFree(capability_sids);
    return true;
}

static void free_capability_sids(SID_AND_ATTRIBUTES *capability_attributes, DWORD capability_count) {
    for (DWORD index = 0; index < capability_count; index++) {
        LocalFree(capability_attributes[index].Sid);
    }
}

static HRESULT create_or_get_appcontainer_sid(const wchar_t *profile_name,
                                              SID_AND_ATTRIBUTES *capability_attributes,
                                              DWORD capability_count,
                                              PSID *appcontainer_sid) {
    HRESULT result = CreateAppContainerProfile(profile_name,
                                               L"Bisq webcam",
                                               L"Bisq webcam AppContainer sandbox",
                                               capability_attributes,
                                               capability_count,
                                               appcontainer_sid);
    if (result == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) {
        result = DeriveAppContainerSidFromAppContainerName(profile_name, appcontainer_sid);
    }
    return result;
}

static DWORD grant_path_access(PSID appcontainer_sid, const wchar_t *path, DWORD access_permissions) {
    DWORD attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES) {
        return GetLastError();
    }

    PACL existing_dacl = NULL;
    PACL new_dacl = NULL;
    PSECURITY_DESCRIPTOR security_descriptor = NULL;
    DWORD result = GetNamedSecurityInfoW((LPWSTR)path,
                                         SE_FILE_OBJECT,
                                         DACL_SECURITY_INFORMATION,
                                         NULL,
                                         NULL,
                                         &existing_dacl,
                                         NULL,
                                         &security_descriptor);
    if (result != ERROR_SUCCESS) {
        return result;
    }

    EXPLICIT_ACCESS_W access;
    ZeroMemory(&access, sizeof(access));
    access.grfAccessPermissions = access_permissions;
    access.grfAccessMode = GRANT_ACCESS;
    access.grfInheritance = (attributes & FILE_ATTRIBUTE_DIRECTORY)
                            ? (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE)
                            : NO_INHERITANCE;
    access.Trustee.TrusteeForm = TRUSTEE_IS_SID;
    access.Trustee.TrusteeType = TRUSTEE_IS_USER;
    access.Trustee.ptstrName = (LPWSTR)appcontainer_sid;

    result = SetEntriesInAclW(1, &access, existing_dacl, &new_dacl);
    if (result == ERROR_SUCCESS) {
        result = SetNamedSecurityInfoW((LPWSTR)path,
                                       SE_FILE_OBJECT,
                                       DACL_SECURITY_INFORMATION,
                                       NULL,
                                       NULL,
                                       new_dacl,
                                       NULL);
    }

    if (new_dacl != NULL) {
        LocalFree(new_dacl);
    }
    if (security_descriptor != NULL) {
        LocalFree(security_descriptor);
    }
    return result;
}


static bool is_dot_directory(const wchar_t *file_name) {
    return wcscmp(file_name, L".") == 0 || wcscmp(file_name, L"..") == 0;
}

static wchar_t *join_child_path(const wchar_t *parent_path, const wchar_t *file_name) {
    size_t parent_length = wcslen(parent_path);
    size_t file_name_length = wcslen(file_name);
    bool needs_separator = parent_length > 0 && parent_path[parent_length - 1] != L'\\' && parent_path[parent_length - 1] != L'/';
    size_t child_path_length = parent_length + (needs_separator ? 1 : 0) + file_name_length;
    if (child_path_length > SIZE_MAX / sizeof(wchar_t) - 1) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }

    wchar_t *child_path = malloc((child_path_length + 1) * sizeof(wchar_t));
    if (child_path == NULL) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }

    wchar_t *write_cursor = child_path;
    memcpy(write_cursor, parent_path, parent_length * sizeof(wchar_t));
    write_cursor += parent_length;
    if (needs_separator) {
        *write_cursor++ = L"\\"[0];
    }
    memcpy(write_cursor, file_name, file_name_length * sizeof(wchar_t));
    write_cursor += file_name_length;
    *write_cursor = L"\0"[0];
    return child_path;
}

static bool grant_path_tree_access(PSID appcontainer_sid, const wchar_t *path, DWORD access_permissions) {
    DWORD result = grant_path_access(appcontainer_sid, path, access_permissions);
    if (result != ERROR_SUCCESS) {
        SetLastError(result);
        return false;
    }

    DWORD attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY) || (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        return true;
    }

    wchar_t *search_path = join_child_path(path, L"*");
    if (search_path == NULL) {
        return false;
    }

    WIN32_FIND_DATAW find_data;
    HANDLE find_handle = FindFirstFileW(search_path, &find_data);
    free(search_path);
    if (find_handle == INVALID_HANDLE_VALUE) {
        DWORD find_error = GetLastError();
        return find_error == ERROR_FILE_NOT_FOUND || find_error == ERROR_PATH_NOT_FOUND;
    }

    bool success = true;
    do {
        if (is_dot_directory(find_data.cFileName)) {
            continue;
        }
        wchar_t *child_path = join_child_path(path, find_data.cFileName);
        if (child_path == NULL) {
            success = false;
            break;
        }
        if (!grant_path_tree_access(appcontainer_sid, child_path, access_permissions)) {
            free(child_path);
            success = false;
            break;
        }
        free(child_path);
    } while (FindNextFileW(find_handle, &find_data));

    DWORD last_error = GetLastError();
    FindClose(find_handle);
    if (!success) {
        return false;
    }
    if (last_error != ERROR_NO_MORE_FILES) {
        SetLastError(last_error);
        return false;
    }
    return true;
}

static bool grant_configured_roots(PSID appcontainer_sid, const struct launcher_options *options) {
    for (DWORD index = 0; index < options->read_root_count; index++) {
        if (!grant_path_tree_access(appcontainer_sid,
                                    options->read_roots[index],
                                    GENERIC_READ | GENERIC_EXECUTE)) {
            print_last_error(L"Failed to grant AppContainer read access");
            return false;
        }
    }

    for (DWORD index = 0; index < options->write_root_count; index++) {
        if (!grant_path_tree_access(appcontainer_sid,
                                    options->write_roots[index],
                                    GENERIC_READ | GENERIC_WRITE | GENERIC_EXECUTE | DELETE)) {
            print_last_error(L"Failed to grant AppContainer write access");
            return false;
        }
    }
    return true;
}

static bool wide_buffer_reserve(struct wide_buffer *buffer, size_t additional_length) {
    if (additional_length > SIZE_MAX - buffer->length - 1) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return false;
    }

    size_t required_capacity = buffer->length + additional_length + 1;
    if (required_capacity <= buffer->capacity) {
        return true;
    }

    size_t new_capacity = buffer->capacity == 0 ? 256 : buffer->capacity;
    while (new_capacity < required_capacity) {
        if (new_capacity > SIZE_MAX / 2) {
            SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return false;
        }
        new_capacity *= 2;
    }

    wchar_t *new_data = realloc(buffer->data, new_capacity * sizeof(wchar_t));
    if (new_data == NULL) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return false;
    }

    buffer->data = new_data;
    buffer->capacity = new_capacity;
    return true;
}

static bool wide_buffer_append_char(struct wide_buffer *buffer, wchar_t value) {
    if (!wide_buffer_reserve(buffer, 1)) {
        return false;
    }
    buffer->data[buffer->length++] = value;
    buffer->data[buffer->length] = L'\0';
    return true;
}

static bool wide_buffer_append_string(struct wide_buffer *buffer, const wchar_t *value) {
    size_t length = wcslen(value);
    if (!wide_buffer_reserve(buffer, length)) {
        return false;
    }
    memcpy(buffer->data + buffer->length, value, length * sizeof(wchar_t));
    buffer->length += length;
    buffer->data[buffer->length] = L'\0';
    return true;
}

static bool argument_needs_quotes(const wchar_t *argument) {
    if (argument[0] == L'\0') {
        return true;
    }
    for (const wchar_t *current = argument; *current != L'\0'; current++) {
        if (*current == L' ' || *current == L'\t' || *current == L'"') {
            return true;
        }
    }
    return false;
}

static bool append_quoted_argument(struct wide_buffer *buffer, const wchar_t *argument) {
    if (!argument_needs_quotes(argument)) {
        return wide_buffer_append_string(buffer, argument);
    }

    if (!wide_buffer_append_char(buffer, L'"')) {
        return false;
    }

    size_t backslash_count = 0;
    for (const wchar_t *current = argument; *current != L'\0'; current++) {
        if (*current == L'\\') {
            backslash_count++;
            continue;
        }

        if (*current == L'"') {
            for (size_t index = 0; index < backslash_count * 2 + 1; index++) {
                if (!wide_buffer_append_char(buffer, L'\\')) {
                    return false;
                }
            }
            backslash_count = 0;
            if (!wide_buffer_append_char(buffer, *current)) {
                return false;
            }
            continue;
        }

        for (size_t index = 0; index < backslash_count; index++) {
            if (!wide_buffer_append_char(buffer, L'\\')) {
                return false;
            }
        }
        backslash_count = 0;
        if (!wide_buffer_append_char(buffer, *current)) {
            return false;
        }
    }

    for (size_t index = 0; index < backslash_count * 2; index++) {
        if (!wide_buffer_append_char(buffer, L'\\')) {
            return false;
        }
    }
    return wide_buffer_append_char(buffer, L'"');
}

static wchar_t *build_command_line(int argc, wchar_t *argv[], int command_index) {
    struct wide_buffer buffer;
    ZeroMemory(&buffer, sizeof(buffer));

    for (int index = command_index; index < argc; index++) {
        if (index > command_index && !wide_buffer_append_char(&buffer, L' ')) {
            free(buffer.data);
            return NULL;
        }
        if (!append_quoted_argument(&buffer, argv[index])) {
            free(buffer.data);
            return NULL;
        }
    }
    return buffer.data;
}

static HANDLE create_child_process_job(void) {
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) {
        return NULL;
    }

    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limit_information;
    ZeroMemory(&limit_information, sizeof(limit_information));
    limit_information.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job,
                                 JobObjectExtendedLimitInformation,
                                 &limit_information,
                                 sizeof(limit_information))) {
        CloseHandle(job);
        return NULL;
    }
    return job;
}


static bool make_standard_handles_inheritable(void) {
    DWORD standard_handles[] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    for (size_t index = 0; index < sizeof(standard_handles) / sizeof(standard_handles[0]); index++) {
        HANDLE handle = GetStdHandle(standard_handles[index]);
        if (handle == NULL || handle == INVALID_HANDLE_VALUE) {
            continue;
        }
        if (!SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
            return false;
        }
    }
    return true;
}

static int launch_appcontainer_process(int argc,
                                       wchar_t *argv[],
                                       const struct launcher_options *options,
                                       PSID appcontainer_sid,
                                       SID_AND_ATTRIBUTES *capability_attributes) {
    wchar_t *command_line = build_command_line(argc, argv, options->command_index);
    if (command_line == NULL) {
        print_last_error(L"Failed to construct child process command line");
        return 126;
    }

    SIZE_T attribute_list_size = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &attribute_list_size);
    LPPROC_THREAD_ATTRIBUTE_LIST attribute_list = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(),
                                                                                          HEAP_ZERO_MEMORY,
                                                                                          attribute_list_size);
    if (attribute_list == NULL) {
        free(command_line);
        print_last_error(L"Failed to allocate process attribute list");
        return 126;
    }

    int exit_code = 126;
    STARTUPINFOEXW startup_info;
    PROCESS_INFORMATION process_information;
    SECURITY_CAPABILITIES security_capabilities;
    ZeroMemory(&startup_info, sizeof(startup_info));
    ZeroMemory(&process_information, sizeof(process_information));
    ZeroMemory(&security_capabilities, sizeof(security_capabilities));

    if (!InitializeProcThreadAttributeList(attribute_list, 1, 0, &attribute_list_size)) {
        print_last_error(L"Failed to initialize process attribute list");
        goto cleanup;
    }

    security_capabilities.AppContainerSid = appcontainer_sid;
    security_capabilities.Capabilities = capability_attributes;
    security_capabilities.CapabilityCount = options->capability_count;
    if (!UpdateProcThreadAttribute(attribute_list,
                                   0,
                                   PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
                                   &security_capabilities,
                                   sizeof(security_capabilities),
                                   NULL,
                                   NULL)) {
        print_last_error(L"Failed to configure AppContainer security capabilities");
        goto cleanup_attribute_list;
    }

    startup_info.StartupInfo.cb = sizeof(startup_info);
    startup_info.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup_info.StartupInfo.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup_info.StartupInfo.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup_info.StartupInfo.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    startup_info.lpAttributeList = attribute_list;

    if (!make_standard_handles_inheritable()) {
        print_last_error(L"Failed to mark standard handles inheritable");
        goto cleanup_attribute_list;
    }

    if (!CreateProcessW(NULL,
                        command_line,
                        NULL,
                        NULL,
                        TRUE,
                        EXTENDED_STARTUPINFO_PRESENT,
                        NULL,
                        NULL,
                        &startup_info.StartupInfo,
                        &process_information)) {
        print_last_error(L"Failed to launch webcam process in AppContainer");
        goto cleanup_attribute_list;
    }

    HANDLE job = create_child_process_job();
    if (job != NULL) {
        if (!AssignProcessToJobObject(job, process_information.hProcess)) {
            print_last_error(L"Failed to assign webcam process to cleanup job; continuing");
        }
    } else {
        print_last_error(L"Failed to create cleanup job; continuing");
    }

    WaitForSingleObject(process_information.hProcess, INFINITE);
    DWORD child_exit_code = 126;
    if (GetExitCodeProcess(process_information.hProcess, &child_exit_code)) {
        exit_code = (int)child_exit_code;
    } else {
        print_last_error(L"Failed to read webcam process exit code");
    }

    if (job != NULL) {
        CloseHandle(job);
    }
    CloseHandle(process_information.hThread);
    CloseHandle(process_information.hProcess);

cleanup_attribute_list:
    DeleteProcThreadAttributeList(attribute_list);
cleanup:
    HeapFree(GetProcessHeap(), 0, attribute_list);
    free(command_line);
    return exit_code;
}

static int run_diagnostics(void) {
    SID_AND_ATTRIBUTES capability_attributes[1];
    ZeroMemory(capability_attributes, sizeof(capability_attributes));
    if (!get_capability_sid_from_name(DEFAULT_CAPABILITY_NAME, &capability_attributes[0].Sid)) {
        print_last_error(L"Failed to derive diagnostic webcam capability SID");
        return 1;
    }
    capability_attributes[0].Attributes = SE_GROUP_ENABLED;

    PSID appcontainer_sid = NULL;
    HRESULT result = create_or_get_appcontainer_sid(DEFAULT_PROFILE_NAME,
                                                    capability_attributes,
                                                    1,
                                                    &appcontainer_sid);
    if (FAILED(result)) {
        print_hresult(L"Failed to create or derive diagnostic AppContainer profile", result);
        free_capability_sids(capability_attributes, 1);
        return 1;
    }

    FreeSid(appcontainer_sid);
    free_capability_sids(capability_attributes, 1);
    wprintf(L"appcontainer=available\n");
    wprintf(L"default_profile=%ls\n", DEFAULT_PROFILE_NAME);
    wprintf(L"default_capability=%ls\n", DEFAULT_CAPABILITY_NAME);
    return 0;
}

int wmain(int argc, wchar_t *argv[]) {
    if (argc == 2 && wcscmp(argv[1], L"--diagnose") == 0) {
        return run_diagnostics();
    }

    struct launcher_options options;
    if (argc < 2 || !parse_launcher_options(argc, argv, &options)) {
        print_usage();
        print_last_error(L"Invalid AppContainer launcher arguments");
        return 64;
    }

    SID_AND_ATTRIBUTES capability_attributes[MAX_CAPABILITIES];
    ZeroMemory(capability_attributes, sizeof(capability_attributes));
    for (DWORD index = 0; index < options.capability_count; index++) {
        if (!get_capability_sid_from_name(options.capabilities[index], &capability_attributes[index].Sid)) {
            print_last_error(L"Failed to derive AppContainer capability SID");
            free_capability_sids(capability_attributes, index);
            return 126;
        }
        capability_attributes[index].Attributes = SE_GROUP_ENABLED;
    }

    PSID appcontainer_sid = NULL;
    HRESULT result = create_or_get_appcontainer_sid(options.profile_name,
                                                    capability_attributes,
                                                    options.capability_count,
                                                    &appcontainer_sid);
    if (FAILED(result)) {
        print_hresult(L"Failed to create or derive AppContainer profile", result);
        free_capability_sids(capability_attributes, options.capability_count);
        return 126;
    }

    if (!grant_configured_roots(appcontainer_sid, &options)) {
        print_last_error(L"Failed to prepare AppContainer filesystem grants");
        FreeSid(appcontainer_sid);
        free_capability_sids(capability_attributes, options.capability_count);
        return 126;
    }

    int exit_code = launch_appcontainer_process(argc,
                                                argv,
                                                &options,
                                                appcontainer_sid,
                                                capability_attributes);
    FreeSid(appcontainer_sid);
    free_capability_sids(capability_attributes, options.capability_count);
    return exit_code;
}
