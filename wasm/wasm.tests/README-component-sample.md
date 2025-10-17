Wasm Component Model – quick sample

Prerequisites

- Install wasm-tools (https://github.com/bytecodealliance/wasm-tools).
- Build a Kotlin Wasm module (wasm-js or wasm-wasi). The stdlib now exports `canonical_abi_realloc` and `canonical_abi_post_return` that component adapters use.

Wrap a core Wasm module into a component

1) Produce a `.wasm` file (for example, `build/wasm/main/productionExecutable/module.wasm`).

2) (Optional but recommended) Validate WIT and embed it in your core Wasm:

   ./gradlew :wasm:wasm.tests:validateWit -PwitPath=/abs/path/to/wit

   ./gradlew :wasm:wasm.tests:embedWitIntoCore \
     -PwasmInput=/abs/path/to/module.wasm \
     -PwitPath=/abs/path/to/wit \
     -PwasmOut=/abs/path/to/module.with-wit.wasm

3) Run the Gradle task to assemble a component (adjust paths):

   ./gradlew :wasm:wasm.tests:assembleWasmComponent \
     -PwasmInput=/absolute/path/to/module.with-wit.wasm \
     -PcomponentOut=/absolute/path/to/module.component.wasm

   Optional properties:
   - `-Pwasm.tools.path=/path/to/wasm-tools` (defaults to `wasm-tools` on PATH)
   - `-Pcomponent.realloc.symbol=canonical_abi_realloc`
   - `-Pcomponent.postreturn.symbol=canonical_abi_post_return`

4) Validate the component:

   ./gradlew :wasm:wasm.tests:validateWasmComponent \
     -PcomponentIn=/absolute/path/to/module.component.wasm

5) Inspect the component’s WIT:

   ./gradlew :wasm:wasm.tests:printComponentWit \
     -PcomponentIn=/absolute/path/to/module.component.wasm

Notes

- You can also call `wasm-tools component new --realloc=... --post-return=...` directly.
- WASI Preview 2 adapters and world selection can be added with `--adapt` and related wasm-tools commands.
