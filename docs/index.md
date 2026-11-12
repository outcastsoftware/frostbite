---
title: Home
nav_order: 1
---

# Frostbite

A **Java** mixin framework for modding Unity **IL2CPP** Android games. You attach Java code to a
game by **name** — hook any method, read and write any field, call any method, change arguments,
override or cancel returns — and Frostbite does the native work (BNM + Dobby) for you. No C++, no
hunting for offsets that break every game update.

## Start here

- **[User Guide](USER_GUIDE.md)** — the full course. Takes a Java developer from the core idea to
  writing any Frostbite mod: mixins, hooks, selectors, the four hook powers, accessors, objects
  and structs, a complete worked cheat, a reference, every limitation, and a glossary.
- **[Build Pipeline](GETTING_STARTED.md)** — how to build Frostbite, patch a game APK, package a
  `.frostbite` mod, and install and run it.

## What it can do

- Hook any IL2CPP method (instance or static, any signature) at **HEAD** or **TAIL**.
- **Override** a return value, **cancel** a void method (name-based NOP), or **rewrite** the
  arguments.
- Read and write any field (instance or static), call any method — `int`, `long`, `float`,
  `double`, `bool`, enums, objects, and value-type structs.
- **Persist** a captured object (a hook's `self`) past the callback and edit that same instance
  on a later frame — a weak GC handle that reports when the game drops it.
- **Create** new IL2CPP objects (`Il2Cpp.newInstance`, e.g. `new Vector3(1,2,3)`).
- **Draw** with **Mirage**: a 2D overlay (rounded rects, text, lines — no overlay permission) and 3D
  world objects (boxes, capsules, meshes) with depth-occlusion control for ESP.
- Resolve everything by **name** at runtime; one bad hook is skipped with a warning, never
  crashing the rest.

## What it is not

Frostbite is a code-modding engine, not a mod-menu UI toolkit. It has no on-screen overlay,
no ESP/drawing, and no raw arbitrary-address memory access — those can be built on top of it in
your own mod.

Use the sidebar to navigate. Source on [GitHub](https://github.com/outcastsoftware/frostbite).
