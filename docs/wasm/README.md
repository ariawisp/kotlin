# Kotlin / Wasm Component Model Plan

 This single document replaces the previous scattering of notes
 (`ComponentDSL.md`, `Stage3Lowering.md`, `ResourceLifecyclePlan.md`,
 `RuntimeDispatch.md`, `TypedMarshallingPlan.md`). It tracks the current state,
open work, and forward roadmap for bringing this fork to WASI
preview‑2 parity.

> **Note:** The `wasmWasi` target builds the Preview‑2 component model by default, publishing the
> `kotlin-stdlib-wasm-wasi` artifact as the baseline for component-enabled WASI Kotlin.

### Binding and Runtime Evolution Roadmap

| Stage | Goal | Tasks (T#.#.#) |
|-------|------|----------------|
| 1 | Implement plugin-driven `klib` generation | T1.1–T1.5 (✅ complete) |
| 2 | Harness bring-up & Wasmtime execution | T2.1–T2.3 |
| 3 | Native component assembler in Kotlin | T3.1–T3.6 |
| 4 | Typed marshalling & async/stream guard rails | T4.1–T4.4 |
| 5 | Multi-runtime parity (WASI/native) smoke tests | T5.1–T5.4 |
| 6 | Tooling & documentation refresh | T6.1–T6.3 |

**Stage 1 – Implement plugin `klib` codegen** (✅ complete)

T1.1  Extract reusable binding generation logic inside the compiler plugin (shared module).

T1.2  Expose a task-friendly API that accepts WIT schemas and produces a WASM-ready `klib` (metadata
      + bitcode) without writing `.kt` sources.

T1.3  Add Gradle task type `WitCodegenTask` that invokes the new entry point and publishes the
      resulting `klib` into Gradle configurations.

T1.4  Update `libraries/stdlib` to depend on that `klib` and converge everything on the plugin-driven path.

T1.5  Replace the old `generateWasiPreview2Kotlin` flow with a `WitCodegenTask` that emits the
      Preview 2 bindings as a reusable klib.

Status: Complete. The new `WitCodegenTask` compiles WIT to a reusable `klib`, and `libraries/stdlib`
consumes that klib during wasm component builds. The offline compiler runs in an isolated classloader
with an explicit `-Xplugin` jar and `-libraries` set.

**Stage 2 – Harness bring-up and Wasmtime execution**

T2.1  Exercise the generated Preview 2 `klib` via an end-to-end harness that consumes the official WASI
      schemas and runs under Wasmtime.

T2.2  Keep the harness pointed at the synced upstream definitions.

T2.3  Add documentation and build/CI hooks that track the downloaded WASI schemas and surface drift.

**Stage 3 – Native component assembler (Preview 2/3 first-class support)**

T3.1  Extract the minimal component-assembler requirements from `wasm-tools component new` (header layout,
      canonical ABI metadata, WIT embedding, recursion groups) and design Kotlin-native data structures
      mirroring the WASI component spec.

T3.2  Port the encoder logic into Kotlin:
      - Reimplement wasm-tools’ component writer in terms of Kotlin IR objects (`WasmModule`, `WasmTypeDeclaration`, etc.).
      - Build a conformance suite that compares Kotlin output byte-for-byte against wasm-tools for the stdlib,
        sample worlds, and fuzzed bindings.

T3.3  Teach the backend to select the appropriate sink:
      - When targeting `wasmWasi` Preview 2/3, emit a `.component.wasm` directly from the Kotlin encoder.
      - When targeting `wasmJs`, continue emitting the existing core module + JS wrappers without change.
      - Embed canonical ABI shims/runtime helpers within the new serializer rather than via an external tool.

T3.4  Thread WIT packages through the compiler so the component encoder embeds the same schemas the generator consumed.
      Fail fast if the synced upstream bundle is missing or stale; do not call `wasm-tools component embed`.

T3.5  Retain wasm-tools/Wasmtime as validation-only dependencies. CI should run `wasm-tools validate` and Wasmtime
      smoke tests on the compiler-produced components, but the build graph must no longer rely on the CLI to finish binaries.

T3.6  Update documentation, samples, and release guidance to describe the new single-step component output and the lack of
      Preview 1 compatibility shims.

**Stage 3 guardrails.** Until Stage 3 is feature-complete we keep the current Stage 2 pipeline as the
default. New component-assembler code paths must be gated behind an explicit opt-in flag (Gradle
property or CLI argument) so the harness keeps relying on `WitCodegenTask` + `wasm-tools component
new`. The Kotlin encoder must fail closed (missing WIT bundle, unsupported features) and fall back to
the existing wasm-tools-based assembler with a warning. Retain wasm-tools and Wasmtime as validation
dependencies even once the Kotlin encoder lands; only flip the default after the Wasmtime harness and
conformance suite agree on byte-for-byte outputs across the full Preview 2 bundle.

---

## 1. Current Snapshot (2025‑02)

### Runtime and Binding Strategy

1.1  **Immediate direction**. We rely on the compiler plugin’s offline `klib` generation path for Preview 2 bindings.

1.2  **Runtime layering**. The plugin will emit the WASI Preview 2 bindings directly as a `klib`;
     the hand-written runtime (`ComponentRuntime`, handle managers, async helpers) remains layered
     on top.

1.3  **Parity**. We add integration tests and CI guards to ensure the plugin-emitted `klib`
     continues to match the upstream spec.

1.4  **Harness reality check**. The JVM E2E harness now loads the upstream WASI WIT packages from
     `libraries/stdlib/wasm/wasi/wit-upstream` to validate schema ingestion before wiring Wasmtime.

1.5  **Wasmtime execution plan**. Promote the harness from schema-only validation to a full
     Preview 2 round-trip: generate bindings from the synced schemas, compile a Kotlin component that
     targets an official world (e.g. `wasi:random/imports`), and run it under Wasmtime via the Gradle
     `Wasmtime*Run` helpers.

- **Stage 1 – Pipeline & DSL**: lock the wasm component compiler pipeline, ship the
  Gradle DSL, and ensure basic component assembly tooling works. ✅ complete.
- **Stage 2 – Resource Lifecycle**: wire resource constructors, handle managers,
  and runtime registration so host/guest resource semantics align with the Preview 2 spec.
  - ✅ `ResourceHandleManager` and `ComponentRuntime.registerResourceFactory`.
  - ✅ `WitFirDeclarationGenerator` emits companion constructor helpers.
  - ✅ `WitIrDriverRegistrationLowering` registers factories + constructor lambdas.
  - ✅ Exported constructor stubs now delegate to helpers and return managed handles.
  - ✅ Borrowed-handle constructors emit diagnostics until support lands.
  - ✅ JVM tests cover handle registration (`ResourceFactoryRegistrationTest`).
- **Stage 4 – Typed Marshalling & Async/Streams**: replace `Any?` with generated types,
  add marshalling helpers, and thread async/stream metadata with guard rails. (next)
- **Stage 5 – Multi-runtime Parity**: implement wasm/native `ComponentRuntime` shims and
  cross-runtime smoke tests.
- **Stage 6 – Tooling & Documentation**: build an end-to-end “compile WIT → run component”
  harness, refresh published docs, and ship sample projects.

---

## 2. Gradle DSL Quick Reference

```
kotlin {
    wasmWasi {
        component {
            name.convention(project.name)
            witDir.set(layout.projectDirectory.dir("src/main/wit"))
            world.set("my:pkg/world")
            importMemory.convention(false)
        }
    }
}

> Preview‑2 runtimes such as Wasmtime run the generated component natively, so no canonical adapters
> or Preview‑1 compatibility flags are required.
```

Enabling the DSL:
1. Turns on the component-model compiler pipeline.
2. Registers helper tasks backed by `wasm-tools`
   (`AssembleWasmComponent`, `ValidateWasmComponent`,
   `PrintComponentWit`, etc.).
3. Exposes task types under `org.jetbrains.kotlin.gradle.targets.wasm.component.*`
   should consumers need custom wiring.

### Sample Component and Wasmtime Run (Stage 2 harness)

This repo contains a minimal sample module with a wasmWasi target and the component-model DSL enabled:

- Project: `:wit:component-sample`
- Source: prints random values generated via `kotlin.random.Random` (backed by the Preview‑2 `wasi:random` bindings).
- Tasks:
  - `:wit:component-sample:assemblePreview2Component` → wraps the production wasm into a `.component.wasm` using `wasm-tools`.
  - `:wit:component-sample:validatePreview2Component` → validates the produced component.
  - `:wit:component-sample:printPreview2ComponentWit` → pretty-prints WIT for the produced component.
  - `:wit:component-sample:wasmWasiWasmtimeProductionRun` → runs the production core wasm under Wasmtime.
  - `:wit:component-sample:runPreview2ComponentViaWasmtime` → runs the `.component.wasm` via `wasmtime component run` and stores logs in `build/runLogs/preview2-wasmtime.log`.
  - `:wit:component-sample:runCoreWasmViaWasmtime` → convenience task to run the core wasm via Wasmtime with required feature flags (gc, reference-types, multi-memory, bulk-memory, multi-value, simd, exceptions, function-references). Useful while the component wrapper is stabilizing.
  - Root task `stage2Preview2Check` (new) depends on the Wasmtime run and snapshot verification so CI has a single Stage‑2 entry point.

The Gradle build resolves the canonical `wasi:cli/command` world from the metadata dump produced by
`:wit:e2e-harness-jvm:dumpPreview2Metadata` (falling back to the constant world id if the dump is
missing). This keeps the sample aligned with whatever version of the official Preview‑2 bundle is
synced under `libraries/stdlib/wasm/wasi/wit-upstream/`.

Notes:
- Wasmtime install is automated internally (gated by `kotlin.internal.enableWasmtimeRunner=true` in `gradle.properties`).
- `wasm-tools` must be available on `PATH` (or set `-Pwasm.tools.path=/path/to/wasm-tools`).
- The sample exercises host-provided WASI imports (random) to verify the end-to-end component harness.
- The wasm-tools CLI used here does not rely on legacy `--realloc/--post-return` flags. The component is assembled via `wasm-tools component new` with modern defaults; no `--realloc` is passed. If your host requires it, pass `--realloc-via-memory-grow` (the sample assembly task does).

### Local Dev Loop (No Publish)

This repo supports fast iteration without publishing to `mavenLocal`:

1) Rebuild the compiler plugin jar

