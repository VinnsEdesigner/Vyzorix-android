// JNI surface for the PTY broker.
//
// Nothing crosses this boundary as an exception. A C++ exception unwinding into
// the JVM is undefined behaviour, so every entry point is wrapped and converts
// an unexpected throw into a failure result.
//
// Symbol names must match the Kotlin class exactly; a mismatch fails at runtime
// with UnsatisfiedLinkError, not at compile time.

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <exception>

#include <unistd.h>
#include <string>
#include <vector>

#include "include/pty_broker.h"

using vyzorix::pty::ExecRequest;
using vyzorix::pty::FdMessage;
using vyzorix::pty::PtyPair;
using vyzorix::pty::ReadResult;
using vyzorix::pty::Status;
using vyzorix::pty::WindowSize;

namespace {

constexpr size_t kDetailSize = 512;

// com.vyzorix.capability.NativePtyResult(int, String, int[], byte[], boolean, boolean)
constexpr const char *kResultClass = "com/vyzorix/capability/NativePtyResult";
constexpr const char *kResultSignature = "(ILjava/lang/String;[I[BZZ)V";

std::string ToString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);

    return out;
}

jobject MakeResult(JNIEnv *env, Status status, const char *detail, jintArray fds = nullptr,
                   jbyteArray bytes = nullptr, bool timed_out = false, bool eof = false) {
    jclass cls = env->FindClass(kResultClass);
    if (cls == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", kResultSignature);
    if (ctor == nullptr) {
        return nullptr;
    }

    jstring jdetail = env->NewStringUTF(detail != nullptr ? detail : "");
    jobject result = env->NewObject(cls, ctor, static_cast<jint>(status), jdetail, fds, bytes,
                                    static_cast<jboolean>(timed_out),
                                    static_cast<jboolean>(eof));
    env->DeleteLocalRef(jdetail);

    return result;
}

// Converts an unexpected C++ exception into a failure result so it cannot reach
// the JVM.
template <typename Fn>
jobject Guarded(JNIEnv *env, Fn &&body) {
    try {
        return body();
    } catch (const std::exception &e) {
        char detail[kDetailSize];
        snprintf(detail, sizeof(detail), "unexpected exception: %s", e.what());

        return MakeResult(env, Status::Internal, detail);
    } catch (...) {
        return MakeResult(env, Status::Internal, "unexpected non-standard exception");
    }
}

std::vector<int> ReadIntArray(JNIEnv *env, jintArray array) {
    std::vector<int> out;
    if (array == nullptr) {
        return out;
    }
    jsize n = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(n));
    if (n > 0) {
        env->GetIntArrayRegion(array, 0, n, out.data());
    }

    return out;
}

std::vector<std::string> ReadStringArray(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) {
        return out;
    }
    jsize n = env->GetArrayLength(array);
    for (jsize i = 0; i < n; i++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(ToString(env, item));
        env->DeleteLocalRef(item);
    }

    return out;
}

}  // namespace

extern "C" {

// NativePtyResult openPty()
// On success fds = [master, slave] and detail = slave path.
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_openPty(JNIEnv *env, jclass /*clazz*/) {
    return Guarded(env, [env]() -> jobject {
        char detail[kDetailSize] = {0};
        PtyPair pair;

        Status status = vyzorix::pty::OpenPty(&pair, detail, sizeof(detail));
        if (status != Status::Ok) {
            return MakeResult(env, status, detail);
        }

        jintArray fds = env->NewIntArray(2);
        jint values[2] = {pair.master, pair.slave};
        env->SetIntArrayRegion(fds, 0, 2, values);

        jobject result = MakeResult(env, Status::Ok, pair.slave_path.c_str(), fds);
        env->DeleteLocalRef(fds);

        return result;
    });
}

// NativePtyResult spawnOnPty(masterFd, slaveFd, path, argv, env, workingDir, rows, cols)
// On success fds = [pid].
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_spawnOnPty(
    JNIEnv *env, jclass /*clazz*/, jint master_fd, jint slave_fd, jstring path,
    jobjectArray argv, jobjectArray envp, jstring working_dir, jint rows, jint cols) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        ExecRequest request;
        request.path = ToString(env, path);
        request.working_dir = ToString(env, working_dir);
        request.argv = ReadStringArray(env, argv);
        request.env = ReadStringArray(env, envp);
        request.window.rows = static_cast<uint16_t>(rows);
        request.window.cols = static_cast<uint16_t>(cols);

        PtyPair pair;
        pair.master = master_fd;
        pair.slave = slave_fd;

        pid_t pid = -1;
        Status status = vyzorix::pty::SpawnOnPty(pair, request, &pid, detail, sizeof(detail));
        if (status != Status::Ok) {
            return MakeResult(env, status, detail);
        }

        jintArray fds = env->NewIntArray(1);
        jint value = static_cast<jint>(pid);
        env->SetIntArrayRegion(fds, 0, 1, &value);

        jobject result = MakeResult(env, Status::Ok, "", fds);
        env->DeleteLocalRef(fds);

        return result;
    });
}

