package dev.frostbite.api;

/**
 * Mirage's 3D half: spawn real Unity primitives and meshes into the game world. Unlike the 2D
 * overlay, these are actual {@code GameObject}s the engine draws every frame with correct depth and
 * perspective — built entirely through Frostbite's invoke + {@link Il2Cpp#newInstance} machinery,
 * no native rendering of our own.
 *
 * <pre>{@code
 * Mirage3D.Node cube = Mirage3D.box().scale(3).position(x, y + 4, z + 12);
 * // ...later, keep it across frames with cube.transform()'s persist(), or:
 * cube.destroy();
 * }</pre>
 *
 * <p>Primitives render with Unity's default material. A game using a custom render pipeline may show
 * them untextured (magenta) until you assign one of its own materials to {@link Node#gameObject()}.
 */
public final class Mirage3D {
    private static final String UE = "UnityEngine.CoreModule", NS = "UnityEngine";
    private static final Il2Cpp.Target CREATE =
            new Il2Cpp.Target(UE, NS, "GameObject", "CreatePrimitive(static;UnityEngine.PrimitiveType)");
    private static final Il2Cpp.Target GET_TRANSFORM = new Il2Cpp.Target(UE, NS, "GameObject", "get_transform(self)");
    private static final Il2Cpp.Target SET_POSITION = new Il2Cpp.Target(UE, NS, "Transform", "set_position(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target SET_SCALE = new Il2Cpp.Target(UE, NS, "Transform", "set_localScale(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target SET_EULER = new Il2Cpp.Target(UE, NS, "Transform", "set_eulerAngles(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target DESTROY = new Il2Cpp.Target(UE, NS, "Object", "Destroy(static;UnityEngine.Object)");
    private static final Il2Cpp.Target GET_COMPONENT = new Il2Cpp.Target(UE, NS, "GameObject", "GetComponent(self;string)");
    private static final Il2Cpp.Target GET_MATERIAL = new Il2Cpp.Target(UE, NS, "Renderer", "get_material(self)");
    private static final Il2Cpp.Target SET_RENDER_QUEUE = new Il2Cpp.Target(UE, NS, "Material", "set_renderQueue(self;int)");
    private static final Il2Cpp.Target SET_INT = new Il2Cpp.Target(UE, NS, "Material", "SetInt(self;string;int)");
    private static final Il2Cpp.Type VEC3 = new Il2Cpp.Type(UE, NS, "Vector3");

    /** Unity's built-in primitive shapes (values match {@code UnityEngine.PrimitiveType}). */
    public enum Shape {
        SPHERE(0), CAPSULE(1), CYLINDER(2), CUBE(3), PLANE(4), QUAD(5);
        final int value;
        Shape(int value) { this.value = value; }
    }

    private Mirage3D() {}

    /** Spawn a primitive of the given shape at the origin, or {@code null} if creation failed. */
    public static Node spawn(Shape shape) {
        Il2CppObject go = Il2Cpp.invokeObject(null, CREATE, shape.value);
        return go == null ? null : new Node(go);
    }

    public static Node box() { return spawn(Shape.CUBE); }
    public static Node capsule() { return spawn(Shape.CAPSULE); }
    public static Node sphere() { return spawn(Shape.SPHERE); }
    public static Node cylinder() { return spawn(Shape.CYLINDER); }
    public static Node plane() { return spawn(Shape.PLANE); }
    public static Node quad() { return spawn(Shape.QUAD); }

    /**
     * A spawned world object you can move, scale, rotate, occlude, and destroy. The GameObject is
     * held in a persistent handle, so a {@code Node} stays usable across frames — update an ESP
     * marker's {@link #position} every frame. {@link #alive()} reports if the scene still has it.
     */
    public static final class Node {
        private final Persistent handle;   // the GameObject, persisted across frames

        Node(Il2CppObject gameObject) { this.handle = gameObject.persist(); }

        /** The current {@code GameObject}, or {@code null} if destroyed. */
        public Il2CppObject gameObject() { return handle.get(); }
        /** The current {@code Transform}, or {@code null}. */
        public Il2CppObject transform() {
            Il2CppObject go = handle.get();
            return go == null ? null : Il2Cpp.invokeObject(go, GET_TRANSFORM);
        }
        /** True while the object still exists in the scene. */
        public boolean alive() { return handle.get() != null; }

        public Node position(float x, float y, float z) {
            Il2CppObject tf = transform();
            if (tf != null) Il2Cpp.invokeVoid(tf, SET_POSITION, Il2Cpp.newInstance(VEC3, x, y, z));
            return this;
        }
        public Node scale(float x, float y, float z) {
            Il2CppObject tf = transform();
            if (tf != null) Il2Cpp.invokeVoid(tf, SET_SCALE, Il2Cpp.newInstance(VEC3, x, y, z));
            return this;
        }
        public Node scale(float uniform) { return scale(uniform, uniform, uniform); }
        public Node rotation(float x, float y, float z) {
            Il2CppObject tf = transform();
            if (tf != null) Il2Cpp.invokeVoid(tf, SET_EULER, Il2Cpp.newInstance(VEC3, x, y, z));
            return this;
        }

        /**
         * Control depth occlusion. {@code occlude(false)} makes the object draw <b>through walls</b>
         * — the basis of true ESP — by pushing its material to the overlay render queue and setting
         * its depth test to Always; {@code occlude(true)} restores normal depth. Reliability depends
         * on the game's shader honoring {@code _ZTest}; for guaranteed ESP, draw boxes with the 2D
         * {@link Mirage} overlay at projected screen positions instead.
         */
        public Node occlude(boolean occluded) {
            Il2CppObject go = handle.get();
            if (go == null) return this;
            Il2CppObject renderer = Il2Cpp.invokeObject(go, GET_COMPONENT, "MeshRenderer");
            if (renderer != null) {
                Il2CppObject material = Il2Cpp.invokeObject(renderer, GET_MATERIAL);
                if (material != null) {
                    Il2Cpp.invokeVoid(material, SET_RENDER_QUEUE, occluded ? 2000 : 4000);
                    Il2Cpp.invokeVoid(material, SET_INT, "_ZTest", occluded ? 4 : 8);   // 4=LEqual, 8=Always
                }
            }
            return this;
        }

        /** Remove this object from the scene. */
        public void destroy() {
            Il2CppObject go = handle.get();
            if (go != null) Il2Cpp.invokeVoid(null, DESTROY, go);
            handle.release();
        }
    }
}