```
./gradlew :wit:compiler-plugin:jar -x test
```

2) Build the runtime wasmWasi `.klib` locally

The runtime `.klib` is required for IR glue. A helper task compiles the in-repo sources and copies
the artifact into a stable location:

```
./gradlew :wit:runtime:syncWasmRuntimeKlib
```

3) Generate the WASI Preview 2 bindings `klib`

```
./gradlew :kotlin-stdlib:generateWasiPreview2Klib --rerun-tasks
```

Under the hood:
- `:kotlin-stdlib:generateWasiPreview2Klib` wires `-Xplugin` to the rebuilt plugin jar and passes
  `-libraries` including:
  - `kotlin-stdlib-wasm-wasi` and `kotlin-stdlib-wasm-js` (for builtins)
  - the locally built runtime `.klib` from `wit/runtime/build/klib`
- The isolated compiler resolves the plugin and emits `kotlin-wasm-wasi-preview2.klib` into
  `libraries/stdlib/build/wit-klibs/wasi-preview2`.

If the runtime `.klib` is missing, the IR phase fails fast with a clear message so the wiring stays correct.

4) Assemble and run the sample

```
./gradlew :wit:component-sample:assemblePreview2Component \
           :wit:component-sample:validatePreview2Component \
           :wit:component-sample:wasmWasiWasmtimeProductionRun \
           :wit:component-sample:runPreview2ComponentViaWasmtime
```

