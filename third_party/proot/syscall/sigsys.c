/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*-
 *
 * This file is part of PRoot.
 *
 * Copyright (C) 2015 STMicroelectronics
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 */

#include <errno.h>              /* E*, */
#include <signal.h>             /* SIGSYS, */
#include <sys/ptrace.h>         /* ptrace(2), PTRACE_GETSIGINFO, */
#include <stdbool.h>            /* bool, */

#include "syscall/sigsys.h"
#include "syscall/sysnum.h"
#include "tracee/tracee.h"
#include "tracee/reg.h"
#include "arch.h"
#include "cli/note.h"

/* Android's app seccomp policy traps the legacy syscalls below; the guest's
 * glibc uses them because it was not built for Android. Each is rewritten to
 * its *at form, which the policy allows. The remaining argument registers are
 * shifted to make room for the leading dirfd/AT_FDCWD. */

/**
 * Move the syscall's instruction pointer back onto the syscall instruction so
 * the rewritten syscall is re-executed from the trap, then push the registers.
 */
static void rewind_and_restart(Tracee *tracee)
{
    word_t instr_pointer = peek_reg(tracee, CURRENT, INSTR_POINTER);

    poke_reg(tracee, INSTR_POINTER, instr_pointer - SYSTRAP_SIZE);

    /* The syscall never entered the kernel, so PRoot must translate it as a
     * fresh sysenter; a sysexit is not pending. */
    tracee->status = 0;
    tracee->restore_original_regs = false;
    tracee->restart_how = PTRACE_SYSCALL;

#if defined(ARCH_X86_64)
    /* The rewound `syscall` instruction is re-executed, and it reads the
     * number from rax, not from orig_rax. set_sysnum() wrote orig_rax, so copy
     * it into rax for the re-execution. */
    tracee->_regs[CURRENT].rax = tracee->_regs[CURRENT].orig_rax;
#elif defined(ARCH_X86)
    tracee->_regs[CURRENT].eax = tracee->_regs[CURRENT].orig_eax;
#endif

    (void) push_regs(tracee);
}

/** open(path, flags, mode) -> openat(AT_FDCWD, path, flags, mode) */
static void rewrite_open(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_4, peek_reg(tracee, CURRENT, SYSARG_3));
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_openat);
}

/** access(path, mode) -> faccessat(AT_FDCWD, path, mode, 0) */
static void rewrite_access(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_4, 0);
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_faccessat);
}

/** stat/lstat(path, buf) -> newfstatat(AT_FDCWD, path, buf, flags) */
static void rewrite_stat(Tracee *tracee, bool follow)
{
    poke_reg(tracee, SYSARG_4, follow ? 0 : AT_SYMLINK_NOFOLLOW);
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_newfstatat);
}

/** mkdir(path, mode) -> mkdirat(AT_FDCWD, path, mode) */
static void rewrite_mkdir(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_mkdirat);
}

/** rename(old, new) -> renameat(AT_FDCWD, old, AT_FDCWD, new) */
static void rewrite_rename(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_4, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_3, AT_FDCWD);
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_renameat);
}

/** unlink(path) -> unlinkat(AT_FDCWD, path, 0); rmdir(path) -> unlinkat(..., AT_REMOVEDIR) */
static void rewrite_unlink(Tracee *tracee, bool directory)
{
    poke_reg(tracee, SYSARG_3, directory ? AT_REMOVEDIR : 0);
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_unlinkat);
}

/** chmod(path, mode) -> fchmodat(AT_FDCWD, path, mode, 0) */
static void rewrite_chmod(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_4, 0);
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_fchmodat);
}

/** chown/lchown(path, owner, group) -> fchownat(AT_FDCWD, path, owner, group, flags) */
static void rewrite_chown(Tracee *tracee, bool follow)
{
    poke_reg(tracee, SYSARG_5, follow ? 0 : AT_SYMLINK_NOFOLLOW);
    poke_reg(tracee, SYSARG_4, peek_reg(tracee, CURRENT, SYSARG_3));
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_fchownat);
}

