# `releases/` — published container assets

The rootfs tarballs are built here and published to a GitHub release **in a different,
public repository**: [`VinnsEdesigner/gz-_ASSETS`](https://github.com/VinnsEdesigner/gz-_ASSETS).

**This repository is private, and that decides where the release lives.** GitHub
serves a private repository's release assets only to authenticated callers, so a
device in a member of the public's hands gets a 404 from
`.../Vyzorix-android/releases/latest/download/...`. The tarballs are Debian packages,
public upstream, so they belong somewhere public. The code stays private; the assets
do not.

This directory is the **staging area**, not the home: the tarballs are ~150 MB each
and are gitignored, so what lives in git is this README and the manifest.

## What is published

`scripts/build-rootfs.sh` writes the tarballs to `debian-container/rootfs/dist/`. Run it
once per shipped ABI:

```bash
for abi in arm64 armeabi-v7a x86_64; do
  scripts/build-rootfs.sh --arch "$abi"
done
```

That produces, per ABI:

| File | Purpose |
|:--|:--|
| `debian-core-<abi>.tar.gz` | The container's base userspace. **Bundled in the APK** for `arm64`; fetched on first boot by other ABIs. |
| `debian-extended-<abi>.tar.gz` | Optional tooling (`git`, `python3`, `tmux`), fetched on demand. |
| `BUILD-<abi>.md` | The build record: package sets and resolved versions. |
| `SHA256SUMS` | Every tarball. The device verifies against this before extracting. |

## Publishing a release

```bash
VERSION=v1.0.0
gh release create "$VERSION" \
  --repo VinnsEdesigner/gz-_ASSETS \
  --title "Container assets $VERSION" \
  --notes "Rootfs tarballs for arm64, armeabi-v7a and x86_64." \
  debian-container/rootfs/dist/*.tar.gz \
  debian-container/rootfs/dist/SHA256SUMS \
  debian-container/rootfs/dist/BUILD-*.md
```

Note the `--repo`: the **public assets repository**, not this one.

The app fetches from
`https://github.com/VinnsEdesigner/gz-_ASSETS/releases/latest/download/`, so
a release marked **latest** is what devices pick up. `ROOTFS_BASE_URL` in the build
config points there; a fork overrides it with `-ProotfsBaseUrl=...`.

## Why the app needs the checksum from the release

A device whose ABI is not the one bundled downloads its rootfs. That is bytes off the
network, so the installer refuses to extract without a checksum — an unverified
rootfs is an attacker's userspace, the same argument as the pinned server key. The
bundled asset needs no separate check: the APK signature covers it.

## Which ABI is bundled

`arm64`, because it is the large majority of devices in use. A universal APK cannot
carry all three — `assets/` is shared across ABIs, unlike `jniLibs/` — and bundling
all three would add ~134 MB for a minority. Build an ABI-specific APK with
`-ProotfsAbi=<abi>`; an app bundle does this properly and is the eventual answer.
