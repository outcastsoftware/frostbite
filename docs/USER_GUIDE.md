---
title: User Guide
nav_order: 2
---

# Frostbite — From Java to Modding Any IL2CPP Game

This is a complete course. If you can read and write ordinary **Java** — classes, methods,
annotations, generics, `interface`, `abstract` — you have everything you need. By the end you will
be able to write **any** Frostbite mod: read and change anything in a Unity game, intercept any
method, and understand exactly what each line does and why.

You will **not** write any C++. You will **not** copy memory addresses. You attach Java code to the
game by **name**, and Frostbite does the dangerous native work for you.

**How this guide is organized**

- **Part A — The mental model:** what modding is, how Frostbite thinks, and the one type you use
  for every game object (`Il2CppObject`).
- **Part B — Set up and first mod:** the project, and the entrypoint.
- **Part C — Hooks:** selectors, callbacks in depth, and the four powers.
- **Part D — Accessors:** when to use `abstract`, fields, methods, and the inline helpers.
- **Part E — Put it together:** a full cheat, and build/install/run.
- **Part F — Reference:** every annotation, limitation, fix, and term.

**Contents**

- [Part A — The mental model](#part-a--the-mental-model)
  - [1. The big idea](#1-the-big-idea)
  - [2. How Frostbite thinks](#2-how-frostbite-thinks)
  - [3. `Il2CppObject`: the one type for everything game-side](#3-il2cppobject-the-one-type-for-everything-game-side)
- [Part B — Set up and first mod](#part-b--set-up-and-first-mod)
  - [4. Setting up a mod project](#4-setting-up-a-mod-project)
  - [5. The entrypoint (Hello, mod)](#5-the-entrypoint-hello-mod)
- [Part C — Hooks](#part-c--hooks)
  - [6. Your first hook](#6-your-first-hook)
  - [7. Selectors: naming a method](#7-selectors-naming-a-method)
  - [8. Callbacks in depth](#8-callbacks-in-depth)
  - [9. The four powers of a hook](#9-the-four-powers-of-a-hook)
- [Part D — Accessors](#part-d--accessors)
  - [10. When to use `abstract`](#10-when-to-use-abstract)
  - [11. Field accessors](#11-field-accessors)
  - [12. Method accessors](#12-method-accessors)
  - [13. The `Il2Cpp` helper and the `Mixins` registry](#13-the-il2cpp-helper-and-the-mixins-registry)
- [Part E — Put it together](#part-e--put-it-together)
  - [14. Build a real cheat](#14-build-a-real-cheat)
  - [15. Build, install, run](#15-build-install-run)
- [Part F — Reference](#part-f--reference)
  - [16. Full reference](#16-full-reference)
  - [17. Every limitation and edge case](#17-every-limitation-and-edge-case)
  - [18. Troubleshooting](#18-troubleshooting)
  - [19. Glossary](#19-glossary)

---

# Part A — The mental model

## 1. The big idea

A Unity game you download is, underneath, a pile of **classes and methods** — exactly like a Java
program. Unity games are usually written in **C#**, which is almost identical to Java (classes,
methods, fields, `this`, inheritance). When the game is built for Android with a mode called
**IL2CPP**, that C# is translated into native ARM machine code and shipped inside the APK as a big
library called `libil2cpp.so`.

So the game is running C#-shaped code, compiled to machine code. **Modding** means changing what
that code does — making the player invincible, giving infinite coins, forcing a function to do
nothing, and so on.

There are three things you ever want to do to someone else's code:

| You want to…                           | In plain Java terms                             | Frostbite tool |
| -------------------------------------- | ----------------------------------------------- | -------------- |
| Run your code when *their* method runs | Wrapping a method with before/after logic        | **Injection** (`@Inject`) |
| Read or change *their* fields          | `obj.someField` / `obj.someField = x`           | **Field accessor** (`@AccessField`) |
| Call *their* method yourself           | `obj.someMethod(args)`                          | **Method accessor** (`@AccessMethod`) |

Frostbite gives you all three, in Java, by **name**. You say "the class `CharacterMotor` in
assembly `Assembly-CSharp`, its method `Jump`" and Frostbite finds it in the running game for you.
You never touch an address.

> **Why is this hard normally?** The game has no names at runtime — IL2CPP strips them into a
> metadata blob. Normal mod tools make you dump the game, find numeric offsets, and rewrite them
> every time the game updates. Frostbite uses a library called **BNM** that reads that metadata and
> resolves names at runtime, and a library called **Dobby** to install the hooks. You ignore both.

## 2. How Frostbite thinks

Three words to internalize. They map onto Java concepts you already know.

### Mixin = "my class is glued to their class"

A **mixin** is a Java class you write, tagged with `@Mixin(...)`, that says "everything in here is
about the game's `CharacterMotor` class." It's the container for your hooks and accessors for that
one game class.

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

An **injection** is a Java method of yours (a *callback*) that Frostbite runs **before** (`HEAD`) or
**after** (`TAIL`) one of the game's methods. This is the game-modding version of wrapping a method.
From your callback you can watch what's happening, change the inputs, change the output, or stop the
original from running at all.

### Accessor = "let me touch their fields and call their methods"

An **accessor** is a Java method you *declare but don't write* (abstract) — Frostbite writes the
body. One kind reads/writes a field; another calls a method. It's the game-modding version of
reflection, but type-safe and generated for you.

That's the whole philosophy: **your Java classes describe what you want to touch; Frostbite wires it
to the running game.**

## 3. `Il2CppObject`: the one type for everything game-side

Before any hooks, learn the one type that appears in almost every mod. Getting this right up front
makes the rest obvious.

### 3.1 What it is

Any **game object** that crosses into your Java code is an **`Il2CppObject`** — a thin Java handle
that holds the object's address inside the running game. It is the Java stand-in for a C# object.

You get an `Il2CppObject` whenever the game hands you something that isn't a plain number or bool:

- the `self` of an instance hook (the game method's `this`),
- a method that **returns** a game object or a struct,
- an **object or struct argument** of a hooked method,
- the value of an **object field** you read.

### 3.2 The rule: always declare `Il2CppObject`, never bare `Object`

Frostbite will accept a parameter typed `java.lang.Object` and inject the same handle — but **always
write `Il2CppObject`**. Three concrete reasons:

1. **It says what it is.** `Il2CppObject self` tells a reader "this is a game object," where
   `Object self` says nothing.
2. **It unlocks methods with no cast.** `self.persist()`, `self.nativeAddress()`,
   `a.equals(b)` only exist on `Il2CppObject`. With `Object` you must cast first every time.
3. **It keeps your code honest.** Using one type for every game object means you never confuse a
   game handle with a Java `Integer`, `String`, or `null` sentinel.

```java
// Do this:
public void onFrame(Il2CppObject self) { ... }

// Not this:
public void onFrame(Object self) { ... }   // works, but loses the type and needs casts later
```

This holds **everywhere** a game object appears: hook `self`, hook object/struct arguments, accessor
object parameters, accessor object returns, and `CallbackInfoReturnable<Il2CppObject>` for an object
or struct return override.

### 3.3 What you can do with one

```java
long addr   = self.nativeAddress();   // the raw game address (rarely needed directly)
Persistent p = self.persist();        // keep it usable past this callback (see 3.6)
```

To read or change the object's **fields**, or **call its methods**, you pass the `Il2CppObject` into
an accessor or the `Il2Cpp` helper — covered in Part D. You never poke its memory yourself.

### 3.4 Lifetime: a handle is valid only during its callback

The address inside an `Il2CppObject` is live **only for the duration of the callback that gave it to
you**. After the callback returns, the game may move or free that object, and the handle dangles.

```java
private Il2CppObject saved;

@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void bad(Il2CppObject self) {
    saved = self;          // WRONG: a frame later this may point at freed memory
}
```

Inside the same callback, pass `self` around freely. To keep it for **later**, don't save the raw
handle — persist it.

### 3.5 Keep an object past the callback — `persist()`

Call `persist()` on any `Il2CppObject` to get a **`Persistent`**: a safe, re-resolvable handle you
*can* store in a field and use on a later frame or from another hook.

```java
private Persistent captured;

@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void onFrame(Il2CppObject self) {
    if (captured == null) captured = self.persist();   // grab it once
}

// ...any later frame / any other hook...
Il2CppObject motor = captured.get();    // the live object, or null if the game dropped it
if (motor != null) setCanAccelerate(motor, true);   // edit that exact instance
captured.release();                     // free the handle when done
```

- `get()` returns the object if it's **still alive**, otherwise `null`. Re-resolved each call, so
  it's always a valid, current handle.
- `isAlive()` is a quick liveness check.
- `release()` frees the handle. Idempotent; `get()` returns `null` afterward.

It's backed by a **weak** GC handle: it tracks the object's real liveness without keeping it alive,
so you see exactly what the game sees. That's the right model for a cheat — you hold the same entity
the game holds, and find out honestly when it's gone.

### 3.6 Boxed structs are `Il2CppObject`s too

C# has two flavors of type, and the difference matters once:

- **Reference types** (classes, strings, arrays): the game passes a *handle* (a pointer). An
  `Il2CppObject` wraps that handle directly.
- **Value types / structs** (`Vector3`, `Color`, `Quaternion`): the game passes the *whole value by
  copy*, not a handle.

When a **struct** crosses into Java, Frostbite **boxes** it — copies the value into a fresh
`Il2CppObject` so you have something to hold. So a `Vector3` argument, return, or field value arrives
as an `Il2CppObject`, same as any other game object. You read its fields the same way.

```java
// A tiny mixin for Vector3 lets you read a boxed Vector3's fields.
@Mixin(assembly = "UnityEngine.CoreModule", namespace = "UnityEngine", targetClass = "Vector3")
public abstract class VectorMixin {
    @AccessField(value = "x", mode = AccessMode.GETTER) public abstract float x(Il2CppObject v);
    @AccessField(value = "y", mode = AccessMode.GETTER) public abstract float y(Il2CppObject v);
    @AccessField(value = "z", mode = AccessMode.GETTER) public abstract float z(Il2CppObject v);
    @AccessMethod("get_one(static)") public abstract Il2CppObject one();   // Vector3.one
}
```

```java
Il2CppObject pos = worldPos(self);            // a boxed Vector3
VectorMixin v = Mixins.get(VectorMixin.class);
float px = v.x(pos), py = v.y(pos), pz = v.z(pos);
```

**Rule of thumb:** if the C# type is a struct, treat it as an `Il2CppObject` and read its fields out
of the boxed copy. You never need a bare `Object` anywhere.

> **One subtlety (rarely hits you):** read a struct's fields from the *boxed object the accessor or
> invoke handed you*, not from some other source. Reading a struct field off the wrong handle gives
> garbage. If you always pass the exact `Il2CppObject` you received, you're fine.

---

# Part B — Set up and first mod

## 4. Setting up a mod project

A mod is a normal **Android app project** (Gradle) that depends on three prebuilt Frostbite jars. It
does **not** need the Frostbite source. Copy the `frostbite-sample` project and change its ids, or
create a project with this `build.gradle`:

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
folder (they come from the Frostbite core build). Copy the `packageFrostbite` task from the sample's
`build.gradle` — it turns your built mod into a small `.frostbite` file, which is what you install.

**You need:** JDK 27, Android SDK 37 (with `d8`, `zipalign`, `apksigner`), and — only if you rebuild
the native runtime — NDK 26.1.10909125. Most mod authors never rebuild the runtime; they reuse
`frostbite-runtime.zip`.

Everything in the rest of this guide goes inside `src/main/java/...` of that project.

## 5. The entrypoint (Hello, mod)

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

- `id` is your mod's unique name. Lowercase, digits, `.`, `_`, `-`. No two installed mods may share
  an id.
- `onLoad` runs once, when the game starts and loads your mod.
- `context.log(...)` prints to Android's log (logcat).

> **Important rule you'll forget once:** `onLoad` runs **before the game's code is ready**. Do
> **not** read fields or call game methods here — the game isn't warmed up yet, and you'll get
> `Frostbite IL2CPP backend not installed`. Do that work from inside a hook (next part), which only
> fires while the game is actually running. Think of `onLoad` as "set up constants and log."

Build it and you have a valid (if boring) mod. Now make it do something.

---

# Part C — Hooks

## 6. Your first hook

Run our code whenever the game's `CharacterMotor.UpdateMovement` runs. `UpdateMovement` is called
every frame while the player is running, so it's a good heartbeat.

```java
package com.example.mymod;

import android.util.Log;
import dev.frostbite.api.At;
import dev.frostbite.api.Il2CppObject;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;

@Mixin(assembly = "Assembly-CSharp",
       namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public final class CharacterMotorMixin {

    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void beforeUpdate(Il2CppObject self) {
        Log.i("MyMod", "UpdateMovement is about to run");
    }
}
```

Read it piece by piece:

- `@Mixin(...)` — "this class is about the game's `CharacterMotor`."
- `@Inject(method = "UpdateMovement(self)", at = At.HEAD)` — "run my method **before** the game's
  `UpdateMovement`."
- `public void beforeUpdate(Il2CppObject self)` — your callback. **Callbacks always return `void`.**
- `Il2CppObject self` — the specific `CharacterMotor` this call is happening on (like `this` inside
  the game's method). Every instance-method hook gets `self` as its first parameter. (Declare it
  `Il2CppObject`, per §3.2.)

`At.HEAD` means before; `At.TAIL` means after. Install the mod, play, filter logcat for `MyMod`, and
you'll see the line every frame.

That's a working hook. The rest of Part C is learning **how to name methods precisely** and **what
your callback can do**.

## 7. Selectors: naming a method

A **selector** is how you point at one exact method. This is the single most important skill, so go
slow.

### 7.1 The canonical signature

A game class can have many methods, and several with the same name but different parameters
(**overloads**). To pick one, you write its **canonical signature**:

```
MethodName(receiver[;ParamType[;ParamType...]])
```

- `receiver` is the literal word **`self`** (for an instance/non-static method) or **`static`** (for
  a static method). It is not a real parameter — it just tells Frostbite whether the method belongs
  to an object or to the class.
- After the receiver, list each real parameter's **type**, separated by `;`.

### 7.2 Parameter type names

Short lowercase names for the common built-ins, full `Namespace.Name` for everything else:

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

Examples (read each out loud):

```
UpdateMovement(self)                                 // instance method, no args
ChangeLane(self;int)                                 // instance method, one int arg
Jump(self;boolean)                                   // instance method, one bool arg
SwitchState(self;SYBO.RunnerCore.Character.MotorStateKind)  // one enum arg
Teleport(self;UnityEngine.Vector3)                   // one struct arg
get_Working(static)                                  // static method, no args
Despawn(static;UnityEngine.Transform)                // static method, one object arg
```

### 7.3 Properties compile to `get_` / `set_`

C# **properties** like `bool CanJump { get; }` compile to methods named `get_CanJump` /
`set_CanJump`. So you hook `get_CanJump(self)`, not `CanJump`. This trips up everyone once.

### 7.4 How do I find the real names?

The game won't tell you its method and field names while running — you discover them ahead of time
by **dumping** the game once with a tool like **Il2CppDumper**. That produces a `dump.cs` listing
every class, method (with parameter types), and field. You read it like a header file, then write
the selector in the canonical form above. You only re-dump when the game updates.

Field names come from the same dump. They look like `_canAccelerate`, or — for an auto-property's
hidden storage — `<AllowInput>k__BackingField`.

### 7.5 Exact vs regex

Default matching is **exact**: your selector string must equal the game's canonical signature.

If you're unsure of the exact parameter types, use **regex** mode and match loosely. The whole
canonical signature is matched with Java's `Pattern.matches`, so escape parentheses and double your
backslashes:

```java
@Inject(method = "ChangeLane\\(self;int\\)", at = At.HEAD, mode = SelectorMode.REGEX)
public void onLane(Il2CppObject self, int lane) { }
```

### 7.6 The two safety rules

- If your selector matches **more than one** method, Frostbite refuses it (it never guesses).
  Narrow it.
- If it matches **zero** methods, that fails too.

Both are printed in logcat, and that single hook is skipped — the rest of your mod still loads.

## 8. Callbacks in depth

A **callback** is the Java method you write for `@Inject`. Everything a hook does is decided by its
callback's **shape** — its parameters and their types. Master this section and hooks hold no
surprises.

### 8.1 What a callback is (and isn't)

Frostbite installs a trampoline on the game method. When the game calls that method, your callback
runs first (`HEAD`) or last (`TAIL`). Your callback is not a replacement for the game method — it
runs *alongside* it. You influence the original through **control objects** (§8.5), not by returning
a value.

### 8.2 The signature-mirror rule

Your callback's parameters **mirror the game method's signature**, in this order:

1. **`self`** — present only for an **instance** (`self`) target; a **static** target has none.
2. the method's **real arguments**, in order, each as the matching Java type (§8.4) — *or* a single
   **`Arguments`** standing in for all of them.
3. an optional trailing **control object** (`CallbackInfo` or `CallbackInfoReturnable<T>`).

Everything is optional except what you actually use. You can observe just `self`, or `self` plus one
argument, or `self` plus a control object — list only what you need, but keep the order.

### 8.3 `self` first — or not at all

```java
// instance target: first param is the object
@Inject(method = "UpdateMovement(self)", at = At.HEAD)
public void a(Il2CppObject self) { }

// static target: no self
@Inject(method = "TriggerPoolBalancing(static)", at = At.HEAD)
public void b() { }
```

`self` is always the first parameter for an instance hook, always typed `Il2CppObject`, and is the
game method's `this`.

### 8.4 Argument order and types

List the game method's real arguments after `self`, in order. Each maps to a Java type:

| C# / IL2CPP arg          | declare it as     |
| ------------------------ | ----------------- |
| `int`, short, byte, char | `int`             |
| `long`                   | `long`            |
| `float`                  | `float`           |
| `double`                 | `double`          |
| `bool`                   | `boolean`         |
| enum                     | `int` (its underlying number) |
| class / string / array   | `Il2CppObject`    |
| struct / value type      | `Il2CppObject` (boxed — see §3.6) |

```java
// ChangeLane(self;int): self, then the int argument
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void onLane(Il2CppObject self, int lane) {
    Log.i("MyMod", "player wants lane " + lane);
}

// a struct argument arrives as a boxed Il2CppObject
@Inject(method = "AdjustVelocityToSurface(self;UnityEngine.Vector3)", at = At.HEAD)
public void onVel(Il2CppObject self, Il2CppObject velocity) { /* read velocity's fields */ }
```

You may also **skip listing the arguments** and take a single `Arguments` instead — do that when you
want to *rewrite* them (§9, Power 4).

### 8.5 The three control objects

A control object is an extra parameter that lets you influence the original. Add the one you need as
the **last** parameter.

| Control object | Use it to | Key call |
| --- | --- | --- |
| `CallbackInfo` | cancel a **void** method (NOP it) | `cancel()` |
| `CallbackInfoReturnable<T>` | replace a **non-void** return, or cancel | `setReturnValue(T)` |
| `Arguments` | read/rewrite the arguments | `getX(i)` / `setX(i, v)` |

`CallbackInfoReturnable<T>` **is a** `CallbackInfo`, so it can cancel too (`setReturnValue` both sets
the value *and* cancels the original). `T` is the return type as a boxed Java type
(`Boolean`, `Integer`, `Il2CppObject`, …).

### 8.6 HEAD vs TAIL — the lifecycle

```
   call arrives
        │
   ┌────▼─────┐   your HEAD callbacks run (highest priority first)
   │   HEAD   │   — may observe, override return, cancel, or edit args
   └────┬─────┘
        │   if not cancelled:
   ┌────▼─────┐
   │ original │   the game's own method body
   └────┬─────┘
        │
   ┌────▼─────┐   your TAIL callbacks run (void targets only)
   │   TAIL   │   — observe side effects
   └────┬─────┘
        │
     returns
```

- **HEAD** is where all the power is: observe, override, cancel, edit args — all happen before the
  original.
- **TAIL** runs after the original. Use it to see the results of side effects. **TAIL works on
  `void` methods only**, and a TAIL callback does **not** receive the return value — influence a
  non-void result at HEAD.

### 8.7 A callback always returns `void`

No matter what the game method returns, your callback is `void`. You never `return` a value from a
callback — you set it through `CallbackInfoReturnable.setReturnValue(...)`. A common first mistake:

```java
// WRONG — callbacks don't return the game value
public boolean alwaysCanJump(Il2CppObject self) { return true; }

// RIGHT
public void alwaysCanJump(Il2CppObject self, CallbackInfoReturnable<Boolean> cir) {
    cir.setReturnValue(Boolean.TRUE);
}
```

### 8.8 Multiple callbacks and priority

Several hooks may target the same method — even across different mods. They run in priority order;
`@Inject(..., priority = N)` sets it, higher `N` first. An earlier HEAD callback that cancels or
overrides is visible to later ones (they can check `isCancelled()`).

### 8.9 Callback shapes — cheat sheet

```java
// observe
(Il2CppObject self, <typed args...>)
// override a non-void return (or cancel)
(Il2CppObject self, <typed args...>, CallbackInfoReturnable<T> cir)
// NOP a void method
(Il2CppObject self, <typed args...>, CallbackInfo ci)
// rewrite arguments (optionally also cancel/override)
(Il2CppObject self, Arguments args[, CallbackInfo|CallbackInfoReturnable<T>])
// static target: drop self from any of the above
```

## 9. The four powers of a hook

A HEAD hook can do four things. You choose which by the callback's shape (§8). This is the heart of
Frostbite.

### Power 1 — Observe

Just read `self` and the arguments. The original runs normally afterward.

```java
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void watch(Il2CppObject self, int lane) {
    Log.i("MyMod", "player wants lane " + lane);
}
```

### Power 2 — Replace the return value (non-void methods)

Add a `CallbackInfoReturnable<T>` as the **last** parameter and call `setReturnValue(...)` at HEAD.
The original is **skipped** and your value is returned instead.

```java
// get_CanJump returns bool. Force it to always say "yes" -> infinite jump.
@Inject(method = "get_CanJump(self)", at = At.HEAD)
public void alwaysCanJump(Il2CppObject self, CallbackInfoReturnable<Boolean> cir) {
    cir.setReturnValue(Boolean.TRUE);
}
```

If you *don't* call `setReturnValue`, the original runs as usual — so you can decide conditionally.
To override an **object or struct** return, use `CallbackInfoReturnable<Il2CppObject>` and hand back
an `Il2CppObject` (for a struct, a boxed one):

```java
@Inject(method = "get_LastImpactVelocity(self)", at = At.HEAD)
public void zeroImpact(Il2CppObject self, CallbackInfoReturnable<Il2CppObject> cir) {
    cir.setReturnValue(Mixins.get(VectorMixin.class).one());   // force the Vector3 return
}
```

### Power 3 — Cancel a void method (NOP it)

A `void` method has no return, but you may still want to **stop it from running**. Add a
`CallbackInfo` and call `cancel()`.

```java
// ApplyGravity pulls the player down. NOP it -> no gravity, the player floats.
@Inject(method = "ApplyGravity(self)", at = At.HEAD)
public void noGravity(Il2CppObject self, CallbackInfo ci) {
    ci.cancel();     // the original ApplyGravity never runs
}
```

### Power 4 — Change the arguments (write-back)

Run the original, but with **different inputs**. Declare a single `Arguments` parameter (instead of
listing each argument) and set values. Index `0` is the first real argument (not `self`). Your
changes reach the original and any later hooks.

```java
// Whatever lane the player swipes to, force it to the right lane (1).
@Inject(method = "ChangeLane(self;int)", at = At.HEAD)
public void forceRight(Il2CppObject self, Arguments args) {
    args.setInt(0, 1);
}
```

`Arguments` reads and writes every type: `getInt/setInt`, `getLong/setLong`, `getFloat/setFloat`,
`getDouble/setDouble`, `getBool/setBool`, and `getObject/setObject` (objects and boxed structs as
`Il2CppObject`). Add a trailing `CallbackInfo`/`CallbackInfoReturnable` after `Arguments` if you also
want to cancel or override.

> **There is no secret fifth power.** Everything you can ever do is a combination of these moves plus
> accessors (Part D).

---

# Part D — Accessors

## 10. When to use `abstract`

The word "abstract" shows up in two completely different places. Keep them straight.

### 10.1 Abstract methods = accessors you declare and Frostbite writes

An **accessor** is a method with no body that you let Frostbite implement: a field getter/setter or a
method invoker. Because the method has no body, it must be `abstract`, and a class with abstract
methods must itself be `abstract`.

> **Rule:** a mixin that has any `@AccessField` / `@AccessStaticField` / `@AccessMethod` must be
> `public abstract class`. A mixin with only `@Inject` hooks (which have bodies) can be `final`.

```java
// hooks only -> final is fine
@Mixin(...) public final class HooksOnlyMixin {
    @Inject(...) public void h(Il2CppObject self) { ... }
}

// has accessors -> must be abstract
@Mixin(...) public abstract class WithAccessors {
    @AccessField(value = "_hp", mode = AccessMode.GETTER)
    public abstract int hp(Il2CppObject self);        // no body: Frostbite writes it
}
```

You never write the body and you never subclass it yourself — Frostbite generates a concrete
implementation and registers one instance (see §13). In practice, **make every mixin `abstract`** and
stop thinking about it; it costs nothing and lets you add accessors any time.

### 10.2 Never hook an **abstract game method**

This is the other, unrelated "abstract." Some **game** methods are declared `abstract` in C# (a base
class says a method exists but has no body; subclasses implement it). Those have no real code to
hook, and hooking one **crashes the game**.

> **Rule:** hook the **concrete** class that actually implements the behavior, not an abstract base.
> If a hook crashes the game on launch, an abstract-method target is the first suspect. Pick the
> subclass whose `dump.cs` shows a real method body.

The two rules don't interact: one is about *your* Java class, the other about the *game's* method.

## 11. Field accessors

Hooks react to the game. **Accessors** let you *actively* read/write a field or call a method
whenever you want.

### 11.1 Instance fields — `@AccessField`

```java
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character",
       targetClass = "CharacterMotor")
public abstract class CharacterMotorMixin {

    @AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
    public abstract boolean getCanAccelerate(Il2CppObject motor);   // read motor._canAccelerate

    @AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
    public abstract void setCanAccelerate(Il2CppObject motor, boolean value);  // write it
}
```

- A **getter** takes the object and returns the field's value.
- A **setter** takes the object and the new value, returns `void`.
- `value` is the field name from your dump. `mode` is `GETTER` or `SETTER`.
- The object parameter is `Il2CppObject` (§3.2).

### 11.2 Static fields — `@AccessStaticField`

Same, but **no object parameter** (static fields belong to the class):

```java
@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.GETTER)
public abstract int getBudget();

@AccessStaticField(value = "BalancingBudgetMS", mode = AccessMode.SETTER)
public abstract void setBudget(int value);
```

### 11.3 `const` fields are read-only

A `const` field can be **read but not written** — a setter on a constant silently does nothing.

## 12. Method accessors

`@AccessMethod` calls a game method. The selector (same format as hooks, §7) picks it; `self` /
`static` says instance or static. For an instance method, the first parameter is the object; the rest
are the method's arguments.

```java
@AccessMethod("ChangeLane(self;int)")
public abstract boolean changeLane(Il2CppObject motor, int lane);   // motor.ChangeLane(lane)

@AccessMethod("get_ForwardSpeedTarget(self)")
public abstract float speed(Il2CppObject motor);                    // motor.get_ForwardSpeedTarget()

@AccessMethod("get_WorldPlacerPosition(self)")
public abstract Il2CppObject worldPos(Il2CppObject motor);          // returns a boxed Vector3

@AccessMethod("get_Working(static)")
public abstract boolean working();                                  // a static method, no object
```

Object and struct parameters and returns are `Il2CppObject`. Inside a hook you have `self`, so you
can call, e.g., `setCanAccelerate(self, true)` every frame.

## 13. The `Il2Cpp` helper and the `Mixins` registry

### 13.1 Calling accessors from another class — `Mixins`

Frostbite makes **one instance of each mixin** and registers it. To use a mixin's accessors from
anywhere (even a different mixin), ask for it:

```java
CharacterMotorMixin motor = Mixins.get(CharacterMotorMixin.class);
boolean working = Mixins.get(PoolManagerMixin.class).working();
```

For an **instance** accessor you still need the target object — get it from a callback's `self`,
stash it with `persist()` (§3.5), or fetch it from a static singleton accessor. For **static**
accessors you need no object.

### 13.2 The annotation-free way — `Il2Cpp`

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
- `getObject` / `invokeObject` return an `Il2CppObject`; pass `Il2CppObject` arguments the same way.
- For a **static** member, pass `null` as `self`.
- For a field, `member` is the field name; for `invoke`, it's a selector.

Annotations (`@Access*`) are cleaner and type-checked; `Il2Cpp` is handy for one-offs or a class you
don't have a mixin for (like `Vector3` fields).

---

# Part E — Put it together

## 14. Build a real cheat

A "Subway Tools" cheat menu's worth of behavior, using every power. Every game object is an
`Il2CppObject`. Read the comments — each line is one thing you learned.

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
abstract class CharacterMotorMixin {        // abstract: it has accessors

    // --- accessors we'll use from our hooks (object params are Il2CppObject) ---
    @AccessMethod("SetAccelerate(self;boolean)")
    public abstract void setAccelerate(Il2CppObject motor, boolean on);

    @AccessMethod("set_ColliderEnable(self;boolean)")
    public abstract void setCollider(Il2CppObject motor, boolean on);

    @AccessMethod("get_ForwardSpeedTarget(self)")
    public abstract float speed(Il2CppObject motor);

    // --- runs every frame: keep accelerating, god-mode the collider ---
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void each(Il2CppObject self) {
        setAccelerate(self, true);     // Power: call their method
        setCollider(self, false);      // god mode: no obstacle collisions
    }

    // --- infinite jump: override a bool return ---
    @Inject(method = "get_CanJump(self)", at = At.HEAD)
    public void jump(Il2CppObject self, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(Boolean.TRUE);
    }

    // --- no gravity: NOP a void method ---
    @Inject(method = "ApplyGravity(self)", at = At.HEAD)
    public void grav(Il2CppObject self, CallbackInfo ci) {
        ci.cancel();
    }

    // --- always dodge right: rewrite an argument ---
    @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
    public void lane(Il2CppObject self, Arguments a) {
        a.setInt(0, 1);
    }

    // --- log speed occasionally ---
    private long frames;
    @Inject(method = "UpdateMovement(self)", at = At.TAIL)
    public void tick(Il2CppObject self) {
        if (++frames % 300 == 1) Log.i("MyMod", "speed=" + speed(self));
    }
}
```

That's a complete, powerful mod. You never wrote a loop over the game, never found an address, never
touched C++, and never used a bare `Object`. You described methods and fields by name; Frostbite did
the rest.

**Everything you can ever do is a combination of these moves:** hook a method (HEAD/TAIL), observe or
override its return or cancel it or edit its args, and read/write fields and call methods via
accessors or `Il2Cpp`. Once these click, you can write *any* Frostbite mod.

## 15. Build, install, run

1. **Build the mod:** `gradlew packageFrostbite` → `build/outputs/frostbite/<name>.frostbite`.
   (The annotation processor writes `mod.json`, `hooks.json`, and `mixins.json` into it.)
2. **Patch the game** with the desktop app, tab *01 Install Frostbite*: pick the original game APK
   and `frostbite-runtime.zip`, type the launch Activity, choose an output, Build. This embeds the
   runtime and patches the game's startup.
3. **Add your mod** in tab *02 Mods*: add the `.frostbite`, choose an output, Build the final APK.
4. **Install** the final APK (`adb install`). If a copy with a different signature is installed,
   uninstall it first (may wipe its data).
5. **Watch logcat** (`adb logcat | grep -i frostbite`). On launch you'll see your mod load and
   `Installed N IL2CPP hook(s)`. Any hook it couldn't resolve is logged as skipped — the rest run.

Full pipeline detail: [GETTING_STARTED.md](GETTING_STARTED.md).

---

# Part F — Reference

## 16. Full reference

### 16.1 Annotations

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

### 16.2 Callback parameter shapes (for `@Inject`)

- Observe: `(Il2CppObject self?, typed args...)`
- Override return: `(Il2CppObject self?, typed args..., CallbackInfoReturnable<T>)` — `setReturnValue`
- NOP a void: `(Il2CppObject self?, typed args..., CallbackInfo)` — `cancel`
- Edit args: `(Il2CppObject self?, Arguments)` — `setX`; may add a trailing `CallbackInfo`/CIR

`self` is present for instance (`self`) targets and absent for static (`static`) targets.

### 16.3 Objects and persistence

| Call | Meaning |
| --- | --- |
| declare every game object as `Il2CppObject` | self, object/struct args, object/struct returns, object fields |
| `obj.nativeAddress()` → `long` | the raw game address (rarely needed) |
| `obj.persist()` → `Persistent` | keep a game object usable past the callback (weak handle) |
| `Persistent.get()` → `Il2CppObject` | the object if still alive, else `null` |
| `Persistent.isAlive()` | liveness check |
| `Persistent.release()` | free the handle |

### 16.4 Type mapping

| C# / IL2CPP type               | Java type you declare         | Value for `setReturnValue` |
| ------------------------------ | ----------------------------- | -------------------------- |
| `int`, short, byte, char       | `int`                         | `Integer`                  |
| `long`                         | `long`                        | `Long`                     |
| `float`                        | `float`                       | `Float`                    |
| `double`                       | `double`                      | `Double`                   |
| `bool`                         | `boolean`                     | `Boolean`                  |
| enum                           | `int` (underlying value)      | `Integer`                  |
| class / string / array         | `Il2CppObject`                | `Il2CppObject` or `null`   |
| struct / value type            | `Il2CppObject` (boxed)        | `Il2CppObject` (boxed)     |

All types work for fields, invocation, and injection, static or instance, with any number of
arguments. (A bare `Object` is accepted wherever `Il2CppObject` is, but prefer `Il2CppObject` — §3.2.)

## 17. Every limitation and edge case

- **Only concrete methods can be hooked.** Hooking an **abstract game method** crashes the game
  (§10.2). Hook the concrete class that implements it.
- **`At.TAIL` is `void`-only**, and TAIL callbacks don't receive the return value. Influence non-void
  results at HEAD.
- **A callback always returns `void`** — set the game's return via `CallbackInfoReturnable`, never by
  returning it (§8.7).
- **A mixin with accessors must be `abstract`** (§10.1); a hooks-only mixin may be `final`.
- **`const` fields are read-only.**
- **Overloads:** `@AccessMethod` picks a method by argument **count**; two overloads with the same
  arity are ambiguous. Use the full selector (it encodes parameter types).
- **Ambiguous / zero-match selectors** are skipped with a warning — narrow them.
- **`Il2CppObject` handles are per-callback** — don't stash a bare one across frames; use `persist()`
  (§3.5).
- **Struct reads must come from the boxed object** the accessor/invoke returned (§3.6). Reading a
  struct field from the wrong source gives garbage.
- **Hook capacity:** up to 32 distinct hooked method addresses for the simple path and 32 for the
  universal (complex-signature) path, per process.
- **No live unload** — hooks last for the whole game process.
- **CPU architecture:** `arm64-v8a` is fully supported and tested. The `armeabi-v7a` (32-bit)
  universal path is implemented but **experimental and unverified on a real 32-bit device**; the
  library still loads and simple hooks work there. No x86 library is produced — an x86 emulator must
  translate ARM.
- **Unity version:** the native runtime is pinned to one IL2CPP memory layout (default Unity 2022.2).
  A game on a very different Unity version needs the runtime rebuilt to match.
- **Not included:** an on-screen mod-menu/overlay, ESP/drawing, raw arbitrary-address memory
  read/write, and mid-function instruction patching. Frostbite works at method boundaries and by
  name. (A menu/toggle layer can be built *on top* of Frostbite in your mod.)

## 18. Troubleshooting

| You see… | What it means / fix |
| --- | --- |
| `Skipping hook … matched N methods` | Selector matched N≠1 methods. Narrow it (exact types) or fix a typo, checking a dump. |
| `Could not hook …` | The signature isn't supported on this ABI, or Dobby failed. Check the types/arity. |
| Callback never fires | The game didn't reach that method, or the assembly/namespace/class/signature don't match this game version. |
| Game crashes immediately on launch | Usually an abstract-method hook (§10.2), or the runtime doesn't match the game's Unity version. |
| `Frostbite IL2CPP backend not installed` | You called an accessor/invoke too early (e.g. in `onLoad`). Only call from inside a hook. |
| `signatures do not match` on install | A differently signed copy is installed; uninstall it first. |
| Nothing loads at all | The final APK must come from the **Mods** tab (which writes `index.json`), not the bare patched game APK. |
| Garbled numbers from a struct field | You read the field from a non-boxed source; read struct fields from the `Il2CppObject` the accessor/invoke returned. |
| A stashed object points at garbage next frame | You saved a bare `Il2CppObject`; use `persist()` and `get()` instead (§3.5). |

## 19. Glossary

- **IL2CPP** — Unity's mode that compiles C# to native ARM code (`libil2cpp.so`). The game you're
  modding.
- **Assembly** — a named bundle of classes, e.g. `Assembly-CSharp` (the game's own code) or
  `UnityEngine.CoreModule` (engine types like `Vector3`).
- **Mixin** — your Java class, tagged `@Mixin`, that targets one game class.
- **Hook / injection** — your callback run before (`HEAD`) or after (`TAIL`) a game method.
- **Callback** — the `void` Java method a hook runs; its parameter shape decides what the hook does.
- **Selector** — the canonical signature string naming one method, e.g. `ChangeLane(self;int)`.
- **`self`** — the game object a hook/accessor acts on (the game method's `this`), an `Il2CppObject`.
- **Accessor** — an abstract method Frostbite implements to read/write a field or call a method.
- **`Il2CppObject`** — the Java handle for **every** game object (and boxed struct). Declare it for
  every game object you touch; valid for the current callback unless you `persist()` it.
- **Boxing** — wrapping a copied struct value into an `Il2CppObject` so Java can hold it.
- **`Persistent`** — a re-resolvable, weak handle from `obj.persist()` that keeps a game object
  usable past its callback.
- **`CallbackInfo` / `CallbackInfoReturnable<T>`** — control objects to cancel a void method or
  replace a return value.
- **`Arguments`** — a control object to read and rewrite a method's arguments.
- **`Mixins`** — the registry; `Mixins.get(SomeMixin.class)` returns the live mixin instance.
- **`Il2Cpp`** — the annotation-free helper for fields and method calls via `Il2Cpp.Target`.
- **BNM / Dobby** — the native libraries Frostbite uses to resolve names and install hooks. You never
  call them directly.
- **`.frostbite`** — your packaged mod (DEX + manifests + any ARM libs), the thing you install.

---

You now know the whole model: name a class with `@Mixin`, run code around its methods with `@Inject`
(observe, override, cancel, or edit args), read/write its fields and call its methods with accessors
or `Il2Cpp`, and treat every game object as an `Il2CppObject` — persisted when you need it past the
callback. Everything else is just picking the right name and the right power. Go write mods.
