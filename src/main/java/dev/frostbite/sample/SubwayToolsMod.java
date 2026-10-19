package dev.frostbite.sample;

import dev.frostbite.api.FrostbiteMod;
import dev.frostbite.api.ModContext;
import dev.frostbite.api.ModInitializer;

@FrostbiteMod(id = "subway-tools", version = "0.1.0")
public final class SubwayToolsMod implements ModInitializer {
    @Override public void onLoad(ModContext context) {
        context.log("Subway Tools loaded");
    }
}
