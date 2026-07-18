#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <setjmp.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <mutex>

namespace {
constexpr const char *kTag = "AppleDecryptorNative";
constexpr size_t kMaxNativeLogEntries = 64;
constexpr size_t kMaxNativeLogLength = 256;

// Global state lock — recursive to allow nested locking from
// resolveSymbols -> openTargetLibrary call chain.
std::recursive_mutex gGlobalLock;
#define LOCK_GLOBAL() std::lock_guard<std::recursive_mutex> _lock(gGlobalLock)

constexpr const char *kTargetLibrary = "libandroidappmusic.so";
constexpr const char *kSessionCtrlInstanceSymbol = "_ZN21SVFootHillSessionCtrl8instanceEv";
constexpr const char *kGetPersistentKeySymbol = "_ZN21SVFootHillSessionCtrl16getPersistentKeyERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES8_S8_S8_S8_S8_S8_S8_";
constexpr const char *kDecryptContextSymbol = "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEERKN11SVDecryptor15SVDecryptorTypeERKb";
constexpr const char *kResetAllContextsSymbol = "_ZN21SVFootHillSessionCtrl16resetAllContextsEv";
constexpr const char *kDecryptSampleSymbol = "NfcRKVnxuKZy04KWbdFu71Ou";
constexpr const char *kKeyFormat = "com.apple.streamingkeydelivery";
constexpr const char *kKeyFormatVersion = "1";
constexpr const char *kServerUri = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps";
constexpr const char *kProtocolType = "simplified";
constexpr const char *kFairPlayCertificate = "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";
constexpr size_t kFairPlayCertificateExpectedLength = 3464;
constexpr size_t kFairPlayCertificatePatchOffset = 3218;
constexpr const char *kFairPlayCertificatePatch = "Pa";

struct StdString {
    uint8_t storage[24] = {};
    char *buffer = nullptr;
};

void initStdString(StdString *str, const char *content) {
    if (str == nullptr || content == nullptr) {
        return;
    }

    size_t len = 0;
    while (content[len] != '\0') {
        len++;
    }

    if (len >= 0x17) {
        size_t capacity = 1;
        while (capacity < len + 1) {
            capacity <<= 1;
        }
        str->buffer = static_cast<char *>(malloc(capacity));
        if (str->buffer == nullptr) {
            return;
        }
        for (size_t i = 0; i < len; i++) {
            str->buffer[i] = content[i];
        }
        str->buffer[len] = '\0';
        *reinterpret_cast<uint64_t *>(str->storage) = static_cast<uint64_t>(capacity | 0x1);
        *reinterpret_cast<uint64_t *>(str->storage + sizeof(uint64_t)) = static_cast<uint64_t>(len);
        *reinterpret_cast<char **>(str->storage + sizeof(uint64_t) * 2) = str->buffer;
        return;
    }

    str->storage[0] = static_cast<uint8_t>(len * 2);
    for (size_t i = 0; i < len; i++) {
        str->storage[i + 1] = static_cast<uint8_t>(content[i]);
    }
    str->storage[len + 1] = 0;
}

void destroyStdString(StdString *str) {
    if (str != nullptr && str->buffer != nullptr) {
        free(str->buffer);
        str->buffer = nullptr;
    }
}

// FairPlay certificate patching strategy:
// The base certificate constant kFairPlayCertificate is stored at a length
// of kFairPlayCertificateExpectedLength - strlen(kFairPlayCertificatePatch)
// (currently 3462 bytes). At offset kFairPlayCertificatePatchOffset (3218),
// two bytes "Pa" are inserted to produce the full 3464-byte certificate.
// This avoids storing the complete certificate literal in two places.
// The Kotlin layer does not hold a copy — the certificate exists only here.
void initFairPlayCertificate(StdString *str) {
    const size_t len = strlen(kFairPlayCertificate);
    if (len != kFairPlayCertificateExpectedLength - strlen(kFairPlayCertificatePatch)) {
        initStdString(str, kFairPlayCertificate);
        return;
    }

    char *patched = static_cast<char *>(malloc(kFairPlayCertificateExpectedLength + 1));
    if (patched == nullptr) {
        initStdString(str, kFairPlayCertificate);
        return;
    }

    memcpy(patched, kFairPlayCertificate, kFairPlayCertificatePatchOffset);
    memcpy(patched + kFairPlayCertificatePatchOffset, kFairPlayCertificatePatch, strlen(kFairPlayCertificatePatch));
    memcpy(
            patched + kFairPlayCertificatePatchOffset + strlen(kFairPlayCertificatePatch),
            kFairPlayCertificate + kFairPlayCertificatePatchOffset,
            len - kFairPlayCertificatePatchOffset + 1);
    initStdString(str, patched);
    free(patched);
}

struct ResolverState {
    void *library = nullptr;
    bool hasDlHandle = false;
    uintptr_t libraryBase = 0;
    char libraryPath[512] = {};
    void *sessionCtrlInstance = nullptr;
    void *getPersistentKey = nullptr;
    void *decryptContext = nullptr;
    void *resetAllContexts = nullptr;
    void *decryptSample = nullptr;
};

using SessionCtrlInstanceFn = void *(*)();
using ResetAllContextsFn = void (*)(void *);
using DecryptSampleFn = unsigned long (*)(void *, uint32_t, void *, void *, size_t);

ResolverState gResolver;
void *gSessionCtrl = nullptr;
void *gKdContext = nullptr;
void *gPreshareKdContext = nullptr;
char gNativeLogs[kMaxNativeLogEntries][kMaxNativeLogLength] = {};
size_t gNativeLogCount = 0;
sigjmp_buf gAbortJump;
struct sigaction gPreviousAbortAction = {};
volatile sig_atomic_t gAbortGuardActive = 0;
unsigned long gLastDecryptReturnValue = 0;

bool shouldNativeLog(int priority) {
#if defined(NDEBUG)
    return priority >= ANDROID_LOG_WARN;
#else
    (void) priority;
    return true;
#endif
}

const char *nativeLogPrefix(int priority) {
    switch (priority) {
        case ANDROID_LOG_ERROR:
            return "ERROR";
        case ANDROID_LOG_WARN:
            return "WARN";
        case ANDROID_LOG_DEBUG:
            return "DEBUG";
        default:
            return "INFO";
    }
}

void enqueueNativeLog(int priority, const char *message) {
    if (message == nullptr || message[0] == '\0') {
        return;
    }

    if (gNativeLogCount == kMaxNativeLogEntries) {
        for (size_t i = 1; i < kMaxNativeLogEntries; i++) {
            memcpy(gNativeLogs[i - 1], gNativeLogs[i], kMaxNativeLogLength);
        }
        gNativeLogCount--;
    }

    snprintf(gNativeLogs[gNativeLogCount], kMaxNativeLogLength, "%s: %s", nativeLogPrefix(priority), message);
    gNativeLogCount++;
}

void nativeLog(int priority, const char *format, ...) {
    char message[kMaxNativeLogLength] = {};
    const bool loggable = shouldNativeLog(priority);
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    vsnprintf(message, kMaxNativeLogLength, format, args);
    if (loggable) {
        __android_log_vprint(priority, kTag, format, copy);
    }
    va_end(copy);
    va_end(args);
    if (loggable) {
        enqueueNativeLog(priority, message);
    }
}

void abortGuardHandler(int signal, siginfo_t *, void *) {
    if (gAbortGuardActive) {
        siglongjmp(gAbortJump, 1);
    }

    sigaction(signal, &gPreviousAbortAction, nullptr);
    raise(signal);
}

void installAbortGuard() {
    struct sigaction action = {};
    action.sa_sigaction = abortGuardHandler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    sigaction(SIGABRT, &action, &gPreviousAbortAction);
    gAbortGuardActive = 1;
}

void restoreAbortGuard() {
    gAbortGuardActive = 0;
    sigaction(SIGABRT, &gPreviousAbortAction, nullptr);
}

bool findMappedLibrary(const char *libraryName, char *path, size_t pathSize, uintptr_t *base) {
    if (libraryName == nullptr || path == nullptr || pathSize == 0 || base == nullptr) {
        return false;
    }

    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        return false;
    }

