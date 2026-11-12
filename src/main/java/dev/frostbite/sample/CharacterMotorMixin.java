package dev.frostbite.sample;

import android.util.Log;
import dev.frostbite.api.AccessField;
import dev.frostbite.api.Arguments;
import dev.frostbite.api.AccessMethod;
import dev.frostbite.api.AccessMode;
import dev.frostbite.api.AccessStaticField;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfo;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2Cpp;
import dev.frostbite.api.Il2CppObject;
import dev.frostbite.api.Mirage;
import dev.frostbite.api.Mirage3D;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;
import dev.frostbite.api.Mixins;
import dev.frostbite.api.Persistent;

/**
 * Subway Tools: a working cheat suite for {@code SYBO.RunnerCore.Character.CharacterMotor}
 * in Subway Surfers 3.70.3, testable in a live run.
 *
 * <ul>
 *   <li><b>God mode</b> — the character's collider is disabled and the fall check is
 *       forced to report no fall, so trains, barriers, and pits never end the run.</li>
 *   <li><b>Infinite jump</b> — jump is always allowed, even mid-air.</li>
 *   <li><b>No boundary stumble</b> — boundary impacts are disabled.</li>
 *   <li><b>Max acceleration</b> — the motor is kept accelerating every frame.</li>
 *   <li><b>Telemetry</b> — speed, jump count, and grounded state are logged.</li>
 * </ul>
 *
 * <p><b>Style rule:</b> every game object — {@code self}, an object argument, a boxed struct, a
 * method return — is declared as {@link Il2CppObject}, never a bare {@code Object}. {@code Object}
 * compiles too, but {@code Il2CppObject} is self-documenting and lets you call {@code persist()},
 * {@code nativeAddress()}, and friends without a cast. Use it everywhere.
 *
 * <p>Accessors are declared here with no body; Frostbite generates them. Filter logcat for
 * {@code FrostbiteSample} to watch it.
 */
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character", targetClass = "CharacterMotor")
public abstract class CharacterMotorMixin {
    private static final String TAG = "FrostbiteSample";
    private long frames;

    // The live motor captured past its callback: taken once in a HEAD hook, then reused from
    // a later frame to edit that same instance. A weak handle, so it reports when it dies.
    private Persistent capturedMotor;

    // The Vector3 class, for constructing new instances.
    private static final Il2Cpp.Type VEC3 = new Il2Cpp.Type("UnityEngine.CoreModule", "UnityEngine", "Vector3");

    // A Mirage 3D marker spawned once and repositioned each frame; drawn through walls (ESP).
    private Mirage3D.Node espBox;