Root-level helper: `./gradlew stage2Preview2Check` runs the Wasmtime smoke test and the symbol
snapshot verification, and `check` now depends on it so CI sees Stage‑2 regressions immediately.

### Publishing / Consuming this Fork

The fork’s default version lives in `gradle.properties` (`defaultSnapshotVersion`).

To publish locally and consume from another build:

1. Run `./gradlew publish`. Artifacts land in `build/repo`.
2. In the consumer project set, for example, in `gradle.properties`:

```
bootstrap.local=true
bootstrap.local.version=2.3.0-wit.1
bootstrap.local.path=/absolute/path/to/kotlin/build/repo
```

Alternatively wire repositories manually:

```kotlin
pluginManagement {
    repositories {
        maven(url = uri("../kotlin/build/repo"))
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin")) {
                useVersion("2.3.0-wit.1")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven(url = uri("../kotlin/build/repo"))
        mavenCentral()
        google()
    }
}
```

Run `./gradlew :help --scan` or `buildEnvironment` in the consumer to verify
the custom version resolves.

---

### Native Component Assembler Plan (Stage 3 methodology)

To remove `wasm-tools` from the critical path, Stage 3 gives `kotlinc` a built-in component encoder.
The work is split into three tracks:

1. **Encoder port**
   - Recreate wasm-tools’ component writer in Kotlin (component headers, recursion groups, canonical ABI metadata,
     embedded WIT packages).
   - Translate the Rust implementation or implement it idiomatically, ensuring leb128 encoding and section ordering
     match the spec exactly.
   - Build a conformance harness that compares Kotlin-produced components against wasm-tools for stdlib bindings,
     sample worlds, and fuzzed schemas.

