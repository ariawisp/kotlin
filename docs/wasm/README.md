# Kotlin / Wasm Component Model Plan

This single document replaces the previous scattering of notes
(`ComponentDSL.md`, `Stage3Lowering.md`, `ResourceLifecyclePlan.md`,
`RuntimeDispatch.md`, `TypedMarshallingPlan.md`). It tracks the current state,
open work, and forward roadmap for bringing this fork to WASI
preview‑2 parity.

---

## 1. Current Snapshot (2025‑02)

- **Phase 1 – Pipeline & DSL**: lock the wasm component compiler pipeline, ship the
  Gradle DSL, and ensure basic component assembly tooling works. ✅ complete.
- **Phase 2 – Resource Lifecycle**: wire resource constructors, handle managers,
  and runtime registration so host/guest resource semantics match wit‑bindgen.
  - ✅ `ResourceHandleManager` and `ComponentRuntime.registerResourceFactory`.
  - ✅ `WitFirDeclarationGenerator` emits companion constructor helpers.
  - ✅ `WitIrDriverRegistrationLowering` registers factories + constructor lambdas.
  - ⚠️ Exported constructor stubs still return `Any?`; they must call the helper.
  - ⚠️ Borrowed-handle constructors remain unimplemented; emit diagnostics.
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

1. **Constructor stubs → helpers**  
   Generate concrete bodies for exported `__witExportFn__constructor_*` that
   call the helper, run the binding via `dispatchBinding`, and return the
   managed handle.

2. **Borrowed-handle guard**  
   Detect constructors that produce borrowed handles; emit a targeted
  `UnsupportedOperationException` (or diagnostic) until borrowing semantics
   land.

3. **IR regression**  
   Add an IR text test proving driver registration uses
  `ComponentRuntime.registerResourceFactory` with the generated lambda.

---

## 4. IR / FIR Lowering Roadmap

| Step | Status | Notes |
|------|--------|-------|
| Binding field initialisation | ✅ | Uses `pendingBindingDelegate`. |
| Binding function bodies | ✅ | Calls `ComponentRuntime.dispatchBinding`. |
| Constructor helpers | ✅ | Companion functions returning `OwnHandle<Resource>`. |
| Exported constructor stubs | ⚠️ | Must delegate to helper instead of `Any?`. |
| Register resources | ✅ | Generates factory + helper lambda. |
| Borrowed constructors | ⚠️ | Emit placeholder error until runtime support. |

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
- [ ] Exported constructor stubs call helper and return managed handle.
- [ ] Diagnostic / stub for borrowed constructors.
- [ ] IR text test covering `registerResourceFactory` lowering.

### Next
- [ ] Kick off typed marshalling plan (type shapes, runtime helpers).
- [ ] Draft dispatcher refactor once marshalling is underway.
- [ ] Outline wasm/native runtime shims for Phase 4.

Keep this checklist updated as tasks land so we always have an accurate
snapshot of progress.
