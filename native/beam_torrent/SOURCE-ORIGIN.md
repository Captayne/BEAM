# Native source snapshot

Exported from the local BEAM development repositories on 2026-09-28.
The libtorrent revision includes the local BBR changes. Repository metadata,
build products and toolchains are excluded. Existing upstream licenses are retained.

| Component | Source | Commit |
| --- | --- | --- |
| libtorrent | https://github.com/arvidn/libtorrent.git | `c1ec6e3fcac6d7164baa3c543cecfa244236dd23` |
| asio-gnutls | https://github.com/paullouisageneau/boost-asio-gnutls.git | `a57d4d36923c5fafa9698e14be16b8bc2913700a` |
| try_signal | https://github.com/paullouisageneau/try_signal.git | `105cce59972f925a33aa6b1c3109e4cd3caf583d` |
| libsimulator | https://github.com/arvidn/libsimulator.git | `af20bd829482465a5fbe682fe4e7caa5de5f6b89` |

This is a vendored source snapshot, not a Git submodule. Native rebuilding is
separate from the three standard Gradle application builds. The original local
repositories remain under beam_torrent/ for further development.