    char line[1024];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strstr(line, libraryName) == nullptr) {
            continue;
        }

        unsigned long long start = 0;
        unsigned long long end = 0;
        unsigned long long offset = 0;
        char perms[5] = {};
        char mappedPath[512] = {};
        if (sscanf(line, "%llx-%llx %4s %llx %*s %*s %511s", &start, &end, perms, &offset, mappedPath) != 5) {
            continue;
        }

        if (strstr(mappedPath, libraryName) == nullptr) {
            continue;
        }

        snprintf(path, pathSize, "%s", mappedPath);
        *base = static_cast<uintptr_t>(start - offset);
        fclose(maps);
        return true;
    }

    fclose(maps);
    return false;
}

void *openTargetLibrary() {
    LOCK_GLOBAL();
    char sonameError[128] = {};
    dlerror();
    void *handle = dlopen(kTargetLibrary, RTLD_NOW | RTLD_NOLOAD);
    if (handle != nullptr) {
        nativeLog(ANDROID_LOG_INFO, "resolved %s by soname", kTargetLibrary);
        gResolver.hasDlHandle = true;
        return handle;
    }
    snprintf(sonameError, sizeof(sonameError), "%s", dlerror() ?: "<none>");

    uintptr_t mappedBase = 0;
    if (!findMappedLibrary(kTargetLibrary, gResolver.libraryPath, sizeof(gResolver.libraryPath), &mappedBase)) {
        nativeLog(ANDROID_LOG_WARN, "failed to find mapped path for %s: %s", kTargetLibrary, sonameError);
        return nullptr;
    }
    gResolver.libraryBase = mappedBase;

    char pathNoloadError[128] = {};
    dlerror();
    handle = dlopen(gResolver.libraryPath, RTLD_NOW | RTLD_NOLOAD);
    if (handle != nullptr) {
        nativeLog(ANDROID_LOG_INFO, "resolved %s by mapped path NOLOAD", kTargetLibrary);
        gResolver.hasDlHandle = true;
        return handle;
    }
    snprintf(pathNoloadError, sizeof(pathNoloadError), "%s", dlerror() ?: "<none>");

    dlerror();
    handle = dlopen(gResolver.libraryPath, RTLD_NOW);
    if (handle != nullptr) {
        nativeLog(ANDROID_LOG_INFO, "resolved %s by mapped path", kTargetLibrary);
        gResolver.hasDlHandle = true;
        return handle;
    }

    nativeLog(
            ANDROID_LOG_WARN,
            "dlopen failed for %s; using ELF fallback base=%p soname=%s noload=%s open=%s",
            kTargetLibrary,
            reinterpret_cast<void *>(gResolver.libraryBase),
            sonameError,
            pathNoloadError,
            dlerror() ?: "<none>");
    return reinterpret_cast<void *>(gResolver.libraryBase);
}

