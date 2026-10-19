# Frostbite Sample Mod

This separate Android project is a Java-only Frostbite mod for Subway Surfers 3.70.3. Its `CharacterMotorMixin` injects at HEAD and TAIL of `SYBO.RunnerCore.Character.CharacterMotor.UpdateMovement(self)` and logs through Android logcat. It is self-contained: prebuilt Frostbite jars in `libs/` let it build without the core source.

The annotation processor generates `META-INF/frostbite/mod.json` and `META-INF/frostbite/hooks.json`. Run `packageFrostbite` to produce a `.frostbite` mod under `build/outputs/frostbite/` (it builds the release APK, then strips it to the DEX, manifests, and any ARM libraries). The **Mods** tab of [Frostbite Desktop](https://github.com/outcastsoftware/frostbite/tree/main) imports that `.frostbite` into a Frostbite-enabled game APK; the in-game runtime discovers it from the mod index and installs its hook. The sample has been run on an emulator: logcat showed HEAD and TAIL at calls 1, 301, and 601.

Build with JDK 27 and Android SDK 37. Android Java source and bytecode target 17. Mod authors do not write C++.

Follow the [getting started guide](https://github.com/outcastsoftware/frostbite/blob/framework/docs/GETTING_STARTED.md) (on the `framework` branch) for the complete build, installation, and logcat workflow.
