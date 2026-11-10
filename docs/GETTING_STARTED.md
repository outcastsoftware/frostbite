---
title: Build Pipeline
nav_order: 3
---

# Build and use Frostbite

Frostbite lets a mod author write Java callbacks for Unity IL2CPP methods. The mod author does not write C++. The Frostbite runtime supplies the JNI bridge and native hooks. A desktop app handles the two APK packaging steps.

This guide describes the working Subway Surfers 3.70.3 prototype. The installer can inspect other APKs, but compatibility with an arbitrary Unity game is not established.

## What you build

| Input | Produced by | Purpose |
| --- | --- | --- |
| Original game APK | Game distribution | The unmodified Unity IL2CPP game |
| `frostbite-runtime.zip` | Frostbite core build | Java runtime and ARM native libraries |
| Frostbite game APK | **Install Frostbite** tab | Game with bootstrap and runtime, ready for mods |
| Mod APK | Your Java Android mod project (`assembleDebug`) | Mod DEX and generated Frostbite manifests |
| `.frostbite` mod | `packageFrostbite` task | The mod DEX, ARM libraries, and manifests only — no full APK, with Frostbite branding |
| Final game APK | **Mods** tab | Frostbite game plus one or more mod packages |

Install the **final game APK** on Android. A mod package is an input to the desktop app; installing a mod as a separate Android app does not load it into the game.

A `.frostbite` mod is just the mod payload (DEX, optional `arm64-v8a`/`armeabi-v7a` libraries, and the generated manifests), so it is much smaller than a full mod APK. The desktop **Mods** tab accepts both `.frostbite` mods and legacy mod APKs.

## Requirements