void *resolveElfSymbol(const char *path, uintptr_t base, const char *symbolName) {
    if (path == nullptr || path[0] == '\0' || base == 0 || symbolName == nullptr) {
        return nullptr;
    }

    FILE *file = fopen(path, "rb");
    if (file == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "ELF open failed for %s", path);
        return nullptr;
    }

    Elf64_Ehdr header = {};
    if (fread(&header, 1, sizeof(header), file) != sizeof(header) ||
        memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
        header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_shoff == 0 ||
        header.e_shnum == 0 ||
        header.e_shentsize != sizeof(Elf64_Shdr)) {
        fclose(file);
        nativeLog(ANDROID_LOG_WARN, "ELF header unsupported for %s", path);
        return nullptr;
    }

    const size_t sectionBytes = static_cast<size_t>(header.e_shnum) * sizeof(Elf64_Shdr);
    auto *sections = static_cast<Elf64_Shdr *>(malloc(sectionBytes));
    if (sections == nullptr) {
        fclose(file);
        return nullptr;
    }

    if (fseek(file, static_cast<long>(header.e_shoff), SEEK_SET) != 0 ||
        fread(sections, 1, sectionBytes, file) != sectionBytes) {
        free(sections);
        fclose(file);
        nativeLog(ANDROID_LOG_WARN, "ELF section read failed for %s", path);
        return nullptr;
    }

    void *resolved = nullptr;
    for (size_t i = 0; i < header.e_shnum && resolved == nullptr; i++) {
        const Elf64_Shdr &symSection = sections[i];
        if (symSection.sh_type != SHT_DYNSYM || symSection.sh_entsize != sizeof(Elf64_Sym) || symSection.sh_link >= header.e_shnum) {
            continue;
        }

        const Elf64_Shdr &strSection = sections[symSection.sh_link];
        auto *strings = static_cast<char *>(malloc(static_cast<size_t>(strSection.sh_size)));
        if (strings == nullptr) {
            continue;
        }

        if (fseek(file, static_cast<long>(strSection.sh_offset), SEEK_SET) != 0 ||
            fread(strings, 1, static_cast<size_t>(strSection.sh_size), file) != static_cast<size_t>(strSection.sh_size)) {
            free(strings);
            continue;
        }

        const size_t symbolCount = static_cast<size_t>(symSection.sh_size / symSection.sh_entsize);
        if (fseek(file, static_cast<long>(symSection.sh_offset), SEEK_SET) != 0) {
            free(strings);
            continue;
        }

        for (size_t symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
            Elf64_Sym symbol = {};
            if (fread(&symbol, 1, sizeof(symbol), file) != sizeof(symbol)) {
                break;
            }
            if (symbol.st_name >= strSection.sh_size) {
                continue;
            }
            if (strcmp(strings + symbol.st_name, symbolName) == 0) {
                resolved = reinterpret_cast<void *>(base + symbol.st_value);
                break;
            }
        }
        free(strings);
    }

    free(sections);
    fclose(file);
    return resolved;
}

