/*
 * Windows-only helper for the webcam MSIX/AppContainer validation harness.
 *
 * The executable records the current process token state, prefixes PATH for
 * JavaCPP/OpenCV dependent DLL resolution, runs the configured probe command,
 * redirects probe output to a file next to this executable, and writes the
 * probe exit code to a status file.
 */

#define _CRT_SECURE_NO_WARNINGS
#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif

#include <windows.h>
#include <appmodel.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#ifndef APPMODEL_ERROR_NO_PACKAGE
#define APPMODEL_ERROR_NO_PACKAGE 15700L
#endif

#define STATUS_CREATE_PROCESS_FAILED 126

static bool get_module_directory(wchar_t *directory_path, DWORD capacity) {
    DWORD length = GetModuleFileNameW(NULL, directory_path, capacity);
    if (length == 0 || length >= capacity) {
        return false;
    }

    for (DWORD index = length; index > 0; index--) {
        wchar_t value = directory_path[index - 1];
        if (value == L'\\' || value == L'/') {
            directory_path[index - 1] = L'\0';
            return true;
        }
    }
    SetLastError(ERROR_INVALID_NAME);
    return false;
}

static wchar_t *join_path(const wchar_t *directory_path, const wchar_t *file_name) {
    size_t directory_length = wcslen(directory_path);
    size_t file_name_length = wcslen(file_name);
    size_t length = directory_length + 1 + file_name_length;
    wchar_t *path = malloc((length + 1) * sizeof(wchar_t));
    if (path == NULL) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }

    memcpy(path, directory_path, directory_length * sizeof(wchar_t));
    path[directory_length] = L'\\';
    memcpy(path + directory_length + 1, file_name, file_name_length * sizeof(wchar_t));
    path[length] = L'\0';
    return path;
}

static wchar_t *duplicate_wide_string(const wchar_t *value) {
    size_t length = wcslen(value);
    wchar_t *copy = malloc((length + 1) * sizeof(wchar_t));
    if (copy == NULL) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }
    memcpy(copy, value, (length + 1) * sizeof(wchar_t));
    return copy;
}

static wchar_t *read_utf8_file(const wchar_t *path, bool optional) {
    HANDLE file = CreateFileW(path,
                              GENERIC_READ,
                              FILE_SHARE_READ,
                              NULL,
                              OPEN_EXISTING,
                              FILE_ATTRIBUTE_NORMAL,
                              NULL);
    if (file == INVALID_HANDLE_VALUE) {
        if (optional && GetLastError() == ERROR_FILE_NOT_FOUND) {
            SetLastError(ERROR_SUCCESS);
            return NULL;
        }
        return NULL;
    }

    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart > INT_MAX) {
        CloseHandle(file);
        SetLastError(ERROR_INVALID_DATA);
        return NULL;
    }

    DWORD byte_count = (DWORD)size.QuadPart;
    char *bytes = malloc((size_t)byte_count + 1);
    if (bytes == NULL) {
        CloseHandle(file);
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }

    DWORD bytes_read = 0;
    BOOL read_result = ReadFile(file, bytes, byte_count, &bytes_read, NULL);
    CloseHandle(file);
    if (!read_result || bytes_read != byte_count) {
        free(bytes);
        SetLastError(ERROR_READ_FAULT);
        return NULL;
    }
    bytes[byte_count] = '\0';

    DWORD offset = 0;
    if (byte_count >= 3 &&
            (unsigned char)bytes[0] == 0xEF &&
            (unsigned char)bytes[1] == 0xBB &&
            (unsigned char)bytes[2] == 0xBF) {
        offset = 3;
    }

    int wide_length = MultiByteToWideChar(CP_UTF8,
                                          MB_ERR_INVALID_CHARS,
                                          bytes + offset,
                                          (int)(byte_count - offset),
                                          NULL,
                                          0);
    if (wide_length <= 0) {
        free(bytes);
        return NULL;
    }

    wchar_t *wide = malloc(((size_t)wide_length + 1) * sizeof(wchar_t));
    if (wide == NULL) {
        free(bytes);
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return NULL;
    }

    if (MultiByteToWideChar(CP_UTF8,
                            MB_ERR_INVALID_CHARS,
                            bytes + offset,
                            (int)(byte_count - offset),
                            wide,
                            wide_length) != wide_length) {
        free(bytes);
        free(wide);
        return NULL;
    }
    wide[wide_length] = L'\0';
    free(bytes);
    return wide;
}

