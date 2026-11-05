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

#ifndef TRACEE_SIGSYS_H
#define TRACEE_SIGSYS_H

#include "tracee/tracee.h"

/**
 * Handle a SIGSYS raised by the outer (host) seccomp policy.
 *
 * The policy uses SECCOMP_RET_TRAP, which cancels the syscall and is
 * evaluated before PRoot's PTRACE_TRACEME, so PRoot never sees the syscall
 * and cannot translate it. A guest glibc built for x86_64 issues the legacy
 * open(2), which that policy blocks.
 *
 * This rewrites the cancelled syscall into an *at form the policy allows,
 * rewinds the instruction pointer to the trap, and lets PRoot handle the
 * restarted syscall normally (path translation included).
 *
 * Returns 0 to swallow the signal (the syscall was emulated), or SIGSYS to
 * deliver it to the tracee (nothing could be done).
 */
extern int handle_sigsys_event(Tracee *tracee, long sysnum);

#endif /* TRACEE_SIGSYS_H */