    // Fields on the boxed Vector3 (a value type) returned by worldPos().
    private static final Il2Cpp.Target VEC3_X = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "x");
    private static final Il2Cpp.Target VEC3_Y = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "y");
    private static final Il2Cpp.Target VEC3_Z = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "z");

    // ---- accessors used by the cheats and the telemetry ----
    // Note the object parameter is Il2CppObject, not Object: the motor instance, type-named.
    @AccessMethod("SetAccelerate(self;boolean)")
    public abstract void setAccelerate(Il2CppObject motor, boolean on);

    @AccessMethod("set_DisableBoundaryImpact(self;boolean)")
    public abstract void setDisableBoundaryImpact(Il2CppObject motor, boolean on);

    @AccessMethod("set_ColliderEnable(self;boolean)")
    public abstract void setColliderEnable(Il2CppObject motor, boolean on);

    @AccessMethod("get_ForwardSpeedTarget(self)")
    public abstract float speed(Il2CppObject motor);

    @AccessMethod("get_JumpCount(self)")
    public abstract int jumpCount(Il2CppObject motor);

    @AccessMethod("get_Grounded(self)")
    public abstract boolean grounded(Il2CppObject motor);

    @AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
    public abstract boolean canAccelerate(Il2CppObject motor);

    @AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
    public abstract void setCanAccelerate(Il2CppObject motor, boolean value);

    @AccessStaticField(value = "LaneChangeThreshold", mode = AccessMode.GETTER)
    public abstract float laneChangeThreshold();

    // Enum return via invoke: SurfaceKind comes back as its underlying int.
    @AccessMethod("get_LastTouchedSurface(self)")
    public abstract int lastSurface(Il2CppObject motor);

    // Struct (value type) return and argument: Vector3 is boxed on the way out and
    // unboxed on the way back in. A boxed struct is an Il2CppObject like any other.
    @AccessMethod("get_WorldPlacerPosition(self)")
    public abstract Il2CppObject worldPos(Il2CppObject motor);

    @AccessMethod("Teleport(self;UnityEngine.Vector3)")
    public abstract void teleport(Il2CppObject motor, Il2CppObject position);

    // Struct-return override target (queried by the game only on impacts; god mode avoids those).
    @AccessMethod("get_LastImpactVelocity(self)")
    public abstract Il2CppObject lastImpactVelocity(Il2CppObject motor);

    // ---- cheats applied every frame ----
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void applyCheats(Il2CppObject self) {
        // Capture this motor once so other hooks can edit it on a later frame. self is already
        // an Il2CppObject, so persist() is a direct call — no cast.
        if (capturedMotor == null) {
            capturedMotor = self.persist();
            Log.i(TAG, "[SubwayTools] captured motor for later use");
        }
        setCanAccelerate(self, true);
        setAccelerate(self, true);
        setDisableBoundaryImpact(self, true);
        setColliderEnable(self, false);   // god mode: no obstacle collisions

        // Mirage 2D: a live HUD panel in the game's own window (no overlay permission).
        if (Mirage.available()) {
            Mirage.clear();
            Mirage.roundRect(48, 140, 320, 104, 18, Mirage.rgba(16, 18, 20, 220));
            Mirage.strokeRect(48, 140, 320, 104, 18, 2, Mirage.rgb(213, 203, 188));
            Mirage.text(72, 182, "FROSTBITE MIRAGE", 30, Mirage.rgb(243, 243, 239));
            Mirage.text(72, 220, "speed  " + (int) speed(self), 24, Mirage.rgb(213, 203, 188));
            Mirage.fillCircle(340, 170, 9, Mirage.rgb(213, 203, 188));
        }

        // Mirage 3D: a true in-engine ESP box — colored and drawn THROUGH walls (occlude(false)),
        // following the player. Spawned once, recolored/occluded once, repositioned each frame.
        if (espBox == null) {
            espBox = Mirage3D.box();
            if (espBox != null) {
                espBox.scale(2f).color(Mirage.rgb(239, 149, 142)).occlude(false);
                Log.i(TAG, "[SubwayTools] Mirage 3D ESP box spawned (through walls)");
            }
        }
        if (espBox != null && espBox.alive()) {
            Il2CppObject p = worldPos(self);
            espBox.position(Il2Cpp.getFloat(p, VEC3_X) + 4f, Il2Cpp.getFloat(p, VEC3_Y) + 2f,
                    Il2Cpp.getFloat(p, VEC3_Z) + 10f);
        }
        if (++frames % 120 == 1) {
            // Invoke a hooked static method on another mixin: the override makes it true.
            boolean poolWorking = Mixins.get(PoolManagerMixin.class).working();

            // Struct  return + struct field reads + struct-argument round-trip (Vector3).
            Il2CppObject pos = worldPos(self);
            float px = Il2Cpp.getFloat(pos, VEC3_X), py = Il2Cpp.getFloat(pos, VEC3_Y), pz = Il2Cpp.getFloat(pos, VEC3_Z);
            teleport(self, pos);   // pass the struct back in; net no-op, proves unboxing

            // Instance creation: build a brand-new Vector3(7,8,9) from scratch and read it back.
            Il2CppObject made = Il2Cpp.newInstance(VEC3, 7f, 8f, 9f);
            Log.i(TAG, "[SubwayTools] newInstance Vector3 = (" + Il2Cpp.getFloat(made, VEC3_X) + ","
                    + Il2Cpp.getFloat(made, VEC3_Y) + "," + Il2Cpp.getFloat(made, VEC3_Z) + ")");

            // Struct-RETURN override: get_LastImpactVelocity is forced to Vector3(1,1,1).
            Il2CppObject iv = lastImpactVelocity(self);
            Log.i(TAG, "[SubwayTools] struct-return override LastImpactVelocity=("
                    + Il2Cpp.getFloat(iv, VEC3_X) + "," + Il2Cpp.getFloat(iv, VEC3_Y) + "," + Il2Cpp.getFloat(iv, VEC3_Z) + ")");

            // Persistence proof: this is a LATER callback than the frame-1 capture. Resolve the
            // stashed handle, confirm it points at the same live motor as this frame's self, and
            // edit that instance through the resolved reference (not self).
            Il2CppObject stashed = capturedMotor.get();
            if (stashed != null) {
                boolean same = stashed.nativeAddress() == self.nativeAddress();
                setCanAccelerate(stashed, true);   // edit the captured instance, not self
                Log.i(TAG, "[SubwayTools] persisted self still alive=" + capturedMotor.isAlive()
                        + " sameInstance=" + same + " speedViaStashed=" + speed(stashed));
            } else {
                Log.i(TAG, "[SubwayTools] persisted self was collected");
            }

            Log.i(TAG, "[SubwayTools] speed=" + speed(self) + " jumpCount=" + jumpCount(self)
                    + " grounded=" + grounded(self) + " canAccelerate=" + canAccelerate(self)
                    + " surface=" + lastSurface(self) + " poolWorking(static)=" + poolWorking
                    + " pos=(" + px + "," + py + "," + pz + ")"
                    + " (godMode on, laneThreshold=" + laneChangeThreshold() + ")");
        }
    }

    // ---- god mode: never register a fall (pits/gaps) ----
    @Inject(method = "CheckFalling(self)", at = At.HEAD)
    public void noFalling(Il2CppObject self, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(Boolean.FALSE);
    }

    // ---- infinite jump ----
    @Inject(method = "get_CanJump(self)", at = At.HEAD)
    public void alwaysCanJump(Il2CppObject self, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(Boolean.TRUE);
    }

    // ---- enum hook argument: MotorStateKind arrives as its underlying int ----
    @Inject(method = "SwitchState(self;SYBO.RunnerCore.Character.MotorStateKind)", at = At.HEAD)
    public void onSwitchState(Il2CppObject self, int stateKind) {
        Log.i(TAG, "[SubwayTools] SwitchState -> MotorStateKind " + stateKind);
    }

    // ---- universal trampoline: a hook taking a value-type (Vector3) argument, which the
    // fixed trampolines cannot pass. The struct arrives as a boxed Il2CppObject. ----
    private long surfaceTicks;

    @Inject(method = "AdjustVelocityToSurface(self;UnityEngine.Vector3)", at = At.HEAD)
    public void onSurfaceVelocity(Il2CppObject self, Il2CppObject velocity) {
        if (++surfaceTicks % 120 != 1) return;
        Log.i(TAG, "[SubwayTools] UNIVERSAL hook AdjustVelocityToSurface vel=("
                + Il2Cpp.getFloat(velocity, VEC3_X) + "," + Il2Cpp.getFloat(velocity, VEC3_Y) + "," + Il2Cpp.getFloat(velocity, VEC3_Z) + ")");
    }

    // ---- argument write-back: force every lane change to the right (+1), whatever was
    // requested. The mutable Arguments view rewrites the argument before the original runs. ----
    @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
    public void forceRightLane(Il2CppObject self, Arguments args, CallbackInfoReturnable<Boolean> callback) {
        int requested = args.getInt(0);
        args.setInt(0, 1);
        Log.i(TAG, "[SubwayTools] ChangeLane write-back: " + requested + " -> 1 (always right)");
    }

    // ---- struct-RETURN override: force LastImpactVelocity to Vector3(1,1,1) ----
    @Inject(method = "get_LastImpactVelocity(self)", at = At.HEAD)
    public void overrideImpactVelocity(Il2CppObject self, CallbackInfoReturnable<Il2CppObject> callback) {
        callback.setReturnValue(Mixins.get(VectorMixin.class).one());
    }

    // ---- NOP a void method by name: cancel ApplyGravity so the character never falls ----
    private long gravityTicks;

    @Inject(method = "ApplyGravity(self)", at = At.HEAD)
    public void noGravity(Il2CppObject self, CallbackInfo ci) {
        ci.cancel();   // the original ApplyGravity never runs
        if (++gravityTicks % 300 == 1)
            Log.i(TAG, "[SubwayTools] NOP ApplyGravity (no gravity) #" + gravityTicks);
    }
}