static void trim_line_end(wchar_t *value) {
    size_t length = wcslen(value);
    while (length > 0 && (value[length - 1] == L'\r' || value[length - 1] == L'\n')) {
        value[--length] = L'\0';
    }
}

static wchar_t *read_io_directory_path(const wchar_t *module_directory_path) {
    wchar_t *io_dir_file_path = join_path(module_directory_path, L"probe-io-dir.txt");
    if (io_dir_file_path == NULL) {
        return NULL;
    }

    wchar_t *io_directory_path = read_utf8_file(io_dir_file_path, true);
    free(io_dir_file_path);
    if (io_directory_path == NULL) {
        return NULL;
    }
    trim_line_end(io_directory_path);
    if (io_directory_path[0] == L'\0') {
        free(io_directory_path);
        SetLastError(ERROR_INVALID_DATA);
        return NULL;
    }
    return io_directory_path;
}

static wchar_t *read_io_directory_argument(int argc, wchar_t *argv[]) {
    for (int index = 1; index < argc; index++) {
        if (wcscmp(argv[index], L"--io-dir") == 0 && index + 1 < argc) {
            return duplicate_wide_string(argv[index + 1]);
        }
    }
    SetLastError(ERROR_FILE_NOT_FOUND);
    return NULL;
}

static bool prepend_path_prefix(const wchar_t *path_prefix_file_path) {
    wchar_t *prefix = read_utf8_file(path_prefix_file_path, true);
    if (prefix == NULL) {
        return GetLastError() == ERROR_SUCCESS;
    }
    trim_line_end(prefix);
    if (prefix[0] == L'\0') {
        free(prefix);
        return true;
    }

    DWORD existing_length = GetEnvironmentVariableW(L"PATH", NULL, 0);
    wchar_t *existing_path = NULL;
    if (existing_length > 0) {
        existing_path = malloc(existing_length * sizeof(wchar_t));
        if (existing_path == NULL) {
            free(prefix);
            SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return false;
        }
        if (GetEnvironmentVariableW(L"PATH", existing_path, existing_length) >= existing_length) {
            free(prefix);
            free(existing_path);
            SetLastError(ERROR_ENVVAR_NOT_FOUND);
            return false;
        }
    }

    size_t prefix_length = wcslen(prefix);
    size_t existing_path_length = existing_path == NULL ? 0 : wcslen(existing_path);
    size_t new_path_length = prefix_length + (existing_path_length == 0 ? 0 : 1 + existing_path_length);
    wchar_t *new_path = malloc((new_path_length + 1) * sizeof(wchar_t));
    if (new_path == NULL) {
        free(prefix);
        free(existing_path);
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return false;
    }

    memcpy(new_path, prefix, prefix_length * sizeof(wchar_t));
    if (existing_path_length > 0) {
        new_path[prefix_length] = L';';
        memcpy(new_path + prefix_length + 1, existing_path, existing_path_length * sizeof(wchar_t));
    }
    new_path[new_path_length] = L'\0';

    bool success = SetEnvironmentVariableW(L"PATH", new_path) != 0;
    free(prefix);
    free(existing_path);
    free(new_path);
    return success;
}

static void write_last_error(FILE *file, const wchar_t *key, DWORD error) {
    wchar_t *message = NULL;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER |
                   FORMAT_MESSAGE_FROM_SYSTEM |
                   FORMAT_MESSAGE_IGNORE_INSERTS,
                   NULL,
                   error,
                   0,
                   (LPWSTR)&message,
                   0,
                   NULL);
    if (message == NULL) {
        fwprintf(file, L"%ls=%lu\n", key, (unsigned long)error);
    } else {
        fwprintf(file, L"%ls=%lu %ls", key, (unsigned long)error, message);
        LocalFree(message);
    }
}

