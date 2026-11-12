package dev.frostbite.api;

/**
 * Mirage's 3D half: spawn real Unity primitives into the game world and render them <b>in-engine</b>
 * — actual {@code GameObject}s the GPU draws every frame with correct depth and perspective, built
 * through Frostbite's invoke + {@link Il2Cpp#newInstance} machinery.
 *
 * <p>{@link Node#color} and {@link Node#occlude} assign a material using Unity's built-in
 * {@code Hidden/Internal-Colored} shader, which honors a flat {@code _Color} and a {@code _ZTest}
 * depth mode. {@code occlude(false)} sets depth test to <b>Always</b>, so the object renders
 * <b>through walls</b> — true, GPU-side ESP, no screen overlay.
 *
 * <pre>{@code
 * Mirage3D.Node marker = Mirage3D.box().scale(2)
 *         .color(Mirage.rgb(239, 149, 142))
 *         .occlude(false);                       // draws through walls
 * // a Node persists across frames — move it onto a target each frame:
 * marker.position(x, y, z);
 * marker.destroy();
 * }</pre>
 */
public final class Mirage3D {
    private static final String UE = "UnityEngine.CoreModule", NS = "UnityEngine";
    private static final Il2Cpp.Target CREATE =
            new Il2Cpp.Target(UE, NS, "GameObject", "CreatePrimitive(static;UnityEngine.PrimitiveType)");
    private static final Il2Cpp.Target GET_TRANSFORM = new Il2Cpp.Target(UE, NS, "GameObject", "get_transform(self)");
    private static final Il2Cpp.Target GET_COMPONENT = new Il2Cpp.Target(UE, NS, "GameObject", "GetComponent(self;System.Type)");
    private static final Il2Cpp.Target SET_POSITION = new Il2Cpp.Target(UE, NS, "Transform", "set_position(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target SET_SCALE = new Il2Cpp.Target(UE, NS, "Transform", "set_localScale(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target SET_EULER = new Il2Cpp.Target(UE, NS, "Transform", "set_eulerAngles(self;UnityEngine.Vector3)");
    private static final Il2Cpp.Target DESTROY = new Il2Cpp.Target(UE, NS, "Object", "Destroy(static;UnityEngine.Object)");
    private static final Il2Cpp.Target SHADER_FIND = new Il2Cpp.Target(UE, NS, "Shader", "Find(static;string)");
    private static final Il2Cpp.Target SET_MATERIAL = new Il2Cpp.Target(UE, NS, "Renderer", "set_material(self;UnityEngine.Material)");
    private static final Il2Cpp.Target SET_COLOR = new Il2Cpp.Target(UE, NS, "Material", "SetColor(self;string;UnityEngine.Color)");
    private static final Il2Cpp.Target SET_INT = new Il2Cpp.Target(UE, NS, "Material", "SetInt(self;string;int)");
    private static final Il2Cpp.Target SET_RENDER_QUEUE = new Il2Cpp.Target(UE, NS, "Material", "set_renderQueue(self;int)");
    private static final Il2Cpp.Type VEC3 = new Il2Cpp.Type(UE, NS, "Vector3");
    private static final Il2Cpp.Type COLOR = new Il2Cpp.Type(UE, NS, "Color");
    private static final Il2Cpp.Type MATERIAL = new Il2Cpp.Type(UE, NS, "Material");
    private static final Il2Cpp.Type RENDERER = new Il2Cpp.Type(UE, NS, "Renderer");
    private static Il2CppObject rendererType;   // System.Type for Renderer (permanent; resolved once)

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

    private static Il2CppObject rendererType() {
        if (rendererType == null) rendererType = Il2Cpp.typeObject(RENDERER);
        return rendererType;
    }

    /**
     * A spawned world object you can move, scale, rotate, color, occlude, and destroy. The GameObject
     * is held in a persistent handle, so a {@code Node} stays usable across frames — update an ESP
     * marker's {@link #position} every frame. {@link #alive()} reports if the scene still has it.
     */
    public static final class Node {
        private final Persistent handle;   // the GameObject, persisted across frames
        private Il2CppObject material;     // our Internal-Colored material (lazy)
        private int color = 0xFFFFFFFF;
        private boolean through;           // true = draw through walls (ESP)

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

        /** Flat color (ARGB), applied via an {@code Internal-Colored} material (fixes the default magenta). */
        public Node color(int argb) { this.color = argb; applyMaterial(); return this; }

        /**
         * Depth occlusion. {@code occlude(false)} makes the object draw <b>through walls</b> — true
         * in-engine ESP — by setting the material's depth test to Always and pushing it to the
         * overlay render queue; {@code occlude(true)} restores normal depth.
         */
        public Node occlude(boolean occluded) { this.through = !occluded; applyMaterial(); return this; }

        /** (Re)build and assign the Internal-Colored material with the current color + depth mode. */
        private void applyMaterial() {
            Il2CppObject go = handle.get();
            if (go == null) return;
            Il2CppObject renderer = Il2Cpp.invokeObject(go, GET_COMPONENT, rendererType());
            if (renderer == null) return;
            if (material == null) {
                Il2CppObject shader = Il2Cpp.invokeObject(null, SHADER_FIND, "Hidden/Internal-Colored");
                if (shader == null) return;
                material = Il2Cpp.newInstance(MATERIAL, shader);
                if (material == null) return;
            }
            float r = ((color >> 16) & 0xff) / 255f, g = ((color >> 8) & 0xff) / 255f;
            float b = (color & 0xff) / 255f, a = ((color >> 24) & 0xff) / 255f;
            Il2Cpp.invokeVoid(material, SET_COLOR, "_Color", Il2Cpp.newInstance(COLOR, r, g, b, a));
            Il2Cpp.invokeVoid(material, SET_INT, "_ZTest", through ? 8 : 4);    // 8=Always, 4=LEqual
            Il2Cpp.invokeVoid(material, SET_INT, "_ZWrite", through ? 0 : 1);
            Il2Cpp.invokeVoid(material, SET_RENDER_QUEUE, through ? 5000 : 2000);
            Il2Cpp.invokeVoid(renderer, SET_MATERIAL, material);
        }

        /** Remove this object from the scene. */
        public void destroy() {
            Il2CppObject go = handle.get();
            if (go != null) Il2Cpp.invokeVoid(null, DESTROY, go);
            handle.release();
        }
    }
}
