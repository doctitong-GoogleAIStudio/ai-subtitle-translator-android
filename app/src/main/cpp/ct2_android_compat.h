// Force-included into the CTranslate2 target (see CMakeLists.txt) so the vendored source
// stays unmodified. Android's bionic defines __linux__ but has no pthread_setaffinity_np;
// this maps it onto sched_setaffinity for the thread's kernel id. The app never pins
// threads (ReplicaPoolConfig::cpu_core_offset stays -1); this only has to be correct.
#pragma once

#if defined(__ANDROID__) && defined(__cplusplus)
#include <pthread.h>
#include <sched.h>
#include <errno.h>

static inline int pthread_setaffinity_np(pthread_t thread, size_t size, const cpu_set_t *set) {
    return sched_setaffinity(pthread_gettid_np(thread), size, set) == 0 ? 0 : errno;
}
#endif
