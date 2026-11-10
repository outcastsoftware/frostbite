# Frostbite Sample Mod

This separate Android project is a Java-only Frostbite cheat mod for Subway Surfers 3.70.3, targeting `SYBO.RunnerCore.Character.CharacterMotor`. Every cheat is testable in a live run (filter logcat for `FrostbiteSample`):

- **God mode** — `set_ColliderEnable(self;false)` each frame (no obstacle collisions) plus a non-void override of `CheckFalling(self)` to `false` (no pit deaths). Idle into trains and the run never ends.
- **Infinite jump** — non-void override of `get_CanJump(self)` to `true`.
- **No boundary stumble** — `set_DisableBoundaryImpact(self;true)`.
- **Max acceleration** — `SetAccelerate(self;true)` plus the `_canAccelerate` field set `true` every frame; logged speed climbs continuously.
- **Telemetry** — throttled log of `get_ForwardSpeedTarget` (float), `get_JumpCount` (int), `get_Grounded` (bool), the `_canAccelerate` field (bool), the static const `LaneChangeThreshold` (float), and `get_LastTouchedSurface` (the `SurfaceKind` **enum**, returned as its underlying int).
- **Enum hook argument** — injects `SwitchState(self;MotorStateKind)` and logs the enum as an int.
- **Static-method support** (`PoolManagerMixin`) — all `PoolManager` methods are static: a static invoker calls `get_Working`, and a static **non-void injection** overrides its boolean return, called cross-mixin from the telemetry via `Mixins.get`.
- **Struct values** — `get_WorldPlacerPosition` returns a `Vector3` (boxed); the mod reads its `x`/`y`/`z` and passes it back into `Teleport(self;Vector3)`.
- **Universal hook** — `AdjustVelocityToSurface(self;UnityEngine.Vector3)` is injected with a **struct argument**, which the fixed trampolines cannot pass; Frostbite routes it through the register-preserving universal trampoline and the `Vector3` arrives as a boxed object.
- **Argument write-back** — `ChangeLane(self;int)` is injected with a mutable `Arguments` view that rewrites the lane argument to `+1`, so every lane change goes right regardless of the swipe. The modified value flows back into the registers before the original method runs.
- **Void NOP** — `ApplyGravity(self)` is cancelled via `CallbackInfo.cancel()`, so the original never runs and the character defies gravity (floats upward), demonstrating a name-based NOP of a void method.

This exercises the full Frostbite surface: non-void injection with return override (instance and static), method invocation of void/int/float/boolean returns with boolean/enum arguments, instance and static field get/set, and enum handling. Accessors are declared as `public abstract` methods annotated with `@AccessField`, `@AccessStaticField`, and `@AccessMethod`; Frostbite generates their implementations.

Two limitations this sample stays within: injection targets must be **concrete instance methods** (an abstract base method like `CharacterAbstractInputController.Update` has only a stub pointer and aborts the game, as do static injection targets), and **value-type (struct) arguments are not supported** — e.g. `CheckFrontalImpact(self;ImpactFrameState)` cannot be hooked, so god mode disables the collider instead of overriding the impact checks. Calling a mixin's accessors from other code works anywhere via `Mixins.get(CharacterMotorMixin.class)`.

Accessors are declared as `public abstract` methods annotated with `@AccessField`, `@AccessStaticField`, and `@AccessMethod`; Frostbite generates their implementations and registers one instance per mixin. It is self-contained: prebuilt Frostbite jars in `libs/` let it build without the core source.

The annotation processor generates `META-INF/frostbite/mod.json`, `hooks.json`, and `mixins.json`. Run `packageFrostbite` to produce a `.frostbite` mod under `build/outputs/frostbite/` (it builds the release APK, then strips it to the DEX, manifests, and any ARM native libraries). The **Mods** tab of Frostbite Desktop imports that `.frostbite` into a Frostbite-enabled game APK; the in-game runtime discovers it from the mod index and installs its hooks and accessors.

Verified on a MuMu Player 12 emulator (Android 12, x86_64 with ARM translation / Houdini): logcat showed every field get/set, static get/set (BalancingBudgetMS incrementing across frames), method invocation across all return types, and the non-void return override, with no errors.

Build with JDK 27 and Android SDK 37. Android Java source and bytecode target 17. Mod authors do not write C++.