- JDK 27 and Gradle 9.8 for these source builds. The Android Java source and bytecode target Java 17.
- Android SDK 37 and build tools with `d8`, `zipalign`, and `apksigner`. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` so the desktop app can find the SDK.
- Android NDK 26.1.10909125 and CMake for building the native runtime. The supplied runtime ZIP can be reused without building native code for each mod.
- The desktop app includes one development signing key and uses it automatically in both tabs. You do not need to create or select a keystore.
- A complete Unity IL2CPP APK with an ARM library. Frostbite currently builds `arm64-v8a` and `armeabi-v7a`; no x86 native library is supplied. An emulator must support ARM translation or use an ARM image.

The confirmed target is Subway Surfers 3.70.3. Its launch Activity for this build is `com.sybogames.chili.multidex.ChiliMultidexSupportActivity`. Its `CharacterMotor` mixin target is `Assembly-CSharp`, namespace `SYBO.RunnerCore.Character`, class `CharacterMotor`. Other versions may differ.

## 1. Build Frostbite and the desktop app

Open the `Frostbite` core project in a Java/Android IDE with JDK 27. Run the Gradle task `runtimeBundle`. The output is:

```text
Frostbite/build/distributions/frostbite-runtime.zip
```

Open the separate `Frostbite-Desktop` project and run Gradle `installDist`. On Windows, double-click:

```text
fr/Frostbite-Desktop/build/install/frostbite-desktop/bin/frostbite-desktop.bat
```

The desktop project is self-contained: it builds against a prebuilt `host-tools` jar in its own `libs/` and does not need the core source. The app has two tabs, uses one bundled development key for both, and finds the Android SDK installed on this computer.

## 2. Make a Java mod

Use `Frostbite-Sample-Mod` as a starting project. It is self-contained: prebuilt Frostbite jars live in its `libs/`, so a mod builds without the core source. Its `build.gradle` declares:

```groovy
dependencies {
    compileOnly files('libs/frostbite-api.jar')
    annotationProcessor files('libs/frostbite-processor.jar', 'libs/frostbite-api.jar')
}
```

Those jars are built from the `framework` branch (`frostbite-api.jar`, `frostbite-processor.jar`, and `frostbite-host-tools.jar` for `packageFrostbite`). Rebuild and replace them when the core API changes.

Give your own mod a unique Android `applicationId` and a unique Frostbite ID. The entrypoint must implement `ModInitializer`:

```java
@FrostbiteMod(id = "subway-tools", version = "0.1.0")
public final class SubwayToolsMod implements ModInitializer {
    @Override public void onLoad(ModContext context) {
        context.log("Subway Tools loaded");
    }
}
```

Write a mixin for the IL2CPP class. The sample logs on both sides of a `void` method:

```java
@Mixin(assembly = "Assembly-CSharp",
       namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public final class CharacterMotorMixin {
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void beforeUpdate(Object self) {
        Log.i("FrostbiteSample", "UpdateMovement HEAD");
    }

    @Inject(method = "UpdateMovement(self)", at = At.TAIL)
    public void afterUpdate(Object self) {
        Log.i("FrostbiteSample", "UpdateMovement TAIL");
    }
}
```

`self` identifies an instance method's receiver. It is the first Java callback parameter. Declared IL2CPP arguments follow it, in order. For example, `ChangeLane(self;int)` maps to `callback(Object self, int lane)`. Object parameters may be declared as `Object` or `Il2CppObject`; the runtime supplies an `Il2CppObject` or `null`. Its native address is valid only during that callback.

`HEAD` runs before the original method. `TAIL` runs after it. **TAIL is valid only on `void` targets.** For a supported non-void target, a `HEAD` callback can return early by accepting `CallbackInfoReturnable<T>` as its final parameter and calling `setReturnValue(value)`. If it does not call that method, the original target runs. The supplied return value must have the target's exact type.

### Exact and regex selectors

The default is `SelectorMode.EXACT`. The selector is compared with the canonical method signature *within the assembly, namespace, and class named by `@Mixin`*. Examples: `UpdateMovement(self)`, `ChangeLane(self;int)`, and `get_Active(self)`. The `self` token is part of the signature, not an IL2CPP parameter type.

For regex matching, use the actual `mode` member:

```java
@Inject(method = "ChangeLane\\(self;int\\)", at = At.HEAD,
        mode = SelectorMode.REGEX)
public void beforeLane(Object self, int lane) { }
```

Frostbite applies Java `Pattern.matches`, so the **whole canonical signature** must match. Escape literal parentheses in the regex, then double the backslashes in a Java string. A broader `ChangeLane\\(self;.*\\)` can match several overloads; Frostbite rejects an ambiguous result rather than choosing one. Zero matches also fail with a diagnostic. Regex never searches another assembly, namespace, or class.

Injection supports instance **and static** targets (a static selector uses `(static` and its callback has no `self`). Arguments and returns may be any of `int`, `long`, `float`, `double`, `boolean`, enum (as its underlying integer), object reference, or **value type / struct** (which arrives as a boxed object; read its fields with `@AccessField` or `Il2Cpp`). Any number of arguments is allowed. Non-void targets support return override (any scalar or object type). Simple signatures use a fast fixed-shape path; everything else (float/double/long/struct arguments, more than two arguments, 64-bit or `double` returns) is handled by a register-preserving universal trampoline on `arm64-v8a`. If one hook is unsupported or ambiguous it is skipped with a warning and the rest of the mod still loads.

A void target can be **cancelled** (a name-based NOP): declare a trailing `CallbackInfo` and call `cancel()` at HEAD, and the original never runs.

```java
@Inject(method = "ApplyGravity(self)", at = At.HEAD)
public void noGravity(Object self, CallbackInfo ci) { ci.cancel(); }
```

A callback can also **change the arguments** the original method receives. Declare an `Arguments` parameter (after `self` for an instance target) instead of typed-per-argument parameters, and call its `setInt`/`setFloat`/`setObject`/… before the original runs:

```java
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void onChangeLane(Object self, Arguments args) {
    args.setInt(0, 1);   // always change lane to the right
}
```

`Arguments` reads and writes every argument type (objects and structs as `Il2CppObject`), and the edits are passed to the original method and to later hooks in the chain. A hook using `Arguments` always runs on the universal trampoline.

The universal trampoline handles any arity, including arguments that spill onto the stack, and return override covers every type including value-type (struct) returns. The `arm64-v8a` path is exercised on-device. The `armeabi-v7a` (32-bit, softfp) universal trampoline is implemented but **experimental and unverified** — it has not been run on a 32-bit target; the library still loads and the fixed-shape path works regardless. Accessors and method invocation (via `@AccessField` / `@AccessStaticField` / `@AccessMethod` or the `Il2Cpp` helper) cover the same type set for both static and instance members and every argument count, through IL2CPP's own invoker.

### Accessors: reading fields, writing fields, and calling methods

Besides hooking, a `@Mixin` can declare **accessors** for its target type. Accessor methods have no body: they are `public abstract` (so the mixin is `abstract`), and Frostbite generates their implementations. Supported value and argument types are `int`, `float`, `boolean`, and object references (`Object` or `Il2CppObject`).

```java
// instance field get / set (first parameter is the target instance)
@AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
public abstract boolean get_canAccelerate(Object motor);
@AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
public abstract void set_canAccelerate(Object motor, boolean value);

// static field get / set (no instance parameter)
@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.GETTER)
public abstract int get_budget();
@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.SETTER)
public abstract void set_budget(int value);