2. **Backend integration**
   - Extend `WasmCompiledModuleFragment` so the `wasmWasi` pipeline emits `.component.wasm` directly.
   - Keep the `wasmJs` pipeline unchanged (core wasm + JS wrappers).
   - Embed the synchronized WIT bundle during component emission and fail fast if it is missing or stale.

3. **Validation & tooling**
   - Continue running `wasm-tools validate` and Wasmtime smoke tests in CI, but remove the CLI from the build graph.
   - Update docs/samples/release notes to emphasise that Preview 2/3 components are produced in a single compiler step
     and that no Preview 1 compatibility shims remain.

Until Stage 3 lands, `wasm-tools component new` stays in the build; afterwards it is a validation-only dependency.

#### wasm-tools reference map

We lean on the `~/zaedalus/wasm-tools` checkout while porting logic into Kotlin:

- **Component encoder flow**
  - `crates/wit-component/src/encoding.rs:1` — end-to-end component assembly (adapter GC, canonical ABI options, `ComponentEncoder`).
  - `crates/wit-component/src/encoding/world.rs:1` — resolves imports/exports, validates adapters, and stages lowering metadata.
  - `crates/wit-component/src/validation.rs:1` — mirrors how core module imports/exports map back to WIT; use it to align Kotlin validation.
- **Type + metadata encoding**
  - `crates/wit-component/src/encoding/types.rs:1` — maps WIT type IDs onto component types and resources.
  - `crates/wit-component/src/encoding/wit.rs:1` — serialises full WIT packages/worlds into component sections for embedding.
  - `crates/wit-component/src/metadata.rs:1` — documents the `wit-component-encoding` custom section (versioning, string encodings) that Stage 3 must reproduce.
- **Binary writer & CLI wiring**
  - `crates/wasm-encoder/src/component.rs:101` — low-level section writer we need to mirror (header bytes, section IDs, ordering).
  - `src/bin/wasm-tools/component.rs:70` — the `component new` subcommand (adapters, `--realloc-via-memory-grow`, validation toggles). Keep Kotlin’s feature flag compatible.
- **Test fixtures**
  - `tests/cli` (for example `tests/cli/help-component-new.wat.stdout:13`) — golden component outputs/failures used for byte-for-byte regression tests.

Use these references for feature parity, while keeping Stage 2 automation pointed at the existing CLI until the Kotlin encoder passes all conformance checks.

#### Stage 3 decision log

- **Scope baseline**
  - Acceptance suite covers the entire synced WASI Preview 2 bundle downloaded for Stage 2. Kotlin encoder parity and Wasmtime runs must pass for every upstream world before the flag can default to on.
- **Feature flag & fallback**
  - Gradle property: `kotlin.wasm.useNativeComponentEncoder=true` (propagated to CLI as `-Xwasm-native-component`). Default `false`.
  - When disabled or on encoder failure, fall back to `wasm-tools component new` automatically, emitting a warning but keeping Stage 2 flow intact.
- **Encoder architecture**
  - New package `org.jetbrains.kotlin.wasm.component.encoder` built as layered primitives: `ComponentWriter`, `TypeEncoder`, `WorldPlanner`, `MetadataEmitter`, mirroring wasm-tools but using Kotlin-friendly builders.
  - Internal byte writer matches `wasm-encoder` section ordering and leb128 encoding so other backends can share it long term.