void *resolveTargetSymbol(const char *symbolName) {
    if (gResolver.hasDlHandle && gResolver.library != nullptr) {
        void *symbol = dlsym(gResolver.library, symbolName);
        if (symbol != nullptr) {
            return symbol;
        }
    }

    return resolveElfSymbol(gResolver.libraryPath, gResolver.libraryBase, symbolName);
}

void resolveSymbols() {
    LOCK_GLOBAL();
    if (gResolver.library != nullptr &&
        gResolver.sessionCtrlInstance != nullptr &&
        gResolver.getPersistentKey != nullptr &&
        gResolver.decryptContext != nullptr &&
        gResolver.resetAllContexts != nullptr &&
        gResolver.decryptSample != nullptr) {
        return;
    }

    if (gResolver.library == nullptr) {
        gResolver.library = openTargetLibrary();
    }
    if (gResolver.library == nullptr) {
        return;
    }

    gResolver.sessionCtrlInstance = resolveTargetSymbol(kSessionCtrlInstanceSymbol);
    gResolver.getPersistentKey = resolveTargetSymbol(kGetPersistentKeySymbol);
    gResolver.decryptContext = resolveTargetSymbol(kDecryptContextSymbol);
    gResolver.resetAllContexts = resolveTargetSymbol(kResetAllContextsSymbol);
    gResolver.decryptSample = resolveTargetSymbol(kDecryptSampleSymbol);

    nativeLog(
            ANDROID_LOG_INFO,
            "resolver symbols: instance=%p getPersistentKey=%p decryptContext=%p resetAllContexts=%p decryptSample=%p",
            gResolver.sessionCtrlInstance,
            gResolver.getPersistentKey,
            gResolver.decryptContext,
            gResolver.resetAllContexts,
            gResolver.decryptSample);
}