// NativePtyResult readMaster(fd, maxBytes, timeoutMs)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_readMaster(JNIEnv *env, jclass /*clazz*/, jint fd,
                                                 jint max_bytes, jint timeout_ms) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        if (max_bytes <= 0) {
            max_bytes = 4096;
        }

        std::vector<uint8_t> buffer(static_cast<size_t>(max_bytes));
        ReadResult read_result;

        Status status = vyzorix::pty::ReadMaster(fd, buffer.data(), buffer.size(), timeout_ms,
                                                 &read_result, detail, sizeof(detail));

        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(read_result.bytes));
        if (read_result.bytes > 0) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(read_result.bytes),
                                    reinterpret_cast<const jbyte *>(buffer.data()));
        }

        jobject result = MakeResult(env, status, detail, nullptr, bytes, read_result.timed_out,
                                    read_result.eof);
        env->DeleteLocalRef(bytes);

        return result;
    });
}

// NativePtyResult writeMaster(fd, data)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_writeMaster(JNIEnv *env, jclass /*clazz*/, jint fd,
                                                  jbyteArray data) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        if (data == nullptr) {
            return MakeResult(env, Status::InvalidArgument, "data is null");
        }

        jsize len = env->GetArrayLength(data);
        std::vector<uint8_t> buffer(static_cast<size_t>(len));
        if (len > 0) {
            env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buffer.data()));
        }

        size_t written = 0;
        Status status = vyzorix::pty::WriteMaster(fd, buffer.data(), buffer.size(), &written,
                                                  detail, sizeof(detail));

        return MakeResult(env, status, detail);
    });
}

// NativePtyResult setWindowSize(fd, rows, cols)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_setWindowSize(JNIEnv *env, jclass /*clazz*/, jint fd,
                                                    jint rows, jint cols) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        WindowSize size;
        size.rows = static_cast<uint16_t>(rows);
        size.cols = static_cast<uint16_t>(cols);

        Status status = vyzorix::pty::SetWindowSize(fd, size, detail, sizeof(detail));

        return MakeResult(env, status, detail);
    });
}

// NativePtyResult killChild(pid)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_killChild(JNIEnv *env, jclass /*clazz*/, jint pid) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};
        Status status = vyzorix::pty::KillChild(static_cast<pid_t>(pid), detail, sizeof(detail));

        return MakeResult(env, status, detail);
    });
}

// NativePtyResult reapChild(pid) — on success fds = [reaped ? 1 : 0, exitCode].
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_reapChild(JNIEnv *env, jclass /*clazz*/, jint pid) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        bool reaped = false;
        int32_t exit_code = 0;
        Status status = vyzorix::pty::ReapChild(static_cast<pid_t>(pid), &reaped, &exit_code,
                                                detail, sizeof(detail));
        if (status != Status::Ok) {
            return MakeResult(env, status, detail);
        }

        jintArray values = env->NewIntArray(2);
        jint pair[2] = {reaped ? 1 : 0, static_cast<jint>(exit_code)};
        env->SetIntArrayRegion(values, 0, 2, pair);

        jobject result = MakeResult(env, Status::Ok, "", values);
        env->DeleteLocalRef(values);

        return result;
    });
}

// NativePtyResult closeFd(fd)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_closeFd(JNIEnv *env, jclass /*clazz*/, jint fd) {
    return Guarded(env, [&]() -> jobject {
        if (fd < 0) {
            return MakeResult(env, Status::InvalidArgument, "fd is negative");
        }

        // EBADF means it was already closed, which is the desired end state.
        if (close(fd) != 0 && errno != EBADF) {
            char detail[kDetailSize];
            snprintf(detail, sizeof(detail), "close: %s", strerror(errno));

            return MakeResult(env, Status::SyscallFailed, detail);
        }

        return MakeResult(env, Status::Ok, "");
    });
}

// NativePtyResult sendFds(socketFd, fds)
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_sendFds(JNIEnv *env, jclass /*clazz*/, jint socket_fd,
                                              jintArray fds) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        std::vector<int> descriptors = ReadIntArray(env, fds);
        Status status = vyzorix::pty::SendFds(socket_fd, descriptors.data(), descriptors.size(),
                                              nullptr, 0, detail, sizeof(detail));

        return MakeResult(env, status, detail);
    });
}

// NativePtyResult receiveFds(socketFd, timeoutMs) — received fds come back in fds[].
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_receiveFds(JNIEnv *env, jclass /*clazz*/, jint socket_fd,
                                                 jint timeout_ms) {
    return Guarded(env, [&]() -> jobject {
        char detail[kDetailSize] = {0};

        FdMessage message;
        Status status = vyzorix::pty::ReceiveFds(socket_fd, timeout_ms, &message, detail,
                                                 sizeof(detail));
        if (status != Status::Ok) {
            return MakeResult(env, status, detail);
        }

        jintArray values = env->NewIntArray(static_cast<jsize>(message.fds.size()));
        if (!message.fds.empty()) {
            env->SetIntArrayRegion(values, 0, static_cast<jsize>(message.fds.size()),
                                   message.fds.data());
        }

        jobject result = MakeResult(env, Status::Ok, "", values);
        env->DeleteLocalRef(values);

        return result;
    });
}

}  // extern "C"
