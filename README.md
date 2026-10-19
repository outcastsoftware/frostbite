# Frostbite Desktop

The combined desktop GUI prepares a Unity IL2CPP game APK and adds separately built Frostbite Java mods. It has two tabs:

1. **Install Frostbite:** select the original APK, `frostbite-runtime.zip`, the game's full launch Activity class, and an output APK.
2. **Mods:** select the Frostbite-enabled APK, add one or more `.frostbite` mods and/or select installed mods to remove, and choose the final game APK output.

Both tabs use the bundled development key. No keystore setup is needed. Outputs are aligned, signed with APK Signature Schemes v1, v2, and v3, and verified. After the first step succeeds, the app opens the second tab and fills the Frostbite APK path. Since the key is distributed with the app, it provides a convenient consistent signature for development rather than a private publisher identity.

Build with JDK 27 and Gradle `installDist`, then open `build/install/frostbite-desktop/bin/frostbite-desktop.bat` on Windows. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to an Android SDK with build tools. The app is a Java Swing desktop application and is self-contained: it builds against the prebuilt `libs/frostbite-host-tools.jar` (produced from the `framework` branch) and does not need the core source.

The window has its own minimize, maximize, and close controls. Drag the title bar to move it or an edge or corner to resize it. The form adapts to narrower windows and short windows scroll vertically. The transparent [Outcast logo](logo/outcast-breach.svg) and interface tokens are documented in [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

See the full [getting started guide](https://github.com/outcastsoftware/frostbite/blob/framework/docs/GETTING_STARTED.md) (on the `framework` branch) for a mod project, the Subway Surfers example, signing, installation, and troubleshooting.