const char *resolverStatus() {
    LOCK_GLOBAL();
    resolveSymbols();

    if (gResolver.library == nullptr) {
        return "library_missing";
    }

    if (gResolver.sessionCtrlInstance == nullptr ||
        gResolver.getPersistentKey == nullptr ||
        gResolver.decryptContext == nullptr ||
        gResolver.resetAllContexts == nullptr ||
        gResolver.decryptSample == nullptr) {
        return "symbol_missing";
    }

    return "ready";
}

const char *jstringToUtfChars(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return nullptr;
    }

    return env->GetStringUTFChars(value, nullptr);
}

void *readPointerAt(void *base, size_t offset) {
    if (base == nullptr) {
        return nullptr;
    }

    return *reinterpret_cast<void **>(reinterpret_cast<uint8_t *>(base) + offset);
}

bool isEmpty(const char *value) {
    return value == nullptr || value[0] == '\0';
}

#if defined(__aarch64__)
void callGetPersistentKey(
        void *target,
        void *sessionCtrl,
        void *adamId,
        void *assetId,
        void *uri,
        void *keyFormat,
        void *keyFormatVersion,
        void *serverUri,
        void *protocolType,
        void *persistentKey,
        void *fairPlayCertificate) {
    register void *x0 asm("x0") = sessionCtrl;
    register void *x1 asm("x1") = adamId;
    register void *x2 asm("x2") = assetId;
    register void *x3 asm("x3") = uri;
    register void *x4 asm("x4") = keyFormat;
    register void *x5 asm("x5") = keyFormatVersion;
    register void *x6 asm("x6") = serverUri;
    register void *x7 asm("x7") = protocolType;
    register void *x8 asm("x8") = persistentKey;
    register void *x9 asm("x9") = fairPlayCertificate;
    register void *x10 asm("x10") = target;

    asm volatile(
            "str %9, [sp, #-16]!\n"
            "blr %10\n"
            "add sp, sp, #16\n"
            : "+r"(x0), "+r"(x1), "+r"(x2), "+r"(x3), "+r"(x4), "+r"(x5), "+r"(x6), "+r"(x7), "+r"(x8), "+r"(x9), "+r"(x10)
            :
            : "memory", "cc", "x11", "x12", "x13", "x14", "x15", "x16", "x17", "x30");
}

void callDecryptContext(void *target, void *sessionCtrl, void *persistentKey, void *decryptedKey) {
    register void *x0 asm("x0") = sessionCtrl;
    register void *x1 asm("x1") = persistentKey;
    register void *x8 asm("x8") = decryptedKey;
    register void *x9 asm("x9") = target;

    asm volatile(
            "blr %3\n"
            : "+r"(x0), "+r"(x1), "+r"(x8), "+r"(x9)
            :
            : "memory", "cc", "x10", "x11", "x12", "x13", "x14", "x15", "x16", "x17", "x30");
}
#else
void callGetPersistentKey(
        void *, void *, void *, void *, void *, void *, void *, void *, void *, void *, void *) {}
void callDecryptContext(void *, void *, void *, void *) {}
#endif

// Debug assertion: verify SP is 16-byte aligned for AAPCS64
#if defined(__aarch64__) && !defined(NDEBUG)
#define ASSERT_SP_ALIGNED() do { \
    volatile void *sp_value = __builtin_frame_address(0); \
    if (reinterpret_cast<uintptr_t>(sp_value) & 0xf) { \
        nativeLog(ANDROID_LOG_ERROR, "SP misaligned before ARM64 call: %p", sp_value); \
    } \
} while (0)
#else
#define ASSERT_SP_ALIGNED() ((void)0)
#endif

