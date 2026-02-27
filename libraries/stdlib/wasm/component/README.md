# Component-Only WASM Sources (Temporary)

This directory contains the hand-written `actual` implementations that let the
component-mode stdlib build without importing any WASI hosts. They are a
stop-gap until the Preview‑2 runtime generated from the official WASI WIT
packages lands (see `docs/wasm/README.md` Stage 2/3).

Once the WIT-driven runtime supplies these APIs (I/O, randomness, time,
UUIDs, exported-function hooks), this directory should be deleted and the
generated klib should provide the functionality instead.
