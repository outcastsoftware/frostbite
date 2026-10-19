# Frostbite Sample Mod

This separate Android project is a Java-only Frostbite mod for Subway Surfers 3.70.3. Its `CharacterMotorMixin` injects at HEAD and TAIL of `SYBO.RunnerCore.Character.CharacterMotor.UpdateMovement(self)` and logs through Android logcat. It compiles against the core project through a Gradle composite build.

The annotation processor generates `META-INF/frostbite/mod.json` and `META-INF/frostbite/hooks.json`. `assembleDebug` produces a mod APK under `build/outputs/apk/debug/`. The **Add Mods** tab of [Frostbite Desktop](../Frostbite-Desktop) imports this APK into a Frostbite-enabled game APK; the in-game runtime discovers it from the mod index and installs its hook. The sample has been run on an emulator: logcat showed HEAD and TAIL at calls 1, 301, and 601.

Build with JDK 27 and Android SDK 37. Android Java source and bytecode target 17. Mod authors do not write C++.

Follow the [getting started guide](../../Frostbite/docs/GETTING_STARTED.md) for the complete build, APK installation, and logcat workflow.
