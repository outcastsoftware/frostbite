package dev.frostbite.sample;

import dev.frostbite.api.AccessMethod;
import dev.frostbite.api.Il2CppObject;
import dev.frostbite.api.Mixin;

/** Accessor-only mixin for UnityEngine.Vector3, used to obtain a Vector3(1,1,1) to override with. */
@Mixin(assembly = "UnityEngine.CoreModule", namespace = "UnityEngine", targetClass = "Vector3")
public abstract class VectorMixin {
    @AccessMethod("get_one(static)")
    public abstract Il2CppObject one();
}
