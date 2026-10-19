package dev.frostbite.runtime;

import dev.frostbite.api.ModInitializer;
import java.util.List;

public record LoadedMod(String id, String version, ClassLoader classLoader,
                        ModInitializer initializer, List<HookDeclaration> hooks) {}
