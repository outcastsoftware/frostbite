# Frostbite

Frostbite is a Java-first framework for writing IL2CPP Android mods with mixin-style declarations. Java describes which IL2CPP types and methods to target. A compile-time processor validates those declarations and emits a manifest. A native runtime resolves the IL2CPP metadata, installs Dobby hooks, and dispatches callbacks into Java through JNI.

> **Status:** working hook prototype. The Java-only sample mod was packaged separately, added to Subway Surfers 3.70.3, and run on an Android emulator. `CharacterMotor.UpdateMovement` logged at both HEAD and TAIL while the game continued. Field and method accessor bindings remain unimplemented; ABI support is intentionally limited.

**Start here:** [Build and use Frostbite](docs/GETTING_STARTED.md) covers mod creation, the combined desktop app, signing, APK installation, and the sample game. The rest of this README describes the API and runtime contract in more depth.

## Contents

- [How a mixin becomes a hook](#how-a-mixin-becomes-a-hook)
- [APK integration and startup](#apk-integration-and-startup)
- [Mod initializer and mixin registration](#mod-initializer-and-mixin-registration)
- [Mixin targets](#mixin-targets)
- [Method selectors and regex](#method-selectors-and-regex)
- [Field accessors](#field-accessors)
- [Method accessors](#method-accessors)
- [Injections](#injections)
- [Callback rules and native boundaries](#callback-rules-and-native-boundaries)
- [Errors, conflicts, and cleanup](#errors-conflicts-and-cleanup)
- [Build and Android compatibility](#build-and-android-compatibility)
- [Implementation plan](#implementation-plan)
- [What is implemented today](#what-is-implemented-today)

## How a mixin becomes a hook

Mod developers write Java only. Java callback bodies are not translated into C++; they remain Java and execute on ART. The processor reads annotations and writes structured metadata. Frostbite supplies the C++ bridge and ABI-specific Dobby trampolines; a mod project does not contain or compile C++.

For an injection, the intended flow is:

1. **Compile time:** the annotation processor verifies the mixin declaration, selector syntax, callback modifiers, and supported Java parameter/return types. It writes the target and callback metadata into a versioned manifest.
2. **Mod discovery:** the APK index names each separately packaged mod. Its descriptor names the initializer, and its generated hook manifest names the mixin callbacks. Frostbite does not scan every class in the APK or process.
3. **Initialization:** Frostbite loads each mod's DEX, calls its `ModInitializer.onLoad`, and registers the mixins listed in that mod's generated manifest.
4. **Runtime readiness:** the runtime waits until the IL2CPP domain and metadata are available.
5. **Resolution:** the ByNameModding adapter enumerates candidates for the declared assembly, namespace, and class. Frostbite canonicalizes their names and parameter types, then resolves each selector.
6. **Hook installation:** the resolved method pointer and its exact ABI description are passed to a generated or generic native trampoline, which Dobby installs and for which it stores the original function pointer.
7. **Dispatch:** at `HEAD` or `TAIL`, the trampoline attaches the calling thread to the JVM if necessary, marshals supported values, and invokes the registered Java callback on its registered mixin instance (or invokes a static callback).

An annotation processor cannot safely turn arbitrary Java code into a Dobby replacement. The native side must have a type-correct trampoline. The current native runtime supports instance targets with up to two declared word-sized parameters. Each parameter may be `int`, `boolean`, or an IL2CPP object reference. The receiver and object parameters are passed to Java as `Il2CppObject`; a null object parameter becomes Java `null`. Return types are `void`, `boolean`, `int`, `float`, or an object reference. Unsupported layouts, including floating-point parameters, 64-bit primitive parameters, and value types, are rejected before hook installation. The bridge currently has 32 hook slots.

## APK integration and startup

The target deployment uses one desktop app with two tabs and one in-game runtime:

1. **Install Frostbite tab:** selects a game APK, runtime ZIP, launch Activity, and output. Its backend patches the APK, rebuilds it, signs with APK Signature Schemes v1/v2/v3 using the bundled development key, and verifies the output.
2. **Add Mods tab:** selects a Frostbite-enabled APK, one or more mod APKs, and an output. Its backend validates/extracts mod payloads, updates the mod index, signs with v1/v2/v3 using the same bundled key, and verifies the output.
3. **Frostbite in-game runtime:** already embedded by the installer; at game startup it reads the mod index, loads each mod's DEX, calls its initializer, collects its mixins, and installs IL2CPP hooks.

The desktop Mod Loader does not execute mod code itself. It packages mod code into the target APK. The embedded Frostbite runtime loads and executes that code inside the game's process. This keeps APK editing out of the game process and keeps hook installation inside it. A mod menu can later be a mod built on top of this system; it is outside the current implementation scope.

### How separately added mods are discovered

Do not scan every class in the game's DEX and guess which classes are mods. A DEX contains classes, but it does not provide a reliable list of intended Frostbite mods, their entrypoints, versions, dependencies, or hook manifests. Each mod needs a descriptor alongside its DEX, and the APK patcher maintains an index of the mod packages it adds.

The expected APK layout is:

```text
assets/frostbite/mods/index.json
assets/frostbite/mods/subway-tools.frostbite
assets/frostbite/mods/another-mod.frostbite
lib/arm64-v8a/libfrostbite.so
classes.dex                          # includes FrostbiteBootstrap bridge
```

Each `.frostbite` is a ZIP/DEX-JAR package with one mod's code and metadata:

```text
mod.json
classes.dex
META-INF/frostbite/hooks.json
assets/...                           # optional mod data
```

A mod is distributed as a **`.frostbite`** package: a ZIP containing `mod.json`, a contiguous `classes.dex`, `classes2.dex`, … sequence, `META-INF/frostbite/hooks.json`, and any `arm64-v8a`/`armeabi-v7a` libraries — the mod payload only, no full APK. The `packageFrostbite` Gradle task produces it from a release mod APK. For backward compatibility the host tools also accept a legacy **mod APK** containing the same `classes.dex` sequence plus `META-INF/frostbite/{mod,hooks}.json`. Either way the **Mods** tab reads those entries and embeds them as a `.frostbite` asset; it does not install the mod or run its `Application`/`Activity` components. Compile Frostbite API/runtime dependencies as provided dependencies so their classes are not copied into the mod DEX.

`index.json` lists the packages explicitly:

```json
{
  "schemaVersion": 1,
  "mods": ["subway-tools.frostbite", "another-mod.frostbite"]
}
```

Each mod descriptor names its entrypoint and compatibility requirements:

```json
{
  "schemaVersion": 1,
  "id": "subway-tools",
  "version": "1.0.0",
  "apiVersion": 1,
  "entrypoint": "dev.example.SubwayToolsMod",
  "hooks": "META-INF/frostbite/hooks.json"
}
```

At startup, Frostbite reads `index.json`, validates each descriptor, extracts each package to the app's private code cache, and creates one `DexClassLoader` per mod with the game's class loader as its parent. It loads the named entrypoint with `Class.forName`, verifies that it implements `ModInitializer`, constructs it, and calls `onLoad`. This is explicit entrypoint discovery; Frostbite does not enumerate every class or depend on private Android class-loader internals. Parent-first loading lets mods share one Frostbite API/runtime copy. Mod DEX packages must not bundle duplicate Frostbite API classes, or Java class identity and JNI callback lookup can split across class loaders.

The mod project's build should generate the descriptor and hook manifest from its declared entrypoint and annotations. The APK packager adds each `.frostbite` as an independent asset and updates `index.json`. A DEX merely placed in an APK's assets is not automatically loaded by ART; Frostbite extracts it and loads it through that mod's `DexClassLoader`.

Mixin discovery happens at mod build time, not by searching DEX classes at runtime. The annotation processor sees every `@Mixin` in the mod's sources and writes its target and callback records to `hooks.json`. `@FrostbiteMod(id, version)` marks the one `ModInitializer` entrypoint; the processor writes its class name into `mod.json`. At runtime, Frostbite reads that descriptor and generated hook manifest, loads callback classes through that mod's class loader, and associates every manifest record with the correct mod. Thus adding another `.frostbite` package plus its index entry is sufficient for Frostbite to discover it.

The native library is packaged under the APK's ABI-specific native library directory (for example, `lib/arm64-v8a/libfrostbite.so`) and loaded by the bootstrap with `System.loadLibrary`. Only include ABIs for which Frostbite, ByNameModding, and Dobby were built. Keep the Java runtime and native library versions in the same Frostbite release so JNI registrations and manifest schemas agree.

The `MainActivity` patch should call the bootstrap after the original `onCreate` initialization has run. Conceptually, the inserted call is:

```java
@Override
protected void onCreate(Bundle state) {
    super.onCreate(state); // original game initialization
    FrostbiteBootstrap.start(this); // inserted by the APK patcher
}
```

Bootstrap must be idempotent because Activities can be recreated. It should retain process-scoped Frostbite state separately from the Activity and avoid holding the Activity longer than needed. The runtime can keep mod instances, mixin registrations, and native hooks alive across Activity recreation. UI attachment is a later mod concern.

Do not install IL2CPP hooks directly from `onCreate`. Startup has two readiness stages:

```text
MainActivity.onCreate
  -> FrostbiteBootstrap.start(activity)
       -> load libfrostbite.so
       -> read assets/frostbite/mods/index.json
       -> for each listed .frostbite:
            -> validate mod.json and API compatibility
            -> extract classes.dex and create a child ClassLoader
            -> read generated hooks.json and discover this mod's @Mixin declarations
            -> instantiate the declared ModInitializer
            -> call mod.onLoad(context)
       -> wait for IL2CPP metadata readiness
            -> resolve targets through ByNameModding
            -> install Dobby hooks
```

`JNI_OnLoad` should register JNI methods and retain the `JavaVM`; it should not assume IL2CPP is initialized. Hook installation should be triggered by the selected ByNameModding loading/readiness mechanism. Mod `onLoad` runs before hook installation so mods can register declarations while IL2CPP is still starting.

The APK patcher is a separate integration tool, not part of the Frostbite runtime library. It takes an original APK, the Frostbite runtime, and separately built mod packages; adds ABI libraries and mod assets; generates or updates `index.json`; inserts the bootstrap call into the existing launch Activity; rebuilds; signs the output with APK Signature Schemes v1, v2, and v3; and verifies those signatures. The desktop app bundles a development key for both steps. Since the private key is distributed with the app, it cannot identify a unique trusted publisher. Rebuilding and signing changes the package signature; games or services that require the original signature may reject the modified APK, and app updates may require reapplying the patch.

## Project boundaries and build artifacts

Keep the three deliverables separate:

| Project | Responsibility | Build output |
| --- | --- | --- |
| Frostbite core repository | API, annotation processor, Android bootstrap/runtime, mod discovery/loading, JNI runtime, ByNameModding adapter, Dobby hooks | Android runtime artifact plus `libfrostbite.so` for each supported ABI |
| Frostbite Installer repository | Host-side desktop GUI for APK integration | Desktop application; Frostbite-enabled APK |
| Frostbite Mod Loader repository | Host-side desktop GUI for mod APK import/packaging | Desktop application; updated Frostbite-enabled APK containing mod payloads |
| Frostbite mod project | `ModInitializer`, mixin classes, annotation-processor output, optional mod data | Mod APK or `.frostbite` package containing `classes.dex`, `mod.json`, hook manifest, and optional assets |

The patched `MainActivity` must be able to resolve the bootstrap bridge before it can load mods. Put that small bridge in the APK's normal DEX set (ideally the primary DEX) and have it call into the Frostbite Android runtime. The two host-side desktop apps operate on APK files; they are not loaded into the game. The in-game mod loader is part of the Frostbite Android runtime and loads each separate mod DEX. Mod projects compile against Frostbite API classes but must not package duplicate copies of those classes.

The **Mods** tab accepts a `.frostbite` package (or a legacy mod APK) as its input, but the game does not install or launch it as a second application. It extracts the mod's descriptor and DEX payload and embeds them as a `.frostbite` asset, and can also remove mods already embedded in a Frostbite game APK. The embedded runtime loads the DEX through `DexClassLoader`. Adding or removing a mod produces a newly rebuilt, signed game APK; live external mod installation is a separate future feature.

Use three repositories: keep this repository as `frostbite-core`, and create `frostbite-installer` and `frostbite-mod-loader` for the two host-side desktop GUI apps. The core repository owns the public API, annotation processor, Android runtime, and native hook backend because those pieces must release in lockstep. The installer and mod loader depend on versioned core artifacts and can release independently. Version the mod descriptor, hook-manifest schema, and core API separately so incompatibilities can be rejected before loading DEX code.

## Mod initializer and mixin registration

Frostbite loads the entrypoint named in each `mod.json` and calls its `onLoad` once. Every `@Mixin` declared in that mod's source is recorded by the annotation processor and is registered automatically from the generated hook manifest; mod authors do not have to list each mixin manually. The initializer API is:

```java
@FrostbiteMod(id = "subway-tools", version = "1.0.0")
public final class SubwayToolsMod implements ModInitializer {
    @Override
    public void onLoad(ModContext context) {
        // Initialize mod state or log that the mod was loaded.
    }
}
```

`onLoad` is the mod's initialization hook, not the IL2CPP-ready hook. Frostbite invokes it once after loading the mod and reading its generated manifest. Frostbite owns target resolution and native hook installation; if IL2CPP is not ready, hooks remain queued. Initially, every declared mixin in the package is enabled. An explicit enable/disable API for optional mixins can be added later. For an instance callback, Frostbite creates one mixin object per registered mixin class using an accessible no-argument constructor. That object is shared by callbacks on that mixin, not recreated per game object. Static callbacks need no mixin object. A menu UI is an optional mod built later on top of this API; it is not needed to create, discover, or load mods.

The APK bootstrap provides and retains the Java class loader that can load the mod's classes. The ByNameModding integration provides a reliable signal that IL2CPP metadata is ready. Frostbite should not guess readiness from a fixed sleep. If the selected integration has no readiness callback, a small platform adapter can poll a documented IL2CPP-ready condition with a timeout and diagnostic logs.

Proposed lifecycle:

```text
UNINITIALIZED -> WAITING_FOR_IL2CPP -> RESOLVING -> INSTALLING -> ACTIVE
                                      \-> FAILED
ACTIVE -> STOPPING -> STOPPED
```

Registration before readiness is queued. Repeated registration of the same mixin class is idempotent. `install()` should report per-hook outcomes and should not claim `ACTIVE` if required hooks failed. The runtime needs an explicit policy for optional hooks; the default should fail the mod initialization when a required selector cannot resolve.

Each `.frostbite` is an isolated package, so every package can use the same internal `META-INF/frostbite/hooks.json` path. The package descriptor and the top-level APK asset index are keyed by unique mod ID. Frostbite validates both schema versions and rejects duplicate IDs before loading code. In the first version, mods should not depend on classes from other mods; shared functionality belongs in Frostbite API libraries until a dependency class-loading policy is designed.

## Mixin targets

A mixin identifies one IL2CPP class by assembly, namespace, and class name:

```java
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character", targetClass = "CharacterMotor")
public final class CharacterMotorMixin {
    // Accessors and injections for this IL2CPP class.
}
```

These values refer to IL2CPP metadata, not Java classes. A missing namespace is the empty namespace. Resolution is scoped to all three values so same-named classes in different assemblies or namespaces do not collide. The assembly value should be the metadata assembly name as reported by the adapter (usually without guessing whether the filename suffix is present); the runtime should log the exact value it attempted.

## Method selectors and regex

The intended selector describes the method name, receiver kind, and ordered parameter types:

```text
MethodName(self;parameterType;parameterType;...)
```

`self` labels the implicit IL2CPP instance receiver (`this`); it is not a declared C# parameter. `static` marks a static method. The parameters after the receiver are the actual declared method parameters. No-argument examples are `UpdateMovement(self)` and `get_Active(self)`. A static no-argument method is `Reset(static)`. The processor/runtime adds the receiver marker to a canonical signature; it does not expect ByNameModding to report `self` as a normal parameter. A Java callback's `Object self` will receive an `Il2CppObject` wrapper around the native receiver. Mods must not retain that wrapper after the callback until the runtime provides explicit object pinning.

Examples:

```text
UpdateMovement(self)
ChangeLane(self;int)
get_Active(self)
SetSpeed(self;float)
Create(static;string;int)
```

ByNameModding enumerates methods on the already selected IL2CPP class. For each candidate Frostbite reads the method name, static/instance flag, and metadata parameter types, maps those types to canonical names, and creates a signature such as `ChangeLane(self;int)`. Selector matching happens against that string. It does **not** search native machine-code bytes for method names. Once metadata resolves one candidate, Dobby receives its native method pointer.

### Exact matching

Exact matching should be the default. It selects an overload by its full canonical signature and is safest for production mods. For example, `ChangeLane(self;int)` must only resolve the one-`int` overload, not `ChangeLane(self;float)`.

### Regex matching

Regex must be explicit in the API so a typo cannot silently change exact lookup into a broad search. The annotation shape is:

```java
@Inject(
    method = "ChangeLane\\(self;int\\)",
    mode = SelectorMode.REGEX,
    at = At.HEAD
)
```

The regex uses Java `Pattern` syntax and is applied to the **entire canonical signature** using `Matcher.matches()`, not `find()`. Parentheses are regex operators, so literal parentheses in the signature are escaped as `\\(` and `\\)` in a Java string. A broader example, `ChangeLane\\(self;.*\\)`, matches all `ChangeLane` overloads in the selected class. It still cannot match methods in another assembly, namespace, or class because candidates are scoped before regex evaluation.

Resolution rules:

- Zero matches: installation fails with the selector and target in the diagnostic.
- One match: install the hook.
- More than one match: fail by default and list each canonical signature. Explicit multi-match support can be added later, but should use a separate opt-in and install one hook per resolved method.
- Duplicate declarations resolving to the same native method: diagnose a conflict and apply the configured hook ordering policy; do not install competing Dobby hooks on the same address accidentally.

Type names need one stable normalization table. At minimum, document aliases and metadata forms for booleans, integer widths, floating-point values, strings, arrays, enums, object references, pointers, and value types. Types that cannot be distinguished from the metadata available in a supported runtime must be rejected as ambiguous.

## Field accessors

Accessors are generated Java-to-native calls, not hooks. Your `instance` example is a static field; `runTime` is an instance field. A getter returns the field value, and a setter takes the value to store:

```java
@AccessStaticField(value = "instance", mode = AccessMode.GETTER)
public static Object get_characterMotor();

@AccessStaticField(value = "instance", mode = AccessMode.SETTER)
public static void set_characterMotor(Object value);

@AccessField(value = "runTime", mode = AccessMode.GETTER)
public int get_runTime(Object characterMotor);

@AccessField(value = "runTime", mode = AccessMode.SETTER)
public void set_runTime(Object characterMotor, int value);
```

The static accessor has no target object. The instance accessor receives the IL2CPP object explicitly. A setter must receive the value to write; the original `set_characterMotor()` declaration had no value parameter, so it could not perform a write. Annotation processor validation should check accessor staticness, argument count, and Java type against the field metadata when available. If the field is absent or its type is unsupported, report it clearly.

The initial API should distinguish field lookup from field caching. Resolve fields after IL2CPP readiness and cache metadata handles only for the lifetime the adapter guarantees. Do not cache raw object addresses as if a managed IL2CPP object could never move or be collected.

## Method accessors

Method accessors invoke IL2CPP methods by metadata name and exact signature. The two examples mean:

```java
@AccessVoidMethod("ChangeLane(self;int)")
public void ChangeLane(Object characterMotor, int index);

@AccessBooleanMethod("get_Active(self)")
public boolean get_isActive(Object characterMotor);
```

The first invokes `ChangeLane` on `characterMotor` with `index`. The second invokes `get_Active` on that receiver and converts its result to Java `boolean`. The method accessor declaration does not install a Dobby hook. Its Java parameters and return type must correspond to the selected method and supported JNI/IL2CPP conversions.

For a less repetitive API, a later version could replace return-type-specific annotations with one `@AccessMethod` declaration and validate the Java method signature. The typed annotations above are convenient syntax, but should not create separate native lookup implementations.

## Injections

The annotation shape uses an enum for the injection point. An instance callback receives the hooked IL2CPP object as its first argument (`self`), followed by the target method's declared arguments in order. The `HEAD` example runs before the original IL2CPP method:

```java
@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void preUpdate(Object self) {
    // Runs before the original method.
}
```

The corresponding `TAIL` example runs after the original method:

```java
@Inject(method = "UpdateMovement(self)", at = At.TAIL)
public void postUpdate(Object self) {
    // Runs after the original method; observe its post-call state.
}
```

Declared parameters follow `self` in both the selector and Java callback. For example, if metadata reports `SetTarget(self;SYBO.Subway.CharacterMotor)`, an object argument is delivered as an `Il2CppObject` (or `null` if the native argument is null):

```java
@Inject(method = "SetTarget(self;SYBO.Subway.CharacterMotor)", at = At.HEAD)
public void beforeSetTarget(Object self, Il2CppObject target) {
    if (target != null) {
        long address = target.nativeAddress(); // valid only during this callback
    }
}
```

This is an illustrative signature; check the selected game's metadata for the actual method name and parameter type. `Object` is also accepted as the Java callback declaration for an object argument, but its runtime value is still an `Il2CppObject` or `null`. For instance `ChangeLane(self;int)`, the callback shape is `callback(Object self, int index)`. Boolean parameters use Java `boolean`.

`TAIL` is only valid for a target method that returns `void`. It runs the original method exactly once, then invokes the callback, then returns to the caller. A `TAIL` injection on a non-void method is invalid and must be rejected during installation after the target signature is resolved.

Call order:

```text
HEAD, void target: callback(self, args...) -> original(self, args...) -> return
HEAD, non-void target: callback(self, args..., CallbackInfoReturnable) -> original if not cancelled -> return original or supplied value
TAIL, void target only: original(self, args...) -> callback(self, args...) -> return
```

At `HEAD`, a non-void method can return early with a value chosen by the mod. The callback remains `void`; Frostbite passes a `CallbackInfoReturnable<T>` object whose default state means “call the original.” Calling `setReturnValue(value)` stores a value and marks the invocation cancelled, so the native trampoline skips the original method and returns that value using the target's ABI:

```java
@Inject(method = "get_Health(self)", at = At.HEAD)
public void beforeGetHealth(Object self, CallbackInfoReturnable<Float> cir) {
    if (shouldOverrideHealth(self)) {
        cir.setReturnValue(100.0f); // original get_Health is not called
    }
}
```

For a non-void target, callbacks that do not call `setReturnValue` fall through to the original. The supplied value must match the exact target return type; no implicit numeric widening or reference cast should be guessed. The first ABI implementation should support a narrow set of return types, then expand the matrix deliberately.

### Cancellation and replacement

`CallbackInfoReturnable<T>` is the explicit cancellation mechanism for non-void `HEAD` injections. A corresponding `CallbackInfo` can later support cancellation of `void` methods. Do not overload `HEAD` to mean replacement of arguments or arbitrary redirection; those features need separate annotations and separate ABI rules. Until such features exist, callbacks cannot change arguments.

## Callback rules and native boundaries

The trampoline must preserve the IL2CPP ABI for the exact target: implicit receiver, parameter order, value-type layout, hidden return buffers where applicable, and return value. Java types do not automatically match IL2CPP types. Begin with a small ABI matrix (for example, instance `void` methods with reference receiver and a limited set of primitive arguments), then add signatures deliberately.

JNI dispatch must handle:

- Attaching a native-created thread to the correct JVM and detaching it only when Frostbite attached it.
- Caching global class references and callback method IDs without leaking them across unload/reload.
- Managing IL2CPP object references according to the host adapter's GC rules.
- Converting strings, arrays, enums, and value types with documented ownership and lifetime.
- Pending Java exceptions. A callback exception must not unwind through an IL2CPP native frame; capture/log it and apply a documented policy.
- Reentrancy: callback code may call the hooked method again. The runtime must avoid accidental infinite recursion and define how original-call access works.
- Thread safety: registration and installation may occur on one thread while hooks execute on game threads.

## Errors, conflicts, and cleanup

Diagnostics should include the mod ID, mixin class, target triple, selector, and failure reason. At minimum, report malformed selectors, no match, ambiguous match, unsupported type, duplicate hook address, missing callback class/method, and Dobby installation failure.

Hook ordering must be deterministic when multiple mods inject the same target. Define a priority and stable tie-break order, then install one dispatcher per target that calls the registered callbacks in that order and the original method exactly once. Avoid stacking unrelated Dobby hooks on one address unless the hook library explicitly supports safe chaining.

The runtime must retain original pointers and Java references while hooks are active. On unload, stop accepting new callbacks, wait for in-flight callbacks to finish, restore hooks when safe, release JNI global refs, and clear cached metadata. If the host cannot safely unload native hooks, state that limitation and keep the mod/runtime loaded rather than freeing memory still referenced by a trampoline.

## Build and Android compatibility

The API and processor Gradle modules request a Java 27 toolchain. The API emits Java 17 bytecode so Android tooling can consume it; the Android runtime also targets Java 17 bytecode. Java 27 language features cannot appear in Android-delivered sources yet. The processor runs on JDK 27. The runtime build has been checked with JDK 27, Gradle 9.8, Android Gradle Plugin 9.4.0, SDK 37, and NDK 26.1.10909125.

The native runtime builds only for `armeabi-v7a` and `arm64-v8a`. Its CMake file pins BNM and the BepInEx Dobby fork to commit hashes. The configured Unity header version is presently `222`; it must be checked against the actual target build before on-device use.

## Implementation plan

### Phase 0: choose a controlled target

1. Use a test game or an APK/build you are authorized to modify. Define a supported target profile; do not promise compatibility with arbitrary APKs.
2. Record the Unity version, package name, `MainActivity` class, minimum Android version, and native ABIs.
3. Pin one ByNameModding revision, one Dobby revision, and one Android NDK version for the prototype.

**Done when:** the target APK launches unmodified on a test device and its Activity/native-library layout is understood well enough to patch repeatably.

### Phase 1: stable declaration and processor

1. Finalize annotation names and selector grammar, including explicit exact-vs-regex mode.
2. Validate mixin placement, callback instance/static rules, Java callback signature, duplicate declarations, and annotation values at compile time.
3. Emit a schema-versioned manifest per mod artifact and provide a deterministic merge/package step.
4. Add processor-level fixtures for valid declarations and compile-time failures.

**Done when:** a sample build produces a deterministic manifest; malformed callbacks fail compilation with source locations; two dependencies cannot overwrite each other's manifests.

### Phase 2: installer, mod loader, and in-game mod discovery

1. Define the mod APK contract: a declared `ModInitializer`, generated `mod.json`, and generated hook manifest inside the mod APK.
2. Build the Installer GUI workflow to add the Frostbite Android runtime/bootstrap/native libraries to one supported game APK profile and patch its `MainActivity` call.
3. Build the Mod Loader GUI workflow to validate a Frostbite-enabled APK plus mod APK inputs, extract each mod's `classes.dex` and Frostbite assets, package them as `.frostbite` files, and update the asset index.
4. Build the embedded runtime's discovery path: read the index, validate unique IDs/API versions, extract each DEX, and create a parent-first class loader per mod.
5. Load each declared `ModInitializer` by its named entrypoint; do not scan all DEX classes. Sign the resulting APK with v1/v2/v3 using the bundled development key, verify the signatures, then install it.

**Done when:** the Installer GUI produces a launchable Frostbite-enabled test APK; the Mod Loader GUI adds two mod APKs as separate payloads; Frostbite discovers both, calls each compatible `onLoad` once, rejects duplicate IDs/API mismatches clearly, and does so before installing hooks.

### Phase 3: mixin registration and IL2CPP resolution without hooks

1. Load the generated `hooks.json` for each mod and associate every callback with that mod's class loader.
2. Automatically register all mixins listed in the manifest; retain each mod class loader for callback dispatch.
3. Connect the selected ByNameModding readiness callback and expose waiting, resolving, active, and failed states.
4. Resolve targets and log exact/regex matches without installing Dobby hooks.

**Done when:** a separately packaged mod is discovered from the index, its initializer runs, its mixins are discovered from generated metadata without manual class scanning, and Frostbite prints resolved targets with useful errors for missing or ambiguous selectors.

### Phase 4: first JNI/Dobby hook

1. Select one Android ABI and pin compatible NDK, Dobby, and ByNameModding versions.
2. Implement one native trampoline shape for instance `void` methods and a bounded set of arguments.
3. Cache and invoke Java callbacks through JNI; preserve/call the original method exactly once.
4. Add `HEAD`/`TAIL` dispatch for void methods and returnable `HEAD` dispatch for supported non-void types, with lifecycle logs and safe failure handling.

**Done when:** an on-device sample mod logs before/after an IL2CPP method and the game continues normally; repeated startup does not add duplicate hooks.

### Phase 5: accessors and broader types

1. Implement static and instance field get/set accessors.
2. Implement method accessors through ByNameModding invocation APIs.
3. Add primitive/reference/string conversions, then arrays, enums, and value types one category at a time.
4. Generate type-specific native stubs or use a proven generic dispatcher only where ABI-safe.

**Done when:** accessor declarations are validated and exercised against a sample IL2CPP class for each supported type category.

### Phase 6: ordering, cancellation, and release quality

1. Add deterministic priorities and one dispatcher for shared targets.
2. Define and implement cancellation/replacement as separate annotations with typed results.
3. Add unload/reload handling, JNI ref cleanup, exception policy, and reentrancy protection.
4. Publish ABI support, host requirements, known limitations, and a sample mod template.

**Done when:** multiple mixins on one method execute in documented order, errors are actionable, and supported unload paths leave no dangling trampoline or JNI reference.

## What is implemented today

- `@Mixin(assembly, namespace, targetClass)` identifies an IL2CPP target.
- `@Inject(method, at, mode, priority)` records callback declarations and exact or Java-regex selectors.
- `@FrostbiteMod` marks a `ModInitializer`; the processor emits versioned mod and hook manifests.
- The Android runtime reads an explicit mod index, validates packages, loads each mod DEX with `DexClassLoader`, calls `onLoad`, and registers callback declarations in memory.
- `HookResolver` matches candidate method metadata, rejects missing/ambiguous targets and non-void `TAIL`, and orders callbacks.
- The separate [Frostbite-Sample-Mod](../fr/Frostbite-Sample-Mod) Android project builds an installable mod APK using only Java source.
- The combined [Frostbite-Desktop](../fr/Frostbite-Desktop) GUI has **Install Frostbite** and **Add Mods** tabs. It patches the launch Activity, embeds the runtime and mod packages, and signs each output.

The Android AAR and host tools build with JDK 27 for `armeabi-v7a` and `arm64-v8a`. On the `Pixel_10_Pro_XL` emulator, the resulting Subway Surfers 3.70.3 APK loaded the sample mod, installed one BNM-resolved Dobby hook, and logged `CharacterMotor.UpdateMovement HEAD call=1`, `TAIL call=1`, `HEAD call=301`, and `TAIL call=301` during gameplay. The sample's Java callbacks require no mod-authored C++.

The current native trampoline supports instance methods with zero to two word-sized arguments: object references, `int`, and `boolean`. Supported returns are `void`, object reference, `int`, `boolean`, and `float`. Floating-point parameters, 64-bit parameters, value types, static targets, and more than two arguments are rejected. Field and method accessor annotations are declarations only; binding and invocation are future work. The runtime keeps installed hooks for the process lifetime and does not support live unload.