static void write_status(const wchar_t *status_file_path, DWORD exit_code) {
    FILE *file = _wfopen(status_file_path, L"w, ccs=UTF-8");
    if (file != NULL) {
        fwprintf(file, L"%lu\n", (unsigned long)exit_code);
        fclose(file);
    }
}

static void write_token_info(const wchar_t *token_file_path) {
    FILE *file = _wfopen(token_file_path, L"w, ccs=UTF-8");
    if (file == NULL) {
        return;
    }

    HANDLE token = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) {
        write_last_error(file, L"open_process_token_error", GetLastError());
    } else {
        DWORD token_is_appcontainer = 0;
        DWORD token_info_length = 0;
        if (GetTokenInformation(token,
                                TokenIsAppContainer,
                                &token_is_appcontainer,
                                sizeof(token_is_appcontainer),
                                &token_info_length)) {
            fwprintf(file, L"token_is_appcontainer=%ls\n", token_is_appcontainer ? L"true" : L"false");
        } else {
            write_last_error(file, L"token_is_appcontainer_error", GetLastError());
        }

        DWORD integrity_length = 0;
        GetTokenInformation(token, TokenIntegrityLevel, NULL, 0, &integrity_length);
        PTOKEN_MANDATORY_LABEL integrity_label = malloc(integrity_length);
        if (integrity_label != NULL &&
                GetTokenInformation(token,
                                    TokenIntegrityLevel,
                                    integrity_label,
                                    integrity_length,
                                    &integrity_length)) {
            PSID integrity_sid = integrity_label->Label.Sid;
            DWORD sub_authority_count = *GetSidSubAuthorityCount(integrity_sid);
            DWORD rid = *GetSidSubAuthority(integrity_sid, sub_authority_count - 1);
            fwprintf(file, L"integrity_rid=0x%lx\n", (unsigned long)rid);
        } else {
            write_last_error(file, L"integrity_error", GetLastError());
        }
        free(integrity_label);
        CloseHandle(token);
    }

    UINT32 package_family_name_length = 0;
    LONG package_result = GetCurrentPackageFamilyName(&package_family_name_length, NULL);
    if (package_result == APPMODEL_ERROR_NO_PACKAGE) {
        fwprintf(file, L"package_family_name=<none>\n");
        fwprintf(file, L"package_family_name_error=APPMODEL_ERROR_NO_PACKAGE\n");
    } else if (package_result == ERROR_INSUFFICIENT_BUFFER) {
        wchar_t *package_family_name = malloc(package_family_name_length * sizeof(wchar_t));
        if (package_family_name == NULL) {
            fwprintf(file, L"package_family_name_error=ERROR_NOT_ENOUGH_MEMORY\n");
        } else {
            package_result = GetCurrentPackageFamilyName(&package_family_name_length, package_family_name);
            if (package_result == ERROR_SUCCESS) {
                fwprintf(file, L"package_family_name=%ls\n", package_family_name);
            } else {
                fwprintf(file, L"package_family_name_error=%ld\n", package_result);
            }
            free(package_family_name);
        }
    } else {
        fwprintf(file, L"package_family_name_error=%ld\n", package_result);
    }

    fclose(file);
}

static void write_process_failure(const wchar_t *output_file_path, const wchar_t *message, DWORD error) {
    FILE *file = _wfopen(output_file_path, L"a, ccs=UTF-8");
    if (file == NULL) {
        return;
    }
    fwprintf(file, L"%ls\n", message);
    write_last_error(file, L"win32_error", error);
    fclose(file);
}