- **Module placement & ownership**
  - Introduce Gradle submodule `:wasm-component-encoder` under `compiler/ir/backend.wasm`. Ownership sits with the Wasm backend team; public access only via backend APIs.
- **Validation strategy**
  - Conformance harness generates components with both Kotlin and wasm-tools paths, asserts byte-for-byte equality, and captures diffs when mismatched.
  - CI runs `wasm-tools validate` and Wasmtime smoke tests for every Preview 2 world on both pipelines. Stage 3 stays non-blocking until the suite is stable, then becomes required.
- **Adapter & metadata handling**
  - First release must support adapters, canonical ABI shims, and the `wit-component-encoding` custom section exactly as wasm-tools does. Missing features trigger fallback rather than partial output.
- **Documentation & testing cadence**
  - `docs/wasm/README.md` remains the central spec; update it alongside each milestone (flag plumbing, encoder integration, validation harness).
  - Every encoder subsystem (writer, type encoding, world planning, metadata) ships with unit tests plus coverage in the conformance harness before merge.

---

## 3. Runtime & Resource Lifecycle

### Implemented

- `ComponentRuntime.registerResourceFactory(type, factory, constructorCb)`
  stores a constructor helper alongside the factory.
- `ComponentRegistry` tracks drivers, factories, and helper callbacks.
- `ResourceHandleManager` allocates `OwnHandle`/`BorrowHandle`; JVM
  implementation closes adapters on release.
- Generated modules eagerly register their world drivers through
  `GeneratedModuleRegistry.registerGeneratedWorlds`, and runtimes hook
  them in automatically during start-up.
- FIR annotations now allow both constructor functions and helper metadata
  to carry `@WitConstructor`.

Runtime Klib Requirement

- The IR generation phase requires `org.jetbrains.kotlin.wit.runtime` to be available as a wasmWasi
  `.klib` in the offline compiler’s `-libraries`. This is enforced in the plugin.
- For local development, build the runtime `.klib` via `:wit:runtime:syncWasmRuntimeKlib` and re-run
  `:kotlin-stdlib:generateWasiPreview2Klib`.

### Stage 2 Work Breakdown

The outstanding Stage 2 work breaks down into the following milestones:

**T2.1 – Harness Metadata Integration** (✅ complete)
1.1 ✅ `Preview2MetadataIntrospector.loadPreview2Metadata` is the single entry point used by tests,
    Gradle tasks, and the sample.
1.2 ✅ `Preview2JvmE2eTest` exercises the helper to guard schema ingestion.
1.3 ✅ `:wit:e2e-harness-jvm:dumpPreview2Metadata` now emits `build/preview2/preview2-metadata.json`
    (the task is configuration-cache safe).

**T2.2 – Sample Component Project** (✅ wiring, ⚙️ runtime host bindings still pending)
2.1 ✅ `:wit:component-sample` contains the Wasmtime harness and depends on the Preview‑2 runtime.
2.2 ✅ The Gradle build resolves the target world by reading the metadata dump (falling back to the
    constant `wasi:cli/command` if the dump is missing or empty).
2.3 ⚙️ Still TODO: bring up a `DefaultComponentRuntime` host with explicit handlers for `wasi:random`
    and friends once the runtime APIs are ready. The Wasmtime path covers the immediate smoke test.

**T2.3 – Wasmtime Execution Wiring** (✅ initial guardrails)
3.1 ✅ The sample reuses the shared Wasmtime toolchain (setup + feature flags).
3.2 ✅ `assemblePreview2Component`, `printPreview2ComponentWit`, and
    `runPreview2ComponentViaWasmtime` are CC‑safe tasks that produce artefacts and logs under
    `build/compileSync` and `build/runLogs`.
3.3 ⚙️ Basic assertions ensure a clean exit and empty stderr. Once component I/O is plumbed, tighten
    the check to require the random-value banner in stdout.

**T2.4 – Symbol Snapshot & CI Guard** (✅ complete)
4.1 ✅ `:wit:e2e-harness-jvm:generatePreview2SymbolSnapshot` copies the metadata dump into
    `docs/wasm/snapshots/preview2-metadata.json`.
4.2 ✅ `:wit:e2e-harness-jvm:verifyPreview2SymbolSnapshot` compares the dump against the committed
    snapshot (and is configuration-cache safe).