// method invoker (selector picks instance vs static via self/static)
@AccessMethod("CanChangeLane(self;int)")
public abstract boolean canChangeLane(Object motor, int lane);
@AccessMethod("ResetSession(static)")
public abstract void resetSession();
```

The static accessors and the static invoker read and write static state on the mixin's target class; no instance is passed. A mixin may hold only accessors and no injections. Frostbite creates one instance per mixin (its generated subclass) and registers it, so any code can reach a mixin's accessors, not just its own callbacks:

```java
PoolManagerMixin pool = Mixins.get(PoolManagerMixin.class);
pool.set_budget(pool.get_budget() + 1);
```

Field get/set and method invocation work for both static and instance members and for every supported type; const fields are readable but not writable. The annotation processor records each mixin's generated implementation in `META-INF/frostbite/mixins.json`.

Build the mod project with Gradle `assembleDebug`. The sample produces `fr/Frostbite-Sample-Mod/build/outputs/apk/debug/frostbite-sample-mod-debug.apk`. The annotation processor creates `META-INF/frostbite/mod.json` and `META-INF/frostbite/hooks.json` in the APK. Those files tell Frostbite which initializer and mixins to load; no manual class scanning or registration is required.

### Package a `.frostbite` mod

To ship a small, branded mod package instead of a whole APK, run the `packageFrostbite` task:

```text
gradlew packageFrostbite
```

The sample produces `fr/Frostbite-Sample-Mod/build/outputs/frostbite/frostbite-sample-mod.frostbite`. The task first runs the Android plugin's `assembleRelease`, then strips the release APK down to the mod DEX, any ARM native libraries, and the Frostbite manifests. It is named `packageFrostbite` rather than `assembleRelease` because the Android plugin already defines `assembleRelease`. Add the task to your own mod's `build.gradle` from the sample:

```groovy
configurations { frostbitePackager }
dependencies { frostbitePackager 'dev.frostbite:host-tools:0.1.0-SNAPSHOT' }

tasks.register('packageFrostbite', JavaExec) {
    group = 'frostbite'
    dependsOn 'assembleRelease'
    classpath = configurations.frostbitePackager
    mainClass = 'dev.frostbite.host.ModPackager'
    def releaseDir = layout.buildDirectory.dir('outputs/apk/release')
    def out = layout.buildDirectory.file("outputs/frostbite/${project.name}.frostbite")
    outputs.file out
    doFirst {
        def apks = releaseDir.get().asFile.listFiles({ f -> f.name.endsWith('.apk') } as FileFilter)
        if (apks == null || apks.length == 0) throw new GradleException('No release APK; run assembleRelease')
        out.get().asFile.parentFile.mkdirs()
        args = [apks[0].absolutePath, out.get().asFile.absolutePath]
    }
}
```

The on-disk file name is cosmetic; the desktop app reads the real mod ID from inside the package.

## 3. Install Frostbite into the game APK

Open the `Frostbite-Desktop` app and choose **01 Install Frostbite**.

1. Select the original, complete game APK.
2. Select `frostbite-runtime.zip`.
3. Enter the full launch Activity class. For the confirmed Subway Surfers build, use `com.sybogames.chili.multidex.ChiliMultidexSupportActivity`.
4. Choose an output APK path that differs from the original.
5. Click **Build Frostbite APK**.

The app patches that Activity's `onCreate`, embeds the runtime DEX and ARM native libraries, aligns the APK, signs with its bundled development key using v1, v2, and v3, and verifies the signatures. A successful build opens the **02 Mods** tab with the Frostbite game APK already selected.

## 4. Add or remove mods

In **02 Mods**, select the Frostbite game APK if it was not filled automatically. Its currently installed mods load into the **Installed mods** list.

- To add mods, click **Add mods** and choose one or more `.frostbite` mods (legacy mod APKs also work).
- To remove mods, select entries in **Installed mods**.

Choose a different output path and click **Build modded APK**. The build adds and removes in one pass, and the same bundled key signs the result automatically.

The app validates each added mod's descriptor and DEX, packages it as a separate `.frostbite` asset, and rewrites `assets/frostbite/mods/index.json` with the resulting set. On game startup, Frostbite reads that index, loads each mod's DEX and named initializer, then resolves and installs its declared hooks. Duplicate mod IDs are rejected. To replace an existing mod version, remove it and add the new one in the same build.

## 5. Install and observe

Install the **final** signed game APK on your Android device or emulator. Android Studio Device Manager can install an APK, or use `adb install` if you prefer. An already installed copy signed with a different key must be removed before installation; removing it may erase its local data. Frostbite APKs built with this bundled key can generally update each other. The key is included in the public desktop app, so it is suitable for development builds, not for establishing a unique trusted publisher identity.

Launch the game and reach gameplay to trigger `UpdateMovement`. For the sample, filter logcat for `Frostbite` and `FrostbiteSample`. The confirmed emulator run logged `CharacterMotor.UpdateMovement HEAD call=1`, `TAIL call=1`, and later calls 301 and 601 while the game continued.

## Common problems

| Symptom | Check |
| --- | --- |
| Build button disabled | Select existing input files, an output path, and the launch Activity in step 01. In step 02, add at least one mod or select an installed mod to remove. |
| Launch Activity not found | Confirm the full Activity class for this exact APK version; do not assume another game or release uses the Subway Surfers class. |
| Frostbite mod index missing | The Mods tab needs the APK produced by Install Frostbite, not the original game APK. |
| No callback logs | Confirm the game reached the hooked method, inspect Frostbite resolution logs, and verify that this game version has the specified assembly/class/signature. |
| Ambiguous regex | Narrow the expression until it matches one canonical signature. |
| Duplicate mod ID | Rebuild from the clean Frostbite game APK with one version of that mod. |
| APK install reports a signature mismatch | The original game and Frostbite build have different signatures; remove the original installation before installing the patched APK. |

The runtime keeps hooks for the life of the game process. Live unload is not implemented.
