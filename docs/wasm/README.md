# Kotlin / Wasm Component Model Plan

 This single document replaces the previous scattering of notes
 (`ComponentDSL.md`, `Stage3Lowering.md`, `ResourceLifecyclePlan.md`,
 `RuntimeDispatch.md`, `TypedMarshallingPlan.md`). It tracks the current state,
 open work, and forward roadmap for bringing this fork to WASI
 preview‑2 parity.

### Binding and Runtime Evolution Roadmap

| Stage | Goal | Tasks (T#.#.#) |
|-------|------|----------------|
| 1 | Implement plugin-driven `klib` generation and remove `wit-bindgen` | T1.1–T1.5 |
| 2 | Harden parity/testing and update docs | T2.1–T2.3 |

**Stage 1 – Implement plugin `klib` codegen and drop `wit-bindgen`**

T1.1  Extract reusable binding generation logic inside the compiler plugin (shared module).

T1.2  Expose a task-friendly API that accepts WIT schemas and produces a WASM-ready `klib` (metadata
      + bitcode) without writing `.kt` sources.

T1.3  Add Gradle task type `WitCodegenTask` that invokes the new entry point and publishes the
      resulting `klib` into Gradle configurations.

T1.4  Update `libraries/stdlib` to depend on that `klib` and remove the `wit-bindgen` CLI step.

T1.5  Replace the old `generateWasiPreview2Kotlin` flow with a `WitCodegenTask` that emits the
      Preview 2 bindings as a reusable klib.

**Stage 2 – Parity, testing, and documentation**

T2.1  Create integration/parity tests comparing the plugin-emitted `klib` to the previous CLI output
      (until confident).

T2.2  Add a regression guard in CI ensuring the `klib` stays in sync with upstream WASI specs.

T2.3  Update documentation, changelog, and migration notes to reflect the new binding flow.

---

## 1. Current Snapshot (2025‑02)

### Runtime and Binding Strategy

1.1  **Immediate direction**. We are dropping the pinned `wit-bindgen` CLI and replacing it with a
     compiler-plugin-driven `klib` generation path.

1.2  **Runtime layering**. The plugin will emit the WASI Preview 2 bindings directly as a `klib`;
     the hand-written runtime (`ComponentRuntime`, handle managers, async helpers) remains layered
     on top.

1.3  **Parity**. We add integration tests and CI guards to ensure the plugin-emitted `klib`
     continues to match the upstream spec.

- **Phase 1 – Pipeline & DSL**: lock the wasm component compiler pipeline, ship the
  Gradle DSL, and ensure basic component assembly tooling works. ✅ complete.
- **Phase 2 – Resource Lifecycle**: wire resource constructors, handle managers,
  and runtime registration so host/guest resource semantics match wit‑bindgen.
  - ✅ `ResourceHandleManager` and `ComponentRuntime.registerResourceFactory`.
  - ✅ `WitFirDeclarationGenerator` emits companion constructor helpers.
  - ✅ `WitIrDriverRegistrationLowering` registers factories + constructor lambdas.
  - ✅ Exported constructor stubs now delegate to helpers and return managed handles.
  - ✅ Borrowed-handle constructors emit diagnostics until support lands.
  - ✅ JVM tests cover handle registration (`ResourceFactoryRegistrationTest`).
- **Phase 3 – Typed Marshalling & Async/Streams**: replace `Any?` with generated types,
  add marshalling helpers, and thread async/stream metadata with guard rails. (next)
- **Phase 4 – Multi-runtime Parity**: implement wasm/native `ComponentRuntime` shims and
  cross-runtime smoke tests.
- **Phase 5 – Tooling & Integration**: build an end-to-end “compile WIT → run component”
  harness and sample projects.
- **Phase 6 – Documentation & Samples**: refresh published docs and guidance.

---

## 2. Gradle DSL Quick Reference

```
kotlin {
    wasmWasi {
        component {
            enabled.set(true)
            name.convention(project.name)
            witDir.set(layout.projectDirectory.dir("src/main/wit"))
            world.set("my:pkg/world")
            adapters.add("wasi_snapshot_preview2")
            importMemory.convention(false)
        }
    }
}
```

Enabling the DSL:
1. Turns on the component-model compiler pipeline.
2. Registers helper tasks backed by `wasm-tools`
   (`AssembleWasmComponent`, `ValidateWasmComponent`,
   `PrintComponentWit`, etc.).
3. Exposes task types under `org.jetbrains.kotlin.gradle.targets.wasm.component.*`
   should consumers need custom wiring.

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

## 3. Runtime & Resource Lifecycle

### Implemented

- `ComponentRuntime.registerResourceFactory(type, factory, constructorCb)`
  stores a constructor helper alongside the factory.
- `ComponentRegistry` tracks drivers, factories, and helper callbacks.
- `ResourceHandleManager` allocates `OwnHandle`/`BorrowHandle`; JVM
  implementation closes adapters on release.
- FIR annotations now allow both constructor functions and helper metadata
  to carry `@WitConstructor`.

### Remaining Phase 2 Work

1. ~~Constructor stubs → helpers~~  
   Completed. Stubs now call the helper, dispatch the binding, and return the managed handle.

2. ~~Borrowed-handle guard~~  
   Completed. Borrowed constructors surface a dedicated diagnostic until runtime support arrives.

3. ~~IR regression~~  
   Completed. A lowering test now asserts `registerResourceFactory` is emitted with the helper lambda.

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

## 5. Typed Marshalling (Phase 3 Preview)

Goals once Phase 2 wraps:
- Replace `Any?` arguments/results with generated Kotlin types matching WIT.
- Introduce a `ValueMarshaller` surface analogous to Rust’s runtime.
- Carry async/stream metadata through FIR→IR so the runtime can gate execution.
- Model borrowing via wrapper types (owning projection + borrow view) even
  without lifetime support.
- Extend runtime dispatcher to understand typed payloads while keeping the
  current compatibility shim temporarily.

Current action items:
1. Thread resolved WIT type shapes into `WitIrPlan`.
2. Define runtime helpers for scalars, lists, records, variants, resources.
3. Update lowering to emit conversions instead of varargs of `Any?`.

---

## 6. Runtime Dispatch (Future Work)

We still rely on `ComponentRuntime.dispatchBinding` to reach host callbacks.
The intended evolution:

- Introduce `BindingDispatcher` with `dispatchImport` / `dispatchExport`.
- Have IR lowerings call `runtime.call(delegate, args)` so the dispatcher can
  choose the right transport (host/import vs wasm export).
- Provide a JVM prototype that logs metadata and throws until actual execution
  is wired.

This work naturally ties into the typed marshalling effort (Phase 3).

---

## 7. Remaining Phases Overview

| Phase | Focus | Status |
|-------|-------|--------|
| 2 | Resource lifecycle + constructor helpers | In progress (see §3). |
| 3 | Typed marshalling, async/stream guard rails | Not started. |
| 4 | Multi-runtime parity (wasm/native) + smoke tests | Not started. |
| 5 | End-to-end tooling (compile WIT → run component) | Not started. |
| 6 | Documentation & samples refresh | Not started. |

---

## 8. Task Tracker (live)

### Must Do (Phase 2 completion)
- [x] Exported constructor stubs call helper and return managed handle.
- [x] Diagnostic / stub for borrowed constructors.
- [x] IR text test covering `registerResourceFactory` lowering.

### Next
- [ ] Kick off typed marshalling plan (type shapes, runtime helpers).
- [ ] Draft dispatcher refactor once marshalling is underway.
- [ ] Outline wasm/native runtime shims for Phase 4.

Keep this checklist updated as tasks land so we always have an accurate
snapshot of progress.