bool callGetPersistentKeySafely(
        void *target,
        void *sessionCtrl,
        void *adamId,
        void *assetId,
        void *uri,
        void *keyFormat,
        void *keyFormatVersion,
        void *serverUri,
        void *protocolType,
        void *persistentKey,
        void *fairPlayCertificate) {
    ASSERT_SP_ALIGNED();
    if (sigsetjmp(gAbortJump, 1) != 0) {
        restoreAbortGuard();
        nativeLog(ANDROID_LOG_WARN, "getPersistentKey aborted");
        return false;
    }

    installAbortGuard();
    callGetPersistentKey(
            target,
            sessionCtrl,
            adamId,
            assetId,
            uri,
            keyFormat,
            keyFormatVersion,
            serverUri,
            protocolType,
            persistentKey,
            fairPlayCertificate);
    restoreAbortGuard();
    return true;
}

bool callDecryptContextSafely(void *target, void *sessionCtrl, void *persistentKey, void *decryptedKey) {
    ASSERT_SP_ALIGNED();
    if (sigsetjmp(gAbortJump, 1) != 0) {
        restoreAbortGuard();
        nativeLog(ANDROID_LOG_WARN, "decryptContext aborted");
        return false;
    }

    installAbortGuard();
    callDecryptContext(target, sessionCtrl, persistentKey, decryptedKey);
    restoreAbortGuard();
    return true;
}

bool callDecryptSampleSafely(
        void *target,
        void *decryptContext,
        uint32_t decryptorType,
        void *input,
        void *output,
        size_t size,
        unsigned long *returnValue) {
    ASSERT_SP_ALIGNED();
    if (sigsetjmp(gAbortJump, 1) != 0) {
        restoreAbortGuard();
        nativeLog(ANDROID_LOG_WARN, "decryptSample aborted");
        return false;
    }

    auto decryptSample = reinterpret_cast<DecryptSampleFn>(target);
    installAbortGuard();
    const unsigned long result = decryptSample(decryptContext, decryptorType, input, output, size);
    restoreAbortGuard();
    if (returnValue != nullptr) {
        *returnValue = result;
    }
    return true;
}

