---
title: User Guide
nav_order: 2
---

# Frostbite — From Java to Modding Any IL2CPP Game

This is a complete course. If you can read and write ordinary **Java** — classes, methods,
annotations, generics, `interface`, `abstract` — you have everything you need to start. By the
end you will be able to write **any** Frostbite mod: read and change anything in a Unity game,
intercept any method, and understand exactly what each line does and why.

You will **not** write any C++. You will **not** copy memory addresses. You attach Java code to
the game by **name**, and Frostbite does the dangerous native work for you.

**Contents**

1. [The big idea (what modding even is)](#1-the-big-idea)
2. [How Frostbite thinks (the mental model)](#2-how-frostbite-thinks)
3. [Setting up a mod project](#3-setting-up-a-mod-project)
4. [Lesson 1 — Hello, mod (the entrypoint)](#4-lesson-1--hello-mod)
5. [Lesson 2 — Your first hook](#5-lesson-2--your-first-hook)
6. [Lesson 3 — Selectors: naming a method](#6-lesson-3--selectors-naming-a-method)
7. [Lesson 4 — Reading a method's arguments](#7-lesson-4--reading-a-methods-arguments)
8. [Lesson 5 — The four powers of a hook](#8-lesson-5--the-four-powers-of-a-hook)
9. [Lesson 6 — Accessors: fields and method calls](#9-lesson-6--accessors-fields-and-method-calls)
10. [Lesson 7 — The `Il2Cpp` helper and the `Mixins` registry](#10-lesson-7--the-il2cpp-helper-and-the-mixins-registry)
11. [Lesson 8 — Objects and structs (the one tricky concept)](#11-lesson-8--objects-and-structs)
12. [Lesson 9 — Build a real cheat, start to finish](#12-lesson-9--build-a-real-cheat)
13. [Build, install, run](#13-build-install-run)
14. [Full reference](#14-full-reference)
15. [Every limitation and edge case](#15-every-limitation-and-edge-case)
16. [Troubleshooting](#16-troubleshooting)
17. [Glossary](#17-glossary)

---

## 1. The big idea

A Unity game you download is, underneath, a pile of **classes and methods** — exactly like a
Java program. Unity games are usually written in **C#**, which is almost identical to Java
(classes, methods, fields, `this`, inheritance). When the game is built for Android with a mode
called **IL2CPP**, that C# is translated into native ARM machine code and shipped inside the
APK as a big library called `libil2cpp.so`.

So the game is running C#-shaped code, compiled to machine code. **Modding** means changing what
that code does — making the player invincible, giving infinite coins, forcing a function to do
nothing, and so on.

There are three things you ever want to do to someone else's code:

| You want to…                          | In plain Java terms                               | Frostbite tool |
| ------------------------------------- | ------------------------------------------------- | -------------- |
| Run your code when *their* method runs | Like wrapping a method with before/after logic    | **Injection** (`@Inject`) |
| Read or change *their* fields          | Like `obj.someField` / `obj.someField = x`        | **Field accessor** (`@AccessField`) |
| Call *their* method yourself           | Like `obj.someMethod(args)`                        | **Method accessor** (`@AccessMethod`) |

Frostbite gives you all three, in Java, by **name**. You say "the class `CharacterMotor` in
assembly `Assembly-CSharp`, its method `Jump`" and Frostbite finds it in the running game for
you. You never touch an address.

> **Why is this hard normally?** The game has no names at runtime — IL2CPP strips them into a
> metadata blob. Normal mod tools make you dump the game, find numeric offsets, and rewrite them
> every time the game updates. Frostbite uses a library called **BNM** that reads that metadata
> and resolves names at runtime, and a library called **Dobby** to install the hooks. You get to
> ignore both.

---

## 2. How Frostbite thinks

Three words you must internalize. They map onto Java concepts you already know.

### Mixin = "my class is glued to their class"

A **mixin** is a Java class you write, tagged with `@Mixin(...)`, that says "everything in here
is about the game's `CharacterMotor` class." It's the container for your hooks and accessors for
that one game class.

```java
@Mixin(assembly = "Assembly-CSharp",
       namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public final class CharacterMotorMixin {
    // everything here targets the game's CharacterMotor
}
```

Think of it like writing an **extension** or a **companion** for a class you don't own.

### Injection = "run my method around their method"

An **injection** is a Java method of yours (a *callback*) that Frostbite runs **before** (`HEAD`)
or **after** (`TAIL`) one of the game's methods. This is the game-modding version of wrapping a
method. From your callback you can watch what's happening, change the inputs, change the output,
or stop the original from running at all.

### Accessor = "let me touch their fields and call their methods"

An **accessor** is a Java method you *declare but don't write* (abstract) — Frostbite writes the
body. One kind reads/writes a field; another calls a method. It's the game-modding version of
reflection, but type-safe and generated for you.

That's the whole philosophy: **your Java classes describe what you want to touch; Frostbite wires
it to the running game.**

---

## 3. Setting up a mod project

A mod is a normal **Android app project** (Gradle) that depends on three prebuilt Frostbite jars.
It does **not** need the Frostbite source. Copy the `frostbite-sample` project and change its
ids, or create a project with this `build.gradle`:

```groovy
plugins { id 'com.android.application' version '9.4.0' }

android {
    namespace 'com.example.mymod'
    compileSdk 37
    defaultConfig {
        applicationId 'com.example.mymod'   // must be unique on the device
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName '1.0.0'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

repositories { google(); mavenCentral() }

dependencies {
    // The API you write against, only at compile time (it lives in the game already):
    compileOnly files('libs/frostbite-api.jar')
    // The annotation processor that turns your @Mixin/@Inject classes into manifests:
    annotationProcessor files('libs/frostbite-processor.jar', 'libs/frostbite-api.jar')
}
```

Put `frostbite-api.jar`, `frostbite-processor.jar`, and `frostbite-host-tools.jar` in a `libs/`
folder (they come from the Frostbite core build). Copy the `packageFrostbite` task from the
sample's `build.gradle` — it turns your built mod into a small `.frostbite` file, which is what
you actually install.

**You need:** JDK 27, Android SDK 37 (with `d8`, `zipalign`, `apksigner`), and — only if you
rebuild the native runtime — NDK 26.1.10909125. Most mod authors never rebuild the runtime; they
reuse `frostbite-runtime.zip`.

Everything in the rest of this guide goes inside `src/main/java/...` of that project.

---

## 4. Lesson 1 — Hello, mod

Every mod has exactly **one** entrypoint: a class that implements `ModInitializer` and is tagged
with `@FrostbiteMod`.

```java
package com.example.mymod;

import dev.frostbite.api.FrostbiteMod;
import dev.frostbite.api.ModContext;
import dev.frostbite.api.ModInitializer;

@FrostbiteMod(id = "my-mod", version = "1.0.0")
public final class MyMod implements ModInitializer {
    @Override public void onLoad(ModContext context) {
        context.log("Hello from my mod!");     // shows in logcat under tag Frostbite/my-mod
    }
}
```

- `id` is your mod's unique name. Lowercase, digits, `.`, `_`, `-`. No two installed mods may
  share an id.
- `onLoad` runs once, when the game starts and loads your mod.
- `context.log(...)` prints to Android's log (logcat).

> **Important rule you'll forget once:** `onLoad` runs **before the game's code is ready**. Do
> **not** read fields or call game methods here — the game isn't warmed up yet. Do that work from
> inside a hook (next lesson), which only fires while the game is actually running. Think of
> `onLoad` as "set up constants and log," nothing that touches the game.

Build it and you have a valid (if boring) mod. Now let's make it do something.

---

## 5. Lesson 2 — Your first hook

We want to run our code whenever the game's `CharacterMotor.UpdateMovement` method runs.
`UpdateMovement` is called every frame while the player is running, so it's a good heartbeat.

```java
package com.example.mymod;

import android.util.Log;
import dev.frostbite.api.At;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;

@Mixin(assembly = "Assembly-CSharp",
       namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public final class CharacterMotorMixin {

    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void beforeUpdate(Object self) {
        Log.i("MyMod", "UpdateMovement is about to run");
    }
}
```

Read it piece by piece:

- `@Mixin(...)` — "this class is about the game's `CharacterMotor`."
- `@Inject(method = "UpdateMovement(self)", at = At.HEAD)` — "run my method **before** the game's
  `UpdateMovement`."
- `public void beforeUpdate(Object self)` — your callback. **Callbacks always return `void`.**
- `Object self` — the specific `CharacterMotor` object this call is happening on (like `this`
  inside the game's method). Every instance-method hook gets `self` as its first parameter.

`At.HEAD` means before; `At.TAIL` means after. Install the mod, play, filter logcat for `MyMod`,
and you'll see the line every frame.

That's a working hook. Everything else is learning **how to name methods precisely** and **what
your callback can do**.

---

## 6. Lesson 3 — Selectors: naming a method

A **selector** is how you point at one exact method. This is the single most important skill, so
go slow.

A game class can have many methods, and several with the same name but different parameters
(**overloads**). To pick one, you write its **canonical signature**:

```
MethodName(receiver[;ParamType[;ParamType...]])
```

- `receiver` is the literal word **`self`** (for an instance/non-static method) or **`static`**
  (for a static method). This is not a real parameter — it's just telling Frostbite whether the
  method belongs to an object or to the class.
- After the receiver, list each real parameter's **type**, separated by `;`.

Parameter types use short lowercase names for the common built-ins, and full `Namespace.Name` for
everything else:

| C# type         | write it as            |
| --------------- | ---------------------- |
| `void`          | `void`                 |
| `bool`          | `boolean`              |
| `int`           | `int`                  |
| `long`          | `long`                 |
| `float`         | `float`                |
| `double`        | `double`               |
| `string`        | `string`               |
| anything else   | full name, e.g. `UnityEngine.Vector3`, `SYBO.RunnerCore.Character.MotorStateKind` |

Examples (read each one out loud):

```
UpdateMovement(self)                                 // instance method, no args
ChangeLane(self;int)                                 // instance method, one int arg
Jump(self;boolean)                                   // instance method, one bool arg
SwitchState(self;SYBO.RunnerCore.Character.MotorStateKind)  // one enum arg
Teleport(self;UnityEngine.Vector3)                   // one struct arg
get_Working(static)                                  // static method, no args
Despawn(static;UnityEngine.Transform)                // static method, one object arg
```

> Note the `get_` prefix. C# **properties** like `bool CanJump { get; }` compile to methods named
> `get_CanJump` / `set_CanJump`. That's why you hook `get_CanJump(self)`, not `CanJump`.

### How do I find the real names?

The game won't tell you its method and field names while running — you discover them ahead of
time by **dumping** the game once with a tool like **Il2CppDumper**. That produces a `dump.cs`
listing every class, method (with parameter types), and field. You read it like a header file,
then write the selector in the canonical form above. You only re-dump when the game updates.

Field names come from the same dump. They look like `_canAccelerate`, or — for an auto-property's
hidden storage — `<AllowInput>k__BackingField`.

### Exact vs regex

Default matching is **exact**: your selector string must equal the game's canonical signature.

If you're unsure of the exact parameter types, use **regex** mode and match loosely. The whole
canonical signature is matched with Java's `Pattern.matches`, so escape parentheses and double
your backslashes:

```java
@Inject(method = "ChangeLane\\(self;int\\)", at = At.HEAD, mode = SelectorMode.REGEX)
public void onLane(Object self, int lane) { }
```

Two rules that save you from silent bugs:

- If your selector matches **more than one** method, Frostbite refuses it (so it never guesses).
  Narrow it.
- If it matches **zero** methods, that fails too. Both are printed in logcat, and that single
  hook is skipped — the rest of your mod still loads.

---

## 7. Lesson 4 — Reading a method's arguments

Your callback's parameters mirror the game method's signature:

1. If the target is an **instance** method (`self`), your **first** parameter is the object, typed
   `Object` (or `Il2CppObject`). A **static** target has no such parameter.
2. Then come the method's real arguments, **in order**, each as the matching Java type.

So for `ChangeLane(self;int)`:

```java
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void onLane(Object self, int lane) {
    Log.i("MyMod", "player wants lane " + lane);   // self = the motor, lane = the argument
}
```

The type mapping is exactly what you'd expect, with three things worth memorizing:

- **enums arrive as `int`** (their underlying number). Declare the parameter `int`.
- **objects and strings** are `Object` (or `Il2CppObject`) — a handle to the game object.
- **structs** (value types like `Vector3`) are also `Object`/`Il2CppObject` — a *boxed copy*. We
  cover structs properly in Lesson 8.

Full table is in the [reference](#14-full-reference). `self` and arguments stay valid **only for
the duration of that callback** — don't stash a game object and poke it ten seconds later.

---

## 8. Lesson 5 — The four powers of a hook

A HEAD hook can do four things. You choose which by what parameters your callback declares. This
is the heart of Frostbite.

### Power 1 — Observe

Just read `self` and the arguments. The original runs normally afterward. (Lesson 2 and 4.)

### Power 2 — Replace the return value (for non-void methods)

Add a `CallbackInfoReturnable<T>` as the **last** parameter. Call `setReturnValue(...)` at HEAD.
The original method is **skipped** and your value is returned instead. `T` matches the method's
return type.

```java
// get_CanJump returns bool. Force it to always say "yes" -> infinite jump.
@Inject(method = "get_CanJump(self)", at = At.HEAD)
public void alwaysCanJump(Object self, CallbackInfoReturnable<Boolean> cir) {
    cir.setReturnValue(Boolean.TRUE);
}
```

If you *don't* call `setReturnValue`, the original runs as usual. So you can decide conditionally:

```java
@Inject(method = "get_IsGameOver(self)", at = At.HEAD)
public void neverLose(Object self, CallbackInfoReturnable<Boolean> cir) {
    cir.setReturnValue(Boolean.FALSE);   // the game can never think it's game over
}
```

### Power 3 — Cancel a void method (NOP it)

A `void` method has no return value, but you may still want to **stop it from running at all** —
a classic "NOP." Add a `CallbackInfo` parameter and call `cancel()`.

```java
// ApplyGravity pulls the player down. NOP it -> no gravity, the player floats.
@Inject(method = "ApplyGravity(self)", at = At.HEAD)
public void noGravity(Object self, CallbackInfo ci) {
    ci.cancel();     // the original ApplyGravity never runs
}
```

(`CallbackInfoReturnable` is itself a `CallbackInfo`, so it can cancel too — `setReturnValue`
both sets the value and cancels.)

### Power 4 — Change the arguments (write-back)

Sometimes you want the original to run, but with **different inputs**. Declare a single
`Arguments` parameter (instead of listing each argument), and set values. Index `0` is the first
real argument (not `self`). Your changes reach the original method and any later hooks.

```java
// Whatever lane the player swipes to, force it to the right lane (1).
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void forceRight(Object self, Arguments args) {
    args.setInt(0, 1);
}
```

`Arguments` can read and write every type: `getInt/setInt`, `getLong/setLong`,
`getFloat/setFloat`, `getDouble/setDouble`, `getBool/setBool`, `getObject/setObject`. You can add
a trailing `CallbackInfo`/`CallbackInfoReturnable` after `Arguments` if you also want to
cancel/override.

### At.TAIL

`At.TAIL` runs your callback **after** the original. Use it to observe results of side effects.
**TAIL only works on `void` methods**, and TAIL callbacks do not get a return value — for a
non-void method you influence the result at HEAD with Power 2.

### Static methods

Use a `(static)` selector. The callback has **no `self`** parameter — otherwise identical:

```java
@Inject(method = "TriggerPoolBalancing(static)", at = At.HEAD)
public void onBalance() { Log.i("MyMod", "pool balancing ran"); }
```

### Priority

If several hooks target the same method, `@Inject(..., priority = N)` orders them — higher `N`
runs first.

---

## 9. Lesson 6 — Accessors: fields and method calls

Hooks react to the game. **Accessors** let you *actively* read/write a field or call a method
whenever you want.

An accessor is an **abstract method** you declare; Frostbite writes the body. Because the class
now has abstract methods, **a mixin with accessors must be `public abstract`** (not `final`).

### Read and write an instance field — `@AccessField`

```java
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public abstract class CharacterMotorMixin {

    @AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
    public abstract boolean getCanAccelerate(Object motor);   // read motor._canAccelerate

    @AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
    public abstract void setCanAccelerate(Object motor, boolean value);  // write it
}
```

- A **getter** takes the object and returns the field's value.
- A **setter** takes the object and the new value, returns `void`.
- `value` is the field name from your dump. `mode` is `GETTER` or `SETTER`.

### Static fields — `@AccessStaticField`

Same, but **no object parameter** (static fields belong to the class, not an instance):

```java
@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.GETTER)
public abstract int getBudget();

@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.SETTER)
public abstract void setBudget(int value);
```

> `const` fields can be **read but not written** (a setter on a constant silently does nothing).

### Call a method — `@AccessMethod`

The selector (same format as hooks) picks the method; `self`/`static` says instance or static.
For an instance method, the first parameter is the object; the rest are the method's arguments.

```java
@AccessMethod("ChangeLane(self;int)")
public abstract boolean changeLane(Object motor, int lane);   // call motor.ChangeLane(lane)

@AccessMethod("get_ForwardSpeedTarget(self)")
public abstract float speed(Object motor);                    // call motor.get_ForwardSpeedTarget()

@AccessMethod("get_Working(static)")
public abstract boolean working();                            // call a static method
```

Now you can call the game's own methods from your Java code. Inside a hook you have `self`, so you
can do, e.g., `setCanAccelerate(self, true)` every frame.

---

## 10. Lesson 7 — The `Il2Cpp` helper and the `Mixins` registry

### Calling accessors from another class — `Mixins`

Frostbite makes **one instance of each mixin** and registers it. To use a mixin's accessors from
anywhere (even a different mixin), ask for it:

```java
CharacterMotorMixin motor = Mixins.get(CharacterMotorMixin.class);
boolean working = Mixins.get(PoolManagerMixin.class).working();
```

For an **instance** accessor you still need the target object (get it from a callback's `self`,
stash it in a field, or fetch it from a static singleton accessor). For **static** accessors you
don't need any object.

### The annotation-free way — `Il2Cpp`

Everything accessors do, you can also do inline without declaring abstract methods, using the
`Il2Cpp` helper plus a `Target(assembly, namespace, class, member)`:

```java
Il2Cpp.Target canAccel = new Il2Cpp.Target(
        "Assembly-CSharp", "SYBO.RunnerCore.Character", "CharacterMotor", "_canAccelerate");

boolean v = Il2Cpp.getBool(self, canAccel);          // read a field
Il2Cpp.setBool(self, canAccel, true);                // write a field

Il2Cpp.Target jump = new Il2Cpp.Target(
        "Assembly-CSharp", "SYBO.RunnerCore.Character", "CharacterMotor", "Jump(self;boolean)");
boolean jumped = Il2Cpp.invokeBool(self, jump, Boolean.TRUE);   // call a method
```

- Field helpers: `getInt/getLong/getFloat/getDouble/getBool/getObject` and matching `setX`.
- Invoke helpers: `invokeVoid/invokeInt/invokeLong/invokeFloat/invokeDouble/invokeBool/invokeObject(self, target, args...)`.
- For a **static** member, pass `null` as `self`.
- For a field `member` is the field name; for `invoke` it's a selector.

Annotations (`@Access*`) are cleaner and type-checked; `Il2Cpp` is handy for one-offs or when you
need to touch a class you don't have a mixin for (like `Vector3` fields).

---

## 11. Lesson 8 — Objects and structs

This is the only genuinely new concept. Spend a minute here.

### `Il2CppObject`

Any game object that crosses into your Java code is an **`Il2CppObject`** — a thin handle holding
the object's native memory address. `self` is one. A method that returns a game object gives you
one. You can declare such parameters/returns as `Object` or `Il2CppObject`.

**Lifetime:** the raw address inside an `Il2CppObject` is valid **only during the current
callback**. Don't stash a bare `Il2CppObject` and poke it many frames later — by then the game may
have moved or freed the object and the pointer dangles. To keep an object past the callback, use
`persist()` (next).

### Keeping an object past the callback — `persist()`

Want to grab a hook's `self` and edit that same game instance on a later frame? Call `persist()`
on the object. You get a `Persistent` — a safe, re-resolvable handle:

```java
private Persistent captured;

@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void onFrame(Object self) {
    if (captured == null) captured = ((Il2CppObject) self).persist();   // grab it once
}

// ...any later frame / any other hook...
Il2CppObject motor = captured.get();     // the live object, or null if the game dropped it
if (motor != null) {
    setCanAccelerate(motor, true);       // edit that exact instance
}
captured.release();                      // free the handle when done
```

- `get()` returns the object if it's **still alive**, otherwise `null`. Re-resolved each call, so
  it's always the current, valid pointer.
- `isAlive()` is a quick liveness check.
- `release()` frees the handle. Idempotent; `get()` returns `null` afterward.

It's backed by a **weak** GC handle: it tracks the object's real liveness without keeping it
alive, so you see exactly what the game sees. That's the right model for a cheat — you hold the
same entity the game holds, and find out honestly when it's gone.

### Value types (structs) — the boxing rule

C# has two flavors of type:

- **Reference types** (classes, strings, arrays): the game passes a *handle* (a pointer). Simple.
- **Value types / structs** (`Vector3`, `Color`, `Quaternion`, enums are a special case): the
  game passes the *whole value by copy*, not a handle.

When a **struct** crosses into Java, Frostbite **boxes** it — wraps the copied value into a
temporary `Il2CppObject` so you have something to hold. So a `Vector3` argument or return arrives
as an `Il2CppObject`.

To read a struct's fields (like `Vector3.x`), you read **fields of that struct type** out of the
boxed object. Make a tiny mixin for the struct, or use `Il2Cpp`:

```java
@Mixin(assembly = "UnityEngine.CoreModule", namespace = "UnityEngine", targetClass = "Vector3")
public abstract class VectorMixin {
    @AccessField(value = "x", mode = AccessMode.GETTER) public abstract float x(Object v);
    @AccessField(value = "y", mode = AccessMode.GETTER) public abstract float y(Object v);
    @AccessField(value = "z", mode = AccessMode.GETTER) public abstract float z(Object v);
    @AccessMethod("get_one(static)") public abstract Il2CppObject one();   // Vector3.one
}
```

Now, given a boxed `Vector3`:

```java
Il2CppObject pos = ...;                       // e.g. returned by a position getter
VectorMixin v = Mixins.get(VectorMixin.class);
float px = v.x(pos), py = v.y(pos), pz = v.z(pos);
```

And you can **return a struct** from an override — supply a boxed struct:

```java
@Inject(method = "get_LastImpactVelocity(self)", at = At.HEAD)
public void zeroImpact(Object self, CallbackInfoReturnable<Object> cir) {
    cir.setReturnValue(Mixins.get(VectorMixin.class).one());   // force the Vector3 return
}
```

Rule of thumb: **if the C# type is a struct, treat it as an `Il2CppObject` and read its fields.**
Object/struct accessor and callback types must be declared `Object` or `Il2CppObject`.

---

## 12. Lesson 9 — Build a real cheat

Let's assemble a "Subway Tools" cheat menu's worth of behavior, using every power. Read the
comments — each line is one thing you learned.

```java
package com.example.mymod;

import android.util.Log;
import dev.frostbite.api.*;

@FrostbiteMod(id = "subway-tools", version = "1.0.0")
public final class SubwayToolsMod implements ModInitializer {
    @Override public void onLoad(ModContext ctx) { ctx.log("Subway Tools loaded"); }
}

@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
abstract class CharacterMotorMixin {

    // --- accessors we'll use from our hooks ---
    @AccessMethod("SetAccelerate(self;boolean)")
    public abstract void setAccelerate(Object motor, boolean on);

    @AccessMethod("set_ColliderEnable(self;boolean)")
    public abstract void setCollider(Object motor, boolean on);

    @AccessMethod("get_ForwardSpeedTarget(self)")
    public abstract float speed(Object motor);

    // --- runs every frame: keep accelerating, god-mode the collider ---
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void each(Object self) {
        setAccelerate(self, true);     // Power: call their method
        setCollider(self, false);      // god mode: no obstacle collisions
    }

    // --- infinite jump: override a bool return ---
    @Inject(method = "get_CanJump(self)", at = At.HEAD)
    public void jump(Object self, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(Boolean.TRUE);
    }

    // --- no gravity: NOP a void method ---
    @Inject(method = "ApplyGravity(self)", at = At.HEAD)
    public void grav(Object self, CallbackInfo ci) {
        ci.cancel();
    }

    // --- always dodge right: rewrite an argument ---
    @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
    public void lane(Object self, Arguments a) {
        a.setInt(0, 1);
    }

    // --- log speed occasionally ---
    private long frames;
    @Inject(method = "UpdateMovement(self)", at = At.TAIL)
    public void tick(Object self) {
        if (++frames % 300 == 1) Log.i("MyMod", "speed=" + speed(self));
    }
}
```

That's a complete, powerful mod. Notice you never wrote a loop over the game, never found an
address, never touched C++. You described methods and fields by name; Frostbite did the rest.

**Everything you can ever do is a combination of these moves:** hook a method (HEAD/TAIL), observe
or override its return or cancel it or edit its args, and read/write fields and call methods via
accessors or `Il2Cpp`. There is no secret fifth thing — once these click, you can write *any*
Frostbite mod.

---

## 13. Build, install, run

1. **Build the mod:** `gradlew packageFrostbite` → `build/outputs/frostbite/<name>.frostbite`.
   (The annotation processor writes `mod.json`, `hooks.json`, and `mixins.json` into it.)
2. **Patch the game** with the desktop app, tab *01 Install Frostbite*: pick the original game
   APK and `frostbite-runtime.zip`, type the launch Activity, choose an output, Build. This
   embeds the runtime and patches the game's startup.
3. **Add your mod** in tab *02 Mods*: add the `.frostbite`, choose an output, Build the final APK.
4. **Install** the final APK (`adb install`). If a copy with a different signature is installed,
   uninstall it first (may wipe its data).
5. **Watch logcat** (`adb logcat | grep -i frostbite`). On launch you'll see your mod load and
   `Installed N IL2CPP hook(s)`. Any hook it couldn't resolve is logged as skipped — the rest
   still run.

Full pipeline detail: [GETTING_STARTED.md](GETTING_STARTED.md).

---

## 14. Full reference

### Annotations

| Annotation | On | Meaning |
| --- | --- | --- |
| `@FrostbiteMod(id, version)` | the entrypoint class | marks your mod; `id` is unique |
| `@Mixin(assembly, namespace, targetClass)` | a class | binds it to one game class |
| `@Inject(method, at, mode?, priority?)` | a `void` method | a hook; `at` = `HEAD`/`TAIL` |
| `@AccessField(value, mode)` | abstract method | instance field get/set |
| `@AccessStaticField(value, mode)` | abstract method | static field get/set |
| `@AccessMethod(selector)` | abstract method | call an instance/static method |

Enums: `At.HEAD` / `At.TAIL`; `SelectorMode.EXACT` / `SelectorMode.REGEX`;
`AccessMode.GETTER` / `AccessMode.SETTER`.

### Callback parameter shapes (for `@Inject`)

- Observe: `(self?, typed args...)`
- Override return: `(self?, typed args..., CallbackInfoReturnable<T>)` — call `setReturnValue`
- NOP a void: `(self?, typed args..., CallbackInfo)` — call `cancel`
- Edit args: `(self?, Arguments)` — call `setX`; may add a trailing `CallbackInfo`/CIR

`self` is present for instance (`self`) targets and absent for static (`static`) targets.

### Persisting objects

| Call | Meaning |
| --- | --- |
| `obj.persist()` → `Persistent` | keep a game object usable past the callback (weak handle) |
| `Persistent.get()` → `Il2CppObject` | the object if still alive, else `null` |
| `Persistent.isAlive()` | liveness check |
| `Persistent.release()` | free the handle |

### Type mapping

| C# / IL2CPP type               | Java type you declare           | Value for `setReturnValue` |
| ------------------------------ | ------------------------------- | -------------------------- |
| `int`, short, byte, char       | `int`                           | `Integer`                  |
| `long`                         | `long`                          | `Long`                     |
| `float`                        | `float`                         | `Float`                    |
| `double`                       | `double`                        | `Double`                   |
| `bool`                         | `boolean`                       | `Boolean`                  |
| enum                           | `int` (underlying value)        | `Integer`                  |
| class / string / array         | `Object` / `Il2CppObject`       | `Il2CppObject` or `null`   |
| struct / value type            | `Object` / `Il2CppObject` (boxed) | `Il2CppObject` (boxed)   |

All types work for fields, invocation, and injection, static or instance, with any number of
arguments.

---

## 15. Every limitation and edge case

- **Only concrete methods can be hooked.** If a method exists only as an **abstract** declaration
  (no body), hooking it crashes the game. Hook the concrete class that implements it.
- **`At.TAIL` is `void`-only**, and TAIL callbacks don't receive the return value. Influence
  non-void results at HEAD.
- **`const` fields are read-only.**
- **Overloads:** `@AccessMethod` picks a method by argument **count**; two overloads with the same
  arity are ambiguous. Use the full selector (it encodes parameter types).
- **Ambiguous / zero-match selectors** are skipped with a warning — narrow them.
- **`Il2CppObject` handles are per-callback** — don't reuse them across frames unless the game
  still holds the object.
- **Struct reads must come from the boxed object** the accessor/invoke returned. Reading a struct
  field from the wrong source gives garbage.
- **Hook capacity:** up to 32 distinct hooked method addresses for the simple path and 32 for the
  universal (complex-signature) path, per process.
- **No live unload** — hooks last for the whole game process.
- **CPU architecture:** `arm64-v8a` is fully supported and tested. The `armeabi-v7a` (32-bit)
  universal path is implemented but **experimental and unverified on a real 32-bit device**; the
  library still loads and simple hooks work there. No x86 library is produced — an x86 emulator
  must translate ARM.
- **Unity version:** the native runtime is pinned to one IL2CPP memory layout (default Unity
  2022.2). A game on a very different Unity version needs the runtime rebuilt to match.
- **Not included:** an on-screen mod-menu/overlay, ESP/drawing, raw arbitrary-address memory
  read/write, and mid-function instruction patching. Frostbite works at method boundaries and by
  name. (A menu/toggle layer can be built *on top* of Frostbite in your mod.)

---

## 16. Troubleshooting

| You see… | What it means / fix |
| --- | --- |
| `Skipping hook … matched N methods` | Selector matched N≠1 methods. Narrow it (exact types) or fix a typo, checking a dump. |
| `Could not hook …` | The signature isn't supported on this ABI, or Dobby failed. Check the types/arity. |
| Callback never fires | The game didn't reach that method, or the assembly/namespace/class/signature don't match this game version. |
| Game crashes immediately on launch | Usually an abstract-method hook, or the runtime doesn't match the game's Unity version. |
| `Frostbite IL2CPP backend not installed` | You called an accessor/invoke too early (e.g. in `onLoad`). Only call from inside a hook. |
| `signatures do not match` on install | A differently signed copy is installed; uninstall it first. |
| Nothing loads at all | The final APK must come from the **Mods** tab (which writes `index.json`), not the bare patched game APK. |
| Garbled numbers from a struct field | You read the field from a non-boxed source; read struct fields from the `Il2CppObject` the accessor/invoke returned. |

---

## 17. Glossary

- **IL2CPP** — Unity's mode that compiles C# to native ARM code (`libil2cpp.so`). The game you're
  modding.
- **Assembly** — a named bundle of classes, e.g. `Assembly-CSharp` (the game's own code) or
  `UnityEngine.CoreModule` (engine types like `Vector3`).
- **Mixin** — your Java class, tagged `@Mixin`, that targets one game class.
- **Hook / injection** — your callback run before (`HEAD`) or after (`TAIL`) a game method.
- **Selector** — the canonical signature string naming one method, e.g. `ChangeLane(self;int)`.
- **`self`** — the game object a hook/accessor acts on (the game method's `this`).
- **Accessor** — an abstract method Frostbite implements to read/write a field or call a method.
- **`Il2CppObject`** — a Java handle to a game object (or a boxed struct); valid for the current
  callback.
- **Boxing** — wrapping a copied struct value into an `Il2CppObject` so Java can hold it.
- **`CallbackInfo` / `CallbackInfoReturnable<T>`** — control objects to cancel a void method or
  replace a return value.
- **`Arguments`** — a control object to read and rewrite a method's arguments.
- **`Mixins`** — the registry; `Mixins.get(SomeMixin.class)` returns the live mixin instance.
- **`Il2Cpp`** — the annotation-free helper for fields and method calls via `Il2Cpp.Target`.
- **BNM / Dobby** — the native libraries Frostbite uses to resolve names and install hooks. You
  never call them directly.
- **`.frostbite`** — your packaged mod (DEX + manifests + any ARM libs), the thing you install.

---

You now know the whole model: name a class with `@Mixin`, run code around its methods with
`@Inject` (observe, override, cancel, or edit args), and read/write its fields and call its
methods with accessors or `Il2Cpp`. Everything else is just picking the right name and the right
power. Go write mods.
