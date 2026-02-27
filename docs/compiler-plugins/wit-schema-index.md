# WIT Schema Index Runtime Metadata

The Kotlin WIT compiler plugin now materialises a structured runtime model while the schemas are being indexed. The new `WitRuntimeSchema` surface provides:

- Stable package identifiers (`foo:bar@1.0.0`) mapped to the WIT source roots that produced them.
- Interface metadata: stability annotations, resource declarations, and member function signatures.
- World bindings: imports, exports, and resource constructors resolved to their originating interfaces or resource types.
- Source provenance: the directory or file that was scanned, the resolved include roots, and the set of WIT files that contributed to the package.

This metadata flows forward into the FIR and IR pipelines instead of re-parsing the JSON blobs emitted by the wasm-tools parser.

## WIT Sources vs. Precompiled JSON

Precompiled JSON (`--json`) is retained purely for wasm-tools parity testing. When the plugin runs in non-debug mode the JSON payloads are ignored and a warning is emitted. JSON-sourced packages never override packages loaded from real WIT roots.

Enable `--debug` alongside `--json` while running parity tests or local comparisons. In all other situations prefer the `--root` + `--include` options so the Kotlin runtime model matches the original WIT sources.

## Feature Gating and Stability

Stability markers from the schema metadata (`stable`, `unstable`, feature-gated) are preserved on interfaces and worlds. When a feature is disabled the corresponding interfaces/worlds are filtered out of the runtime model, keeping FIR/IR generation in sync with the wasm-tools behaviour.

## Runtime Consumers

Downstream phases should read the runtime model via `WitSchemaIndex.runtimeSchema`. This ensures consistent naming, feature filtering, and resource binding semantics between FIR, IR, and the eventual runtime module.
