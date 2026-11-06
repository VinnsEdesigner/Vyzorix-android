// Per-app metadata: one file per app, in `meta/`, alongside `logs/`.
//
// ## Why a directory of files and not a field in `apps.json`
//
// `apps.json` is the registry of what runs: name, command, autostart, and the pid
// record the supervisor maintains. It is read and written on every start and stop,
// and it is the file an operator edits.
//
// Metadata is different in three ways, and each argues for its own file:
//
//   * It is **per-app and independent** — exposing one app must not rewrite the
//     record of every other app, which is what a shared file means on a crash.
//   * It **contains a secret**. An expose token lives here. Keeping secrets out of
//     the file an operator edits by hand is worth a directory on its own.
//   * It is **derived state** — losing it costs a re-expose, not a reconfiguration,
//     so it does not belong in the file that is the source of truth.
//
// The layout mirrors `logs/`: `meta/<name>.json` is to metadata what
// `logs/<name>.log` is to output.

#ifndef VYZORIX_APP_META_STORE_H
#define VYZORIX_APP_META_STORE_H

#include <string>

namespace vmeta {

/** How an app is exposed to the network. */
struct Expose {
    /** The address the proxy listens on. `127.0.0.1`, or `0.0.0.0` to share. */
    std::string bind = "127.0.0.1";

    /** The port the proxy listens on. */
    int port = 0;

    /**
     * The token a non-loopback bind requires.
     *
     * A secret. It is written to `meta/<name>.json`, which is created `0600` in
     * app-private storage — the same protection everything else here has, and no
     * more. It is deliberately **not** in `apps.json`, which is the file a human
     * reads and edits.
     */
    std::string token;
};

/** Everything recorded about one app outside the registry. */
struct AppMeta {
    std::string name;

    /** True when the app is exposed. [expose] is meaningful only when it is. */
    bool exposed = false;

    Expose expose;
};

/** The metadata file for `name` in `metaDir`. */
std::string MetaPath(const std::string &metaDir, const std::string &name);

/**
 * Loads `name`'s metadata.
 *
 * A missing file is an app with no metadata — not exposed — which is the state of
 * every app that has never been exposed. A malformed file throws, so "unreadable"
 * and "no metadata" are distinguishable: silently treating a corrupt file as
 * "not exposed" would drop a running proxy's configuration without saying so.
 */
AppMeta Load(const std::string &metaDir, const std::string &name);

/**
 * Writes `name`'s metadata atomically, mode 0600.
 *
 * Write-temp-then-rename, like the registry, so a crash cannot leave a file that
 * will not parse. The mode is 0600 because the file may hold a token.
 */
void Save(const std::string &metaDir, const AppMeta &meta);

/**
 * Removes `name`'s metadata file. Returns whether one was removed.
 *
 * Used by `unexpose` and by `remove`: an app that no longer exists must not leave a
 * metadata file behind, or the next app of the same name inherits its token.
 */
bool Remove(const std::string &metaDir, const std::string &name);

/** Creates `metaDir` if it does not exist. Returns false when it cannot. */
bool EnsureDir(const std::string &metaDir);

} // namespace vmeta

#endif // VYZORIX_APP_META_STORE_H
