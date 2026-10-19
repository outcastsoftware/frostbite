package dev.frostbite.sample;

import dev.frostbite.api.At;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;
import android.util.Log;

@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character", targetClass = "CharacterMotor")
public final class CharacterMotorMixin {
    private static final String TAG = "FrostbiteSample";
    private long updates;

    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void beforeUpdate(Object self) {
        if (++updates % 300 == 1) Log.i(TAG, "CharacterMotor.UpdateMovement HEAD call=" + updates);
    }

    @Inject(method = "UpdateMovement(self)", at = At.TAIL)
    public void afterUpdate(Object self) {
        if (updates % 300 == 1) Log.i(TAG, "CharacterMotor.UpdateMovement TAIL call=" + updates);
    }
}
