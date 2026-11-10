# Frostbite User Guide

Frostbite is a mixin framework for Unity **IL2CPP** Android games. You write mods in **Java**
(no C++, no manual offsets): you annotate Java classes, and Frostbite hooks the game's
IL2CPP methods, reads and writes its fields, and calls its methods for you by name. A desktop
app packages everything into a modified game APK.

This guide covers everything from installing the tools to shipping a mod, with a full type
reference and every limitation. For the build-pipeline details see
[GETTING_STARTED.md](GETTING_STARTED.md).

---

## 1. How it works

```
 your Java mod  ──assembleDebug──▶  mod DEX + generated manifests  ──packageFrostbite──▶  .frostbite
 game APK       ──Install Frostbite tab──▶  Frostbite game APK (runtime + bootstrap patched in)
 Frostbite APK + .frostbite  ──Mods tab──▶  final game APK  ──install on device──▶  mods run in-game
```

- At build time, an **annotation processor** turns your `@Mixin` / `@Inject` / `@Access*`
  classes into JSON manifests (`mod.json`, `hooks.json`, `mixins.json`) and, for accessors,
  generated implementation classes.
- At runtime, the Frostbite **native runtime** (C++ using [BNM](https://github.com/ByNameModding/BNM-Android)
  for IL2CPP metadata and [Dobby](https://github.com/BepInEx/Dobby) for hooking) resolves your
  selectors to real IL2CPP methods **by name**, installs hooks, and dispatches to your Java
  callbacks. You never deal with addresses or game-version offsets in your mod.

Mods are plain callbacks and accessors. There is **no in-game menu/overlay, no ESP/drawing, and
no raw-address memory access** — Frostbite is a code-modding engine, not a mod-menu UI toolkit.

---

## 2. Prerequisites

- **JDK 27** and Gradle 9.x to build the Frostbite core. Android mod source/bytecode targets
  Java 17. (The core's own Gradle must run on a JDK the Gradle version supports — JDK 21 is a
  safe launcher; toolchains still compile at 27/17.)
- **Android SDK 37** with `d8`, `zipalign`, `apksigner`. Set `ANDROID_HOME` or
  `ANDROID_SDK_ROOT`.
- **Android NDK 26.1.10909125** and CMake to build the native runtime (or reuse the prebuilt
  `frostbite-runtime.zip`).
- A complete Unity IL2CPP game APK with an **ARM** library (`arm64-v8a` and/or `armeabi-v7a`).
  No x86 native library is produced; an emulator must translate ARM (e.g. libhoudini / libnb)
  or use an ARM image.
- The desktop app ships one development signing key and uses it automatically.

The confirmed target is **Subway Surfers 3.70.3**, launch Activity
`com.sybogames.chili.multidex.ChiliMultidexSupportActivity`.

---

## 3. Create a mod project

Start from `frostbite-sample`. A mod is an ordinary Android application project that depends
only on the prebuilt Frostbite jars in `libs/`, so it builds without the core source:

```groovy
plugins { id 'com.android.application' version '9.4.0' }

android {
    namespace 'com.example.mymod'
    compileSdk 37
    defaultConfig { applicationId 'com.example.mymod'; minSdk 26; targetSdk 35 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

repositories { google(); mavenCentral() }

dependencies {
    compileOnly files('libs/frostbite-api.jar')
    annotationProcessor files('libs/frostbite-processor.jar', 'libs/frostbite-api.jar')
}
```

Add the `packageFrostbite` task from the sample's `build.gradle` to produce a `.frostbite`
package (it strips a release APK down to the mod DEX, manifests, and any ARM libraries).

Give your mod a **unique `applicationId`** and a **unique Frostbite id**.

---

## 4. The entrypoint

Exactly one class per mod implements `ModInitializer` and carries `@FrostbiteMod`:

```java
@FrostbiteMod(id = "my-mod", version = "1.0.0")
public final class MyMod implements ModInitializer {
    @Override public void onLoad(ModContext context) {
        context.log("My Mod loaded");
    }
}
```

- `id` must match `[a-z0-9][a-z0-9._-]*` and be unique across installed mods.
- `onLoad` runs when the mod is loaded, **before** IL2CPP is ready — do not call accessors or
  invoke game methods here; do that from inside a callback (see §5), which only fires once the
  game is running.
- `ModContext.log(msg)` writes to logcat under tag `Frostbite/<id>`.

---

## 5. Mixins

A mixin binds a Java class to one IL2CPP type:

```java
@Mixin(assembly = "Assembly-CSharp",
       namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public final class CharacterMotorMixin { /* injections and/or accessors */ }
```

- `assembly` is the IL2CPP image name (usually `Assembly-CSharp`, or e.g.
  `UnityEngine.CoreModule` for `Vector3`).
- `namespace` may be empty (`""`) for a global type.
- A mixin that declares **accessors** (§7) must be `public abstract` (Frostbite generates a
  concrete subclass). A mixin with only `@Inject` methods can be `final`.
- Frostbite creates **one instance per mixin** and registers it; see §9.

---

## 6. Selectors — naming a method

A selector is the **canonical signature** of a method *within the mixin's assembly, namespace,
and class*:

```
MethodName(receiver[;ParamType[;ParamType...]])
```

- `receiver` is the literal token `self` (instance method) or `static` (static method). It is
  part of the signature, not an IL2CPP parameter.
- Each `ParamType` is the canonical type name. System types are lowercased short names:
  `void`, `boolean`, `int`, `long`, `float`, `double`, `string`. Everything else is its full
  `Namespace.Name`, including enums and structs: `SYBO.RunnerCore.Character.MotorStateKind`,
  `UnityEngine.Vector3`.

Examples:

```
UpdateMovement(self)
ChangeLane(self;int)
SwitchState(self;SYBO.RunnerCore.Character.MotorStateKind)
Teleport(self;UnityEngine.Vector3)
get_Working(static)
Despawn(static;UnityEngine.Transform)
```

**Finding selectors and field names.** Dump the game with a tool such as Il2CppDumper to see
method names, parameter types, and field names (e.g. `_canAccelerate`, or a C# auto-property's
backing field `<AllowInput>k__BackingField`). Then write the selector in the canonical form
above. The native runtime logs the exact canonical signatures it enumerates per hooked class,
which you can cross-check in logcat.

**Exact vs regex.** The default is `SelectorMode.EXACT` (the selector must equal the canonical
signature). For fuzzy matching use `mode = SelectorMode.REGEX`; the whole canonical signature
must match (`Pattern.matches`), so escape literal parentheses and double the backslashes in a
Java string:

```java
@Inject(method = "ChangeLane\\(self;int\\)", at = At.HEAD, mode = SelectorMode.REGEX)
public void beforeLane(Object self, int lane) { }
```

A selector that matches **more than one** method is rejected (narrow it); **zero** matches also
fail. Both are reported in logcat and the hook is skipped (the rest of the mod still loads).

---

## 7. Injection — `@Inject`

A hook runs your Java callback around a game method. Callbacks return `void`; what the callback
*does* (reads args, overrides return, cancels, modifies args) is expressed through its
parameters.

### HEAD and TAIL

```java
@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void before(Object self) { Log.i("Mod", "before"); }

@Inject(method = "UpdateMovement(self)", at = At.TAIL)
public void after(Object self) { Log.i("Mod", "after"); }
```

- `At.HEAD` runs **before** the original; `At.TAIL` runs **after** it.
- **`At.TAIL` is only valid on `void` targets.** For a non-void method, use `At.HEAD` with a
  return override.
- `priority` (int, default 0) orders multiple hooks on the same method — higher runs first.

### Parameters

- For an **instance** target (`self`), the first callback parameter is the receiver, declared
  as `Object` or `Il2CppObject`. A **static** target has no receiver parameter.
- Declared IL2CPP arguments follow, in order. Each maps to a Java type per the table in §12
  (object/struct arguments are `Object`/`Il2CppObject`; a struct is a boxed copy — read its
  fields with an accessor or `Il2Cpp`).
- A trailing control object (`CallbackInfo` or `CallbackInfoReturnable`) is optional for void
  targets and required for return override (below).

```java
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void onLane(Object self, int lane) { /* lane is the requested lane */ }
```

### Override a return value (non-void)

Add `CallbackInfoReturnable<T>` as the last parameter and call `setReturnValue` at HEAD. The
original is skipped and your value is returned. `T` must match the target's return type; a
struct return is a boxed `Il2CppObject` (see §8).

```java
@Inject(method = "get_CanJump(self)", at = At.HEAD)
public void alwaysJump(Object self, CallbackInfoReturnable<Boolean> cir) {
    cir.setReturnValue(Boolean.TRUE);           // infinite jump
}
```

If you do not call `setReturnValue`, the original runs normally.

### Cancel a void method (NOP)

Add `CallbackInfo` as the last parameter and call `cancel()` at HEAD. The original never runs —
a name-based NOP:

```java
@Inject(method = "ApplyGravity(self)", at = At.HEAD)
public void noGravity(Object self, CallbackInfo ci) { ci.cancel(); }
```

### Modify the arguments (write-back)

Declare a single `Arguments` parameter (instead of typed-per-argument parameters, after `self`)
and set values at HEAD. The changes are passed to the original method and to later hooks.
Index 0 is the first IL2CPP argument (not `self`).

```java
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void forceRight(Object self, Arguments args) {
    args.setInt(0, 1);                          // always change lane right
}
```

`Arguments` reads and writes every type: `getInt/setInt`, `getLong/setLong`,
`getFloat/setFloat`, `getDouble/setDouble`, `getBool/setBool`, `getObject/setObject`
(objects and structs as `Il2CppObject`). You may add a trailing `CallbackInfo` /
`CallbackInfoReturnable` to also cancel or override the return.

### Static method injection

Use a `(static)` selector; the callback has no `self`:

```java
@Inject(method = "TriggerPoolBalancing(static)", at = At.HEAD)
public void onTrigger() { Log.i("Mod", "pool balancing"); }
```

Return override on a static non-void target works the same way (add `CallbackInfoReturnable`).

---

## 8. Accessors — read/write fields and call methods

Accessors are **`public abstract` methods** on an `abstract @Mixin` class; Frostbite generates
their bodies. They target members of the mixin's IL2CPP type (except the field/method name is
the annotation value).

### Instance fields — `@AccessField`

```java
@AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
public abstract boolean getCanAccelerate(Object self);

@AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
public abstract void setCanAccelerate(Object self, boolean value);
```

A getter takes the instance and returns the value; a setter takes the instance and the new
value and returns void.

### Static fields — `@AccessStaticField`

No instance parameter:

```java
@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.GETTER)
public abstract int getBudget();

@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.SETTER)
public abstract void setBudget(int value);
```

`const` fields are **readable but not writable** (a setter on a const is a no-op).

### Methods — `@AccessMethod`

The selector picks instance vs static via `self`/`static`. An instance invoker takes the
instance first; the remaining Java parameters map to the IL2CPP arguments in order.

```java
@AccessMethod("ChangeLane(self;int)")
public abstract boolean changeLane(Object self, int lane);

@AccessMethod("get_Working(static)")
public abstract boolean working();
```

### Structs / value types

A value-type field, argument, or return is a **boxed `Il2CppObject`**. Read its fields with
field accessors on the struct's own type. For example, a `Vector3` returned by a method:

```java
@Mixin(assembly = "UnityEngine.CoreModule", namespace = "UnityEngine", targetClass = "Vector3")
public abstract class VectorMixin {
    @AccessField(value = "x", mode = AccessMode.GETTER) public abstract float x(Object v);
    @AccessField(value = "y", mode = AccessMode.GETTER) public abstract float y(Object v);
    @AccessField(value = "z", mode = AccessMode.GETTER) public abstract float z(Object v);
    @AccessMethod("get_one(static)") public abstract Il2CppObject one();   // Vector3.one
}
```

Object and struct accessor types must be declared as `Object` or `Il2CppObject`.

---

## 9. Reaching a mixin from elsewhere — `Mixins`

Frostbite registers one instance per mixin. Call its accessors from anywhere:

```java
CharacterMotorMixin motor = Mixins.get(CharacterMotorMixin.class);
int runTime = motor.getRunTime(self);
```

Accessor-only mixins (no `@Inject`) are instantiated and registered too. For an **instance**
member you still need the target object; obtain it from a callback's `self`, stash it, or get
it from a static singleton accessor.

---

## 10. The `Il2Cpp` helper (no annotations)

The same capabilities are available directly, without declaring accessor methods, through
`Il2Cpp` and an `Il2Cpp.Target(assembly, namespace, class, member)`:

```java
Il2Cpp.Target speed = new Il2Cpp.Target("Assembly-CSharp",
        "SYBO.RunnerCore.Character", "CharacterMotor", "get_ForwardSpeedTarget(self)");
float v = Il2Cpp.invokeFloat(motor, speed);                       // invoke
float x = Il2Cpp.getFloat(vec3, new Il2Cpp.Target(..., "x"));     // field read
Il2Cpp.setBool(motor, new Il2Cpp.Target(..., "_canAccelerate"), true);
```

Methods: `getInt/getLong/getFloat/getDouble/getBool/getObject`, matching `setX`, and
`invokeVoid/invokeInt/invokeLong/invokeFloat/invokeDouble/invokeBool/invokeObject(self, target,
args...)`. Pass `null` as `self` for a static member. `member` is a field name (for fields) or
a canonical selector (for `invoke`).

---

## 11. `Il2CppObject`

Any game object or boxed struct crossing into Java is an `Il2CppObject` wrapping a native
address (`nativeAddress()`). **The address is valid only during the current callback** unless
you keep the object alive through the game's own references. Do not store a raw `Il2CppObject`
and use it many frames later unless you know the game still holds that object.

---

## 12. Type reference

| C# / IL2CPP type        | Java type (callback arg / accessor)      | Return override value          |
| ----------------------- | ---------------------------------------- | ------------------------------ |
| `int`, short/byte/char* | `int`                                    | `Integer`                      |
| `long`                  | `long`                                   | `Long`                         |
| `float`                 | `float`                                  | `Float`                        |
| `double`                | `double`                                 | `Double`                       |
| `bool`                  | `boolean`                                | `Boolean`                      |
| enum                    | `int` (its underlying value)             | `Integer`                      |
| class / string / array  | `Object` or `Il2CppObject`               | `Il2CppObject` (or `null`)     |
| struct / value type     | `Object`/`Il2CppObject` (boxed copy)     | `Il2CppObject` (boxed)         |

\* smaller integer types arrive widened to `int`.

All of the above work for fields, method invocation, and injection arguments/returns, for both
static and instance members, and for any number of arguments.

---

## 13. Build, install, run

1. **Build the mod:** `gradlew packageFrostbite` → `build/outputs/frostbite/<name>.frostbite`
   (or `assembleDebug` for a full mod APK). The processor writes `META-INF/frostbite/mod.json`,
   `hooks.json`, and `mixins.json` into the output.
2. **Patch the game** (desktop app, *01 Install Frostbite*): select the original game APK and
   `frostbite-runtime.zip`, enter the launch Activity, choose an output path, build. This
   embeds the runtime and patches the launcher.
3. **Add mods** (desktop app, *02 Mods*): add your `.frostbite` (or a legacy mod APK), choose an
   output, build the final APK. It rewrites `assets/frostbite/mods/index.json`.
4. **Install** the final APK (`adb install`). If a copy signed with a different key is
   installed, uninstall it first (this may erase its data).
5. **Observe:** filter logcat for `Frostbite` and your own tag. On startup you will see the mod
   load and `Installed N IL2CPP hook(s)`; unsupported hooks are logged as skipped.

---

## 14. Full example

```java
@FrostbiteMod(id = "subway-tools", version = "1.0.0")
public final class SubwayToolsMod implements ModInitializer {
    @Override public void onLoad(ModContext context) { context.log("Subway Tools loaded"); }
}

@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public abstract class CharacterMotorMixin {
    // accessors
    @AccessMethod("SetAccelerate(self;boolean)")
    public abstract void setAccelerate(Object motor, boolean on);
    @AccessMethod("get_ForwardSpeedTarget(self)")
    public abstract float speed(Object motor);

    // cheats
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void each(Object self) { setAccelerate(self, true); }          // max accel

    @Inject(method = "get_CanJump(self)", at = At.HEAD)
    public void jump(Object self, CallbackInfoReturnable<Boolean> cir) {  // infinite jump
        cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "ApplyGravity(self)", at = At.HEAD)
    public void grav(Object self, CallbackInfo ci) { ci.cancel(); }       // no gravity (NOP)

    @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
    public void lane(Object self, Arguments a) { a.setInt(0, 1); }        // always go right
}
```

See the [sample README](../../frostbite-sample/README.md) for a build that also demonstrates
enums, static-method hooks, struct values, and struct-return override.

---

## 15. Limitations and edge cases

- **Injection targets must be concrete instance/static methods.** Hooking a method that only
  exists as an **abstract** declaration (no body) will abort the game; hook the concrete
  override instead.
- **Value-type (struct) support** is complete for fields, invocation, and injection
  arguments/returns on `arm64-v8a`. Struct **return override** is supported; struct reads/writes
  go through boxed `Il2CppObject`.
- **`const` fields are read-only.**
- **Overloads resolve by argument count** for `@AccessMethod` invocation. Two overloads with the
  same arity are ambiguous; use the exact selector form, which also encodes parameter types.
- **Ambiguous or zero-match selectors are skipped** with a warning (narrow the selector).
- **`At.TAIL` only on void targets.** TAIL callbacks do not receive the return value.
- **`Il2CppObject` addresses are per-callback** (see §11).
- **Hook capacity:** up to 32 distinct hooked method addresses per process for the fixed path
  and 32 for the universal path.
- **No live unload** — hooks persist for the life of the game process.
- **Architecture:** `arm64-v8a` is the fully validated path. The `armeabi-v7a` (32-bit)
  universal trampoline is implemented but **experimental and unverified on a real 32-bit
  target**; the library still loads and simpler hooks work. No x86 library is produced.
- **Engine version:** the native runtime is pinned to a Unity IL2CPP layout (`UNITY_VER`,
  default 2022.2 / `222`). A game on a different Unity version needs the runtime rebuilt with a
  matching selector.
- **Not provided:** an in-game menu/overlay, ESP/drawing, raw arbitrary-address memory
  read/write, and arbitrary mid-function instruction patching. Frostbite hooks at method
  boundaries and accesses members by name.

---

## 16. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Skipping hook … matched N methods` | Selector is ambiguous (N>1) or absent (N=0). Narrow or correct it against a dump. |
| `Could not hook …` | Signature unsupported on this ABI, or Dobby failed. Check the type and arity. |
| Callback never fires | The game did not reach that method; confirm it runs, and that the assembly/namespace/class/signature match this game version. |
| Game aborts on launch | Likely an abstract-method hook, or a wrong Unity-version runtime. |
| `Frostbite IL2CPP backend not installed` | An accessor/invoke ran before IL2CPP was ready — call it from inside a callback, not `onLoad`. |
| APK install signature mismatch | A differently signed copy is installed; uninstall it first. |
| No mod loads | The final APK must come from the Mods tab (with `index.json` listing your mod), not the bare game APK. |
| Garbled struct field values | Reading a struct field from a non-boxed source; read struct fields from the boxed `Il2CppObject` the accessor/invoke returned. |

---

Build with JDK 27 and Android SDK 37; Android mod source and bytecode target Java 17. Mod
authors never write C++.