bool prepareSession(const char *adamId, const char *uri) {
    LOCK_GLOBAL();
    if (isEmpty(adamId) || isEmpty(uri) || resolverStatus()[0] != 'r') {
        nativeLog(
                ANDROID_LOG_WARN,
                "prepareSession rejected adamId=%s uriLen=%zu status=%s",
                adamId == nullptr ? "<null>" : adamId,
                uri == nullptr ? 0 : strlen(uri),
                resolverStatus());
        return false;
    }

    auto sessionCtrlInstance = reinterpret_cast<SessionCtrlInstanceFn>(gResolver.sessionCtrlInstance);
    auto resetAllContexts = reinterpret_cast<ResetAllContextsFn>(gResolver.resetAllContexts);

    gSessionCtrl = sessionCtrlInstance();
    if (gSessionCtrl == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "session controller is null");
        return false;
    }

    const bool isPreshare = adamId[0] == '0' && adamId[1] == '\0';
    nativeLog(
            ANDROID_LOG_INFO,
            "prepareSession start adamId=%s uriLen=%zu preshare=%d",
            adamId,
            strlen(uri),
            isPreshare ? 1 : 0);
    if (isPreshare) {
        resetAllContexts(gSessionCtrl);
        gPreshareKdContext = nullptr;
        gKdContext = nullptr;
    }

    void *persistentKeyStorage[2] = {nullptr, nullptr};
    StdString adamIdStr;
    StdString uriStr;
    StdString keyFormat;
    StdString keyFormatVersion;
    StdString serverUri;
    StdString protocolType;
    StdString fairPlayCertificate;
    initStdString(&adamIdStr, adamId);
    initStdString(&uriStr, uri);
    initStdString(&keyFormat, kKeyFormat);
    initStdString(&keyFormatVersion, kKeyFormatVersion);
    initStdString(&serverUri, kServerUri);
    initStdString(&protocolType, kProtocolType);
    initFairPlayCertificate(&fairPlayCertificate);
    const bool persistentKeyCallOk = callGetPersistentKeySafely(
            gResolver.getPersistentKey,
            gSessionCtrl,
            adamIdStr.storage,
            adamIdStr.storage,
            uriStr.storage,
            keyFormat.storage,
            keyFormatVersion.storage,
            serverUri.storage,
            protocolType.storage,
            persistentKeyStorage,
            fairPlayCertificate.storage);

    destroyStdString(&fairPlayCertificate);
    destroyStdString(&protocolType);
    destroyStdString(&serverUri);
    destroyStdString(&keyFormatVersion);
    destroyStdString(&keyFormat);
    destroyStdString(&uriStr);
    destroyStdString(&adamIdStr);

    void *persistentKey = persistentKeyStorage[0];
    if (!persistentKeyCallOk || persistentKey == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "persistent key is null adamId=%s callOk=%d", adamId, persistentKeyCallOk ? 1 : 0);
        if (isPreshare) {
            gPreshareKdContext = nullptr;
        } else {
            gKdContext = nullptr;
        }
        return false;
    }

    void *decryptedKeyStorage[2] = {nullptr, nullptr};
    const bool decryptContextCallOk = callDecryptContextSafely(gResolver.decryptContext, gSessionCtrl, persistentKey, decryptedKeyStorage);
    void *decryptedKey = decryptedKeyStorage[0];
    void *kdContext = readPointerAt(decryptedKey, 0x18);
    if (isPreshare) {
        gPreshareKdContext = kdContext;
        gKdContext = kdContext;
    } else {
        gKdContext = kdContext;
    }
    nativeLog(
            ANDROID_LOG_INFO,
            "prepareSession adamId=%s kdContext=%p preshare=%p",
            adamId,
            kdContext,
            gPreshareKdContext);

    return decryptContextCallOk && kdContext != nullptr;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeIsAvailable(JNIEnv *, jobject) {
    const char *status = resolverStatus();
    nativeLog(ANDROID_LOG_INFO, "native bridge resolver status=%s", status);
    return status[0] == 'r' ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeResolverStatus(JNIEnv *env, jobject) {
    return env->NewStringUTF(resolverStatus());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativePrepareSession(
        JNIEnv *env,
        jobject,
        jstring adamId,
        jstring uri) {
    const char *adamIdChars = jstringToUtfChars(env, adamId);
    const char *uriChars = jstringToUtfChars(env, uri);
    const bool prepared = prepareSession(adamIdChars, uriChars);
    if (adamIdChars != nullptr) {
        env->ReleaseStringUTFChars(adamId, adamIdChars);
    }
    if (uriChars != nullptr) {
        env->ReleaseStringUTFChars(uri, uriChars);
    }
    return prepared ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeDecryptSample(
        JNIEnv *env,
        jobject,
        jbyteArray sample) {
    LOCK_GLOBAL();
    gLastDecryptReturnValue = 0;
    if (sample == nullptr || gKdContext == nullptr || resolverStatus()[0] != 'r') {
        nativeLog(
                ANDROID_LOG_WARN,
                "decryptSample rejected sample=%p kdContext=%p status=%s",
                sample,
                gKdContext,
                resolverStatus());
        return env->NewByteArray(0);
    }

    const jsize sampleSize = env->GetArrayLength(sample);
    jbyteArray output = env->NewByteArray(sampleSize);
    if (output == nullptr) {
        return env->NewByteArray(0);
    }

    jbyte *sampleBytes = env->GetByteArrayElements(sample, nullptr);
    if (sampleBytes == nullptr) {
        return env->NewByteArray(0);
    }

    env->SetByteArrayRegion(output, 0, sampleSize, sampleBytes);
    jbyte *outputBytes = env->GetByteArrayElements(output, nullptr);
    if (outputBytes == nullptr) {
        env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
        return env->NewByteArray(0);
    }

    void *decryptContext = readPointerAt(gKdContext, 0);
    if (decryptContext == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "decryptSample rejected: decryptContext is null kdContext=%p", gKdContext);
        env->ReleaseByteArrayElements(output, outputBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
        return env->NewByteArray(0);
    }

    nativeLog(ANDROID_LOG_INFO, "decryptSample start in=%d kdContext=%p decryptContext=%p", sampleSize, gKdContext, decryptContext);
    unsigned long returnValue = 0;
    const bool callOk = callDecryptSampleSafely(
            gResolver.decryptSample,
            decryptContext,
            5,
            sampleBytes,
            outputBytes,
             static_cast<size_t>(sampleSize),
             &returnValue);
    gLastDecryptReturnValue = returnValue;
    nativeLog(ANDROID_LOG_INFO, "decryptSample done out=%d ret=%lu callOk=%d", sampleSize, returnValue, callOk ? 1 : 0);

    if (!callOk) {
        env->ReleaseByteArrayElements(output, outputBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
        return env->NewByteArray(0);
    }

    env->ReleaseByteArrayElements(output, outputBytes, 0);
    env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeLastDecryptReturnValue(JNIEnv *, jobject) {
    return static_cast<jlong>(gLastDecryptReturnValue);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeDecryptSamples(
        JNIEnv *env,
        jobject,
        jobjectArray samples) {
    LOCK_GLOBAL();
    if (samples == nullptr || gKdContext == nullptr || resolverStatus()[0] != 'r') {
        nativeLog(ANDROID_LOG_WARN, "decryptSamples rejected: samples/context null or resolver not ready");
        return nullptr;
    }

    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) {
        return nullptr;
    }

    jclass byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(count, byteArrayClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    for (jsize i = 0; i < count; i++) {
        jbyteArray sample = static_cast<jbyteArray>(env->GetObjectArrayElement(samples, i));
        if (sample == nullptr) {
            env->SetObjectArrayElement(result, i, env->NewByteArray(0));
            continue;
        }

        const jsize sampleSize = env->GetArrayLength(sample);
        jbyteArray output = env->NewByteArray(sampleSize);
        if (output == nullptr) {
            env->SetObjectArrayElement(result, i, env->NewByteArray(0));
            env->DeleteLocalRef(sample);
            continue;
        }

        jbyte *sampleBytes = env->GetByteArrayElements(sample, nullptr);
        if (sampleBytes == nullptr) {
            env->SetObjectArrayElement(result, i, env->NewByteArray(0));
            env->DeleteLocalRef(output);
            env->DeleteLocalRef(sample);
            continue;
        }

        env->SetByteArrayRegion(output, 0, sampleSize, sampleBytes);
        jbyte *outputBytes = env->GetByteArrayElements(output, nullptr);
        if (outputBytes == nullptr) {
            env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
            env->SetObjectArrayElement(result, i, env->NewByteArray(0));
            env->DeleteLocalRef(output);
            env->DeleteLocalRef(sample);
            continue;
        }

        void *decryptContext = readPointerAt(gKdContext, 0);
        if (decryptContext != nullptr) {
            unsigned long returnValue = 0;
            callDecryptSampleSafely(
                    gResolver.decryptSample,
                    decryptContext,
                    5,
                    sampleBytes,
                    outputBytes,
                    static_cast<size_t>(sampleSize),
                    &returnValue);
        }

        env->ReleaseByteArrayElements(output, outputBytes, 0);
        env->ReleaseByteArrayElements(sample, sampleBytes, JNI_ABORT);
        env->SetObjectArrayElement(result, i, output);
        env->DeleteLocalRef(output);
        env->DeleteLocalRef(sample);
    }

    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_neurax08_xposed_appledecryptor_AppleMusicNativeBridge_nativeDrainLogs(JNIEnv *env, jobject) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(gNativeLogCount), stringClass, nullptr);
    if (result == nullptr) {
        gNativeLogCount = 0;
        return nullptr;
    }

    for (size_t i = 0; i < gNativeLogCount; i++) {
        jstring entry = env->NewStringUTF(gNativeLogs[i]);
        if (entry != nullptr) {
            env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
            env->DeleteLocalRef(entry);
        }
    }
    gNativeLogCount = 0;
    return result;
}

extern "C" [[gnu::visibility("default")]] void native_init() {
    nativeLog(ANDROID_LOG_INFO, "native_init called");
}
