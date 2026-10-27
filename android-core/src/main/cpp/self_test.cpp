// Self-tests for paths that are awkward to drive from Kotlin.
//
// The fd-passing code is the case in point: exercising it through Kotlin would
// mostly exercise Kotlin's FileDescriptor API. Here it runs entirely natively —
// socketpair, send a real descriptor, receive it, write through the copy — so
// the code under test is the SCM_RIGHTS implementation itself.
//
// detail on success carries a small summary ("received=1, roundtrip=ok") so the
// Kotlin assertion has something specific to check.

#include <jni.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <sys/socket.h>
#include <unistd.h>

#include "include/pty_broker.h"

namespace {

constexpr size_t kDetailSize = 512;

jobject MakeResult(JNIEnv *env, vyzorix::pty::Status status, const char *detail) {
    jclass cls = env->FindClass("com/vyzorix/capability/NativePtyResult");
    if (cls == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;[I[BZZ)V");
    if (ctor == nullptr) {
        return nullptr;
    }

    jstring jdetail = env->NewStringUTF(detail);
    jobject result = env->NewObject(cls, ctor, static_cast<jint>(status), jdetail, nullptr,
                                    nullptr, static_cast<jboolean>(false),
                                    static_cast<jboolean>(false));
    env->DeleteLocalRef(jdetail);

    return result;
}

}  // namespace

extern "C" {

// NativePtyResult selfTestFdPassing()
//
// detail = "received=<n>, roundtrip=<ok|fail>"
JNIEXPORT jobject JNICALL
Java_com_vyzorix_capability_NativePty_selfTestFdPassing(JNIEnv *env, jclass /*clazz*/) {
    try {
        int sockets[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) != 0) {
            char detail[kDetailSize];
            snprintf(detail, sizeof(detail), "socketpair: %s", strerror(errno));

            return MakeResult(env, vyzorix::pty::Status::SyscallFailed, detail);
        }

        // Send a real fd: a pipe's read end. A pipe is used rather than a PTY so
        // this test does not depend on PTY allocation succeeding.
        int pipe_fds[2];
        if (pipe(pipe_fds) != 0) {
            char detail[kDetailSize];
            snprintf(detail, sizeof(detail), "pipe: %s", strerror(errno));
            close(sockets[0]);
            close(sockets[1]);

            return MakeResult(env, vyzorix::pty::Status::SyscallFailed, detail);
        }

        char send_detail[kDetailSize] = {0};
        vyzorix::pty::Status send_status =
            vyzorix::pty::SendFds(sockets[0], &pipe_fds[0], 1, nullptr, 0, send_detail,
                                  sizeof(send_detail));
        if (send_status != vyzorix::pty::Status::Ok) {
            close(pipe_fds[0]);
            close(pipe_fds[1]);
            close(sockets[0]);
            close(sockets[1]);

            return MakeResult(env, send_status, send_detail);
        }

        char recv_detail[kDetailSize] = {0};
        vyzorix::pty::FdMessage message;
        vyzorix::pty::Status recv_status =
            vyzorix::pty::ReceiveFds(sockets[1], 2000, &message, recv_detail, sizeof(recv_detail));
        if (recv_status != vyzorix::pty::Status::Ok) {
            close(pipe_fds[0]);
            close(pipe_fds[1]);
            close(sockets[0]);
            close(sockets[1]);

            return MakeResult(env, recv_status, recv_detail);
        }

        // Write to the original write end, read through the *received* read end.
        // If the descriptor really crossed the socket, the byte appears.
        const char payload[] = "x";
        bool roundtrip = false;
        if (write(pipe_fds[1], payload, 1) == 1 && !message.fds.empty()) {
            char byte = 0;
            roundtrip = read(message.fds[0], &byte, 1) == 1 && byte == 'x';
        }

        for (int fd : message.fds) {
            close(fd);
        }
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        close(sockets[0]);
        close(sockets[1]);

        char detail[kDetailSize];
        snprintf(detail, sizeof(detail), "received=%zu, roundtrip=%s", message.fds.size(),
                 roundtrip ? "ok" : "fail");

        if (!roundtrip) {
            return MakeResult(env, vyzorix::pty::Status::FdPassFailed, detail);
        }

        return MakeResult(env, vyzorix::pty::Status::Ok, detail);
    } catch (...) {
        return MakeResult(env, vyzorix::pty::Status::Internal, "unexpected exception");
    }
}

}  // extern "C"