/** symlink(target, linkpath) -> symlinkat(target, AT_FDCWD, linkpath) */
static void rewrite_symlink(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_3, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_2, AT_FDCWD);
    set_sysnum(tracee, PR_symlinkat);
}

/** link(old, new) -> linkat(AT_FDCWD, old, AT_FDCWD, new, 0) */
static void rewrite_link(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_5, 0);
    poke_reg(tracee, SYSARG_4, peek_reg(tracee, CURRENT, SYSARG_2));
    poke_reg(tracee, SYSARG_3, AT_FDCWD);
    poke_reg(tracee, SYSARG_2, peek_reg(tracee, CURRENT, SYSARG_1));
    poke_reg(tracee, SYSARG_1, AT_FDCWD);
    set_sysnum(tracee, PR_linkat);
}

/** pipe(fds) -> pipe2(fds, 0) */
static void rewrite_pipe(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_2, 0);
    set_sysnum(tracee, PR_pipe2);
}

/** dup2(old, new) -> dup3(old, new, 0) */
static void rewrite_dup2(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_3, 0);
    set_sysnum(tracee, PR_dup3);
}

/** waitpid(pid, status, options) -> wait4(pid, status, options, NULL) */
static void rewrite_waitpid(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_4, 0);
    set_sysnum(tracee, PR_wait4);
}

/** epoll_wait(epfd, events, max, timeout) -> epoll_pwait(..., NULL, 0) */
static void rewrite_epoll_wait(Tracee *tracee)
{
    poke_reg(tracee, SYSARG_5, 0);
    poke_reg(tracee, SYSARG_6, 0);
    set_sysnum(tracee, PR_epoll_pwait);
}

/**
 * Complete the blocked syscall with a fake @result, without re-executing it.
 *
 * Used for a syscall with no *at equivalent. The blocked syscall was cancelled
 * by the policy, so there is nothing to run; the guest is told the call failed
 * with the given errno. glibc treats ENOSYS as "not implemented" and falls back,
 * which is why it is the default: a kernel without the call is a case every
 * libc already handles.
 */
static void fake_result(Tracee *tracee, word_t result)
{
    poke_reg(tracee, SYSARG_RESULT, result);

    tracee->status = 0;
    tracee->restore_original_regs = false;
    tracee->restart_how = PTRACE_SYSCALL;

    (void) push_regs(tracee);
}

int handle_sigsys_event(Tracee *tracee, long blocked_sysnum)
{
    Sysnum sysnum;

    if (fetch_regs(tracee) < 0)
        return SIGSYS;

    /* The siginfo is authoritative: at the SIGSYS the cancelled syscall's
     * number is not in the register the tracer would normally read. */
    sysnum = translate_sysnum(get_abi(tracee), (word_t) blocked_sysnum);

    switch (sysnum) {
    case PR_open:      rewrite_open(tracee); break;
    case PR_access:    rewrite_access(tracee); break;
    case PR_stat:      rewrite_stat(tracee, true); break;
    case PR_lstat:     rewrite_stat(tracee, false); break;
    case PR_mkdir:     rewrite_mkdir(tracee); break;
    case PR_rename:    rewrite_rename(tracee); break;
    case PR_unlink:    rewrite_unlink(tracee, false); break;
    case PR_rmdir:     rewrite_unlink(tracee, true); break;
    case PR_chmod:     rewrite_chmod(tracee); break;
    case PR_chown:     rewrite_chown(tracee, true); break;
    case PR_lchown:    rewrite_chown(tracee, false); break;
    case PR_symlink:   rewrite_symlink(tracee); break;
    case PR_link:      rewrite_link(tracee); break;
    case PR_pipe:      rewrite_pipe(tracee); break;
    case PR_dup2:      rewrite_dup2(tracee); break;
    case PR_waitpid:   rewrite_waitpid(tracee); break;
    case PR_epoll_wait: rewrite_epoll_wait(tracee); break;

    default:
        /* No *at equivalent: report ENOSYS. The policy would have killed the
         * guest outright; a libc that sees ENOSYS falls back, which is how the
         * call behaves on a kernel that lacks it. */
        fake_result(tracee, -ENOSYS);

        return 0;
    }

    rewind_and_restart(tracee);

    return 0;
}
