// The real [Host] — see host_system.cpp for the implementation notes.

#ifndef VYZORIX_HOST_SYSTEM_H
#define VYZORIX_HOST_SYSTEM_H

#include "vyzorix/supervisor.h"

namespace vsup {

/** [Host] backed by fork/exec, waitpid, signals and CLOCK_MONOTONIC. */
class SystemHost : public Host {
public:
    int Spawn(const std::string &cmd, const std::string &dir,
              const std::map<std::string, std::string> &env, const std::string &logPath) override;

    bool Exited(int pid, ExitKind *kind, int *code) override;
    void Terminate(int pid) override;
    void Kill(int pid) override;
    std::uint64_t StartTicks(int pid) override;
    std::int64_t NowMs() override;
    void SleepMs(std::int64_t ms) override;
};

} // namespace vsup

#endif // VYZORIX_HOST_SYSTEM_H