static DWORD run_child_process(const wchar_t *command_line,
                               const wchar_t *working_directory_path,
                               const wchar_t *output_file_path) {
    SECURITY_ATTRIBUTES security_attributes;
    ZeroMemory(&security_attributes, sizeof(security_attributes));
    security_attributes.nLength = sizeof(security_attributes);
    security_attributes.bInheritHandle = TRUE;

    HANDLE output_handle = CreateFileW(output_file_path,
                                       GENERIC_WRITE,
                                       FILE_SHARE_READ,
                                       &security_attributes,
                                       CREATE_ALWAYS,
                                       FILE_ATTRIBUTE_NORMAL,
                                       NULL);
    if (output_handle == INVALID_HANDLE_VALUE) {
        return STATUS_CREATE_PROCESS_FAILED;
    }
    SetHandleInformation(output_handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);

    wchar_t *mutable_command_line = _wcsdup(command_line);
    if (mutable_command_line == NULL) {
        CloseHandle(output_handle);
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return STATUS_CREATE_PROCESS_FAILED;
    }

    STARTUPINFOW startup_info;
    PROCESS_INFORMATION process_information;
    ZeroMemory(&startup_info, sizeof(startup_info));
    ZeroMemory(&process_information, sizeof(process_information));
    startup_info.cb = sizeof(startup_info);
    startup_info.dwFlags = STARTF_USESTDHANDLES;
    startup_info.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup_info.hStdOutput = output_handle;
    startup_info.hStdError = output_handle;

    BOOL created = CreateProcessW(NULL,
                                  mutable_command_line,
                                  NULL,
                                  NULL,
                                  TRUE,
                                  CREATE_NO_WINDOW,
                                  NULL,
                                  working_directory_path,
                                  &startup_info,
                                  &process_information);
    DWORD create_error = GetLastError();
    free(mutable_command_line);
    CloseHandle(output_handle);

    if (!created) {
        write_process_failure(output_file_path, L"Failed to launch probe command", create_error);
        SetLastError(create_error);
        return STATUS_CREATE_PROCESS_FAILED;
    }

    WaitForSingleObject(process_information.hProcess, INFINITE);
    DWORD exit_code = STATUS_CREATE_PROCESS_FAILED;
    if (!GetExitCodeProcess(process_information.hProcess, &exit_code)) {
        exit_code = STATUS_CREATE_PROCESS_FAILED;
    }
    CloseHandle(process_information.hThread);
    CloseHandle(process_information.hProcess);
    return exit_code;
}

int wmain(int argc, wchar_t *argv[]) {
    wchar_t module_directory_path[MAX_PATH];
    if (!get_module_directory(module_directory_path, MAX_PATH)) {
        return STATUS_CREATE_PROCESS_FAILED;
    }

    wchar_t *io_directory_path = read_io_directory_argument(argc, argv);
    if (io_directory_path == NULL) {
        io_directory_path = read_io_directory_path(module_directory_path);
    }
    const wchar_t *probe_directory_path = io_directory_path == NULL ? module_directory_path : io_directory_path;

    wchar_t *command_file_path = join_path(probe_directory_path, L"probe-command.txt");
    wchar_t *output_file_path = join_path(probe_directory_path, L"probe-output.txt");
    wchar_t *status_file_path = join_path(probe_directory_path, L"probe-status.txt");
    wchar_t *token_file_path = join_path(probe_directory_path, L"probe-token.txt");
    wchar_t *path_prefix_file_path = join_path(probe_directory_path, L"probe-path-prefix.txt");
    if (command_file_path == NULL ||
            output_file_path == NULL ||
            status_file_path == NULL ||
            token_file_path == NULL ||
            path_prefix_file_path == NULL) {
        return STATUS_CREATE_PROCESS_FAILED;
    }

    write_token_info(token_file_path);
    SetEnvironmentVariableW(L"OPENCV_VIDEOIO_DEBUG", L"1");
    if (!prepend_path_prefix(path_prefix_file_path)) {
        DWORD error = GetLastError();
        write_process_failure(output_file_path, L"Failed to prefix PATH", error);
        write_status(status_file_path, STATUS_CREATE_PROCESS_FAILED);
        return STATUS_CREATE_PROCESS_FAILED;
    }

    wchar_t *command_line = read_utf8_file(command_file_path, false);
    if (command_line == NULL) {
        DWORD error = GetLastError();
        write_process_failure(output_file_path, L"Failed to read probe command", error);
        write_status(status_file_path, STATUS_CREATE_PROCESS_FAILED);
        return STATUS_CREATE_PROCESS_FAILED;
    }
    trim_line_end(command_line);

    DWORD exit_code = run_child_process(command_line, module_directory_path, output_file_path);
    write_status(status_file_path, exit_code);

    free(command_line);
    free(io_directory_path);
    free(command_file_path);
    free(output_file_path);
    free(status_file_path);
    free(token_file_path);
    free(path_prefix_file_path);
    return (int)exit_code;
}