4.3 ✅ The metadata dump now enumerates every synced Preview‑2 world with binding/resource details,
    so the snapshot is meaningful for drift detection instead of a placeholder.
4.4 ✅ Root task `stage2Preview2Check` depends on the Wasmtime run and the snapshot verification
    so CI catches regressions in a single entry point.

**T2.5 – Documentation & Cleanup** (✅ in progress)
5.1 ✅ README now documents the metadata task flow, Stage‑2 check, and sample wiring.
5.2 ⚙️ Continue trimming temporary diagnostic logging once runtime plumbing stabilises.
5.3 ✅ `:wit:e2e-harness-jvm:test` remains part of the verification suite.

---

## 4. IR / FIR Lowering Roadmap

| Step | Status | Notes |
|------|--------|-------|
| Binding field initialisation | ✅ | Uses `pendingBindingDelegate`. |
| Binding function bodies | ✅ | Calls `ComponentRuntime.dispatchBinding`. |
| Constructor helpers | ✅ | Companion functions returning `OwnHandle<Resource>`. |
| Exported constructor stubs | ✅ | Delegates to helper and returns managed handle. |
| Register resources | ✅ | Generates factory + helper lambda. |
| Borrowed constructors | ✅ | Emits placeholder error until runtime support. |

The `WitIrPlan` already carries constructor metadata; widening it later to
include full type shapes will support typed marshalling.

---

## 5. Stage 4 – Typed Marshalling & Async/Streams

**Goals**
- Replace `Any?` arguments/results with generated Kotlin types that match WIT schemas.
- Introduce a `ValueMarshaller` surface analogous to Rust’s runtime so bindings can encode/decode values deterministically.
- Carry async/stream metadata through FIR→IR so unsupported features surface clear diagnostics.
- Model borrowing via wrapper types (owning projection + borrow view) even before we have full lifetime tracking.

**Tasks (T4.*)**
- **T4.1** Thread resolved WIT type shapes into `WitIrPlan` so IR lowering can see concrete signatures.
- **T4.2** Extend the runtime with marshalling helpers for scalars, lists, records, variants, and resources.
- **T4.3** Update FIR/IR lowering to emit conversions instead of varargs of `Any?`, wiring the new helpers.
- **T4.4** Add conformance tests that exercise typed bindings (imports, exports, constructors) and ensure async/stream guard rails remain.

## 6. Stage 5 – Runtime Dispatch Evolution

We still rely on `ComponentRuntime.dispatchBinding` to reach host callbacks.

**Tasks (T5.*)**
- **T5.1** Introduce a `BindingDispatcher` abstraction with `dispatchImport`/`dispatchExport` hooks so hosts can customise transport.
- **T5.2** Update IR lowering to route calls through a unified `runtime.call(delegate, args)` helper that delegates to the dispatcher.
- **T5.3** Provide JVM prototypes (and tests) that exercise the dispatcher, logging metadata and setting expectations for other hosts.
- **T5.4** Stand up WASI/native runtime shims plus cross-runtime smoke tests to verify bindings behave consistently outside the JVM harness.

This work naturally ties into Stage 4 so typed payloads pass cleanly through the dispatcher.

---

## 7. Stage Status Overview

| Stage | Focus | Status |
|-------|-------|--------|
| 2 | Harness bring-up & Wasmtime execution | Metadata + CI harness wired; host runtime bindings pending (see §3). |
| 3 | Native component assembler in Kotlin | Not started. |
| 4 | Typed marshalling & async/stream guard rails | Not started. |
| 5 | Runtime dispatch evolution & multi-runtime parity | Not started. |
| 6 | Tooling & documentation refresh | Not started. |

---

## 8. Task Tracker (live)

### Must Do (Stage 2 completion)
- [x] Exported constructor stubs call helper and return managed handle.
- [x] Diagnostic / stub for borrowed constructors.
- [x] IR text test covering `registerResourceFactory` lowering.

### Next
- [ ] Promote the JVM harness to run a generated component under Wasmtime using the upstream schemas.
- [ ] Document the schema sync workflow and decide on a CI guard for upstream drifts.
- [ ] (Deferred) Revisit Stage 4/Stage 5 work once the Wasmtime harness lands.

Keep this checklist updated as tasks land so we always have an accurate
snapshot of progress.
