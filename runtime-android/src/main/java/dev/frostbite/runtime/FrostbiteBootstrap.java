package dev.frostbite.runtime;

import android.content.Context;
import android.util.Log;
import java.util.List;

/** Called by the patched activity. Process scoped and safe across activity recreation. */
public final class FrostbiteBootstrap {
    private static final String TAG = "Frostbite";
    private static State state = State.NEW;
    private static List<LoadedMod> mods = List.of();

    private FrostbiteBootstrap() {}

    public static synchronized void start(Context context) {
        if (state != State.NEW) return;
        state = State.STARTING;
        try {
            mods = new ModLoader(context.getApplicationContext()).load();
            NativeBridge.initialize(mods);
            state = State.MODS_LOADED;
            Log.i(TAG, "Loaded " + mods.size() + " Frostbite mod(s)");
        } catch (Exception | LinkageError error) {
            state = State.FAILED;
            Log.e(TAG, "Frostbite startup failed", error);
        }
    }

    public static synchronized String state() { return state.name(); }
    public static synchronized List<LoadedMod> loadedMods() { return List.copyOf(mods); }

    private enum State { NEW, STARTING, MODS_LOADED, FAILED }
}
