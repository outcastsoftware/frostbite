package dev.frostbite.processor;

import dev.frostbite.api.AccessField;
import dev.frostbite.api.AccessMethod;
import dev.frostbite.api.AccessMode;
import dev.frostbite.api.AccessStaticField;
import dev.frostbite.api.At;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;
import dev.frostbite.api.FrostbiteMod;
import dev.frostbite.api.ModInitializer;
import dev.frostbite.api.MethodSelector;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@SupportedAnnotationTypes({"dev.frostbite.api.Mixin", "dev.frostbite.api.Inject", "dev.frostbite.api.FrostbiteMod",
        "dev.frostbite.api.AccessField", "dev.frostbite.api.AccessStaticField", "dev.frostbite.api.AccessMethod"})
public final class MixinProcessor extends AbstractProcessor {
    private static final String IMPL_SUFFIX = "_Frostbite";
    private static final String OBJECT = "java.lang.Object";
    private static final String IL2CPP_OBJECT = "dev.frostbite.api.Il2CppObject";

    private final List<Hook> hooks = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    // Accessors grouped by their enclosing @Mixin class, in source order.
    private final Map<TypeElement, List<Accessor>> accessors = new LinkedHashMap<>();
    private final List<MixinImpl> impls = new ArrayList<>();
    private Mod mod;
    private boolean written;
    private boolean invalid;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(FrostbiteMod.class)) {
            if (!(element instanceof TypeElement type)) {
                error(element, "@FrostbiteMod requires a class");
                continue;
            }
            FrostbiteMod annotation = type.getAnnotation(FrostbiteMod.class);
            if (mod != null || !annotation.id().matches("[a-z0-9][a-z0-9._-]*") || annotation.version().isBlank()) {
                error(element, "One valid @FrostbiteMod entrypoint is required per mod artifact");
                continue;
            }
            TypeElement initializer = processingEnv.getElementUtils().getTypeElement(ModInitializer.class.getName());
            if (!processingEnv.getTypeUtils().isAssignable(type.asType(), initializer.asType()) ||
                    !type.getModifiers().contains(Modifier.PUBLIC) || type.getModifiers().contains(Modifier.ABSTRACT)) {
                error(element, "Entrypoint must be a public concrete ModInitializer");
                continue;
            }
            mod = new Mod(annotation.id(), annotation.version(),
                    processingEnv.getElementUtils().getBinaryName(type).toString());
        }
        for (Element element : round.getElementsAnnotatedWith(Inject.class)) collectInject(element);
        collectAccessors(round);
        if (round.processingOver() && !written && !invalid) {
            generateImpls();
            if (!invalid) writeManifest();
        }
        return true;
    }

    private void collectInject(Element element) {
        if (!(element instanceof ExecutableElement callback)) {
            error(element, "@Inject can only annotate a method");
            return;
        }
        Element parent = callback.getEnclosingElement();
        Mixin mixin = parent.getAnnotation(Mixin.class);
        Inject inject = callback.getAnnotation(Inject.class);
        if (mixin == null) {
            error(callback, "@Inject method must be inside a @Mixin class");
            return;
        }
        if (callback.getReturnType().getKind() != TypeKind.VOID) {
            error(callback, "Injection callbacks must return void");
            return;
        }
        String selector = inject.method().trim();
        try {
            new MethodSelector(selector, inject.mode());
        } catch (IllegalArgumentException badSelector) {
            error(callback, badSelector.getMessage());
            return;
        }
        if (!callback.getModifiers().contains(Modifier.PUBLIC) ||
                !parent.getModifiers().contains(Modifier.PUBLIC)) {
            error(callback, "Mixin class and injection callback must be public");
            return;
        }
        String key = parent + "#" + callback.getSimpleName() + callback.getParameters();
        if (!seen.add(key)) {
            error(callback, "Duplicate injection callback");
            return;
        }
        hooks.add(new Hook(mixin.assembly(), mixin.namespace(), mixin.targetClass(), selector,
                inject.at(), processingEnv.getElementUtils().getBinaryName((TypeElement) parent).toString(),
                callback.getSimpleName().toString(), inject.mode().name(), inject.priority(),
                callback.getModifiers().contains(Modifier.STATIC),
                callback.getParameters().stream()
                        .map(p -> processingEnv.getTypeUtils().erasure(p.asType()).toString()).toList()));
    }

    // ---- accessors ------------------------------------------------------

    private void collectAccessors(RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(AccessField.class)) {
            AccessField annotation = element.getAnnotation(AccessField.class);
            buildFieldAccessor(element, annotation.value(), annotation.mode(), false);
        }
        for (Element element : round.getElementsAnnotatedWith(AccessStaticField.class)) {
            AccessStaticField annotation = element.getAnnotation(AccessStaticField.class);
            buildFieldAccessor(element, annotation.value(), annotation.mode(), true);
        }
        for (Element element : round.getElementsAnnotatedWith(AccessMethod.class)) {
            AccessMethod annotation = element.getAnnotation(AccessMethod.class);
            buildMethodAccessor(element, annotation.value());
        }
    }

    private ExecutableElement accessorMethod(Element element) {
        if (!(element instanceof ExecutableElement method)) {
            error(element, "Frostbite accessors must annotate a method");
            return null;
        }
        if (!(method.getEnclosingElement() instanceof TypeElement mixin) || mixin.getAnnotation(Mixin.class) == null) {
            error(method, "Accessor must be inside a @Mixin class");
            return null;
        }
        if (!method.getModifiers().contains(Modifier.ABSTRACT) || method.getModifiers().contains(Modifier.STATIC)
                || !method.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "Accessor must be a public abstract (non-static) method; Frostbite supplies the body");
            return null;
        }
        return method;
    }

    private void buildFieldAccessor(Element element, String fieldName, AccessMode mode, boolean isStatic) {
        ExecutableElement method = accessorMethod(element);
        if (method == null) return;
        List<? extends VariableElement> params = method.getParameters();
        int instanceParams = isStatic ? 0 : 1;
        if (mode == AccessMode.GETTER) {
            if (params.size() != instanceParams) {
                error(method, "Field getter takes " + instanceParams + " parameter(s)");
                return;
            }
            String type = fieldType(method, method.getReturnType(), false);
            if (type == null) return;
            if (!isStatic && objectKind(params.get(0).asType()) == null) {
                error(method, "Instance getter's first parameter must be Object or Il2CppObject");
                return;
            }
            addAccessor(method, new Accessor(method, Accessor.Kind.FIELD_GET, isStatic, fieldName, type, List.of()));
        } else {
            if (method.getReturnType().getKind() != TypeKind.VOID || params.size() != instanceParams + 1) {
                error(method, "Field setter returns void and takes " + (instanceParams + 1) + " parameter(s)");
                return;
            }
            if (!isStatic && objectKind(params.get(0).asType()) == null) {
                error(method, "Instance setter's first parameter must be Object or Il2CppObject");
                return;
            }
            String type = fieldType(method, params.get(instanceParams).asType(), false);
            if (type == null) return;
            addAccessor(method, new Accessor(method, Accessor.Kind.FIELD_SET, isStatic, fieldName, type, List.of()));
        }
    }

    private void buildMethodAccessor(Element element, String selector) {
        ExecutableElement method = accessorMethod(element);
        if (method == null) return;
        selector = selector.trim();
        boolean isStatic = selector.contains("(static");
        if (!isStatic && !selector.contains("(self")) {
            error(method, "Invoker selector must start with (self or (static: " + selector);
            return;
        }
        List<? extends VariableElement> params = method.getParameters();
        int offset = isStatic ? 0 : 1;
        if (!isStatic && (params.isEmpty() || objectKind(params.get(0).asType()) == null)) {
            error(method, "Instance invoker's first parameter must be Object or Il2CppObject");
            return;
        }
        String returnType = fieldType(method, method.getReturnType(), true);
        if (returnType == null) return;
        List<String> argTypes = new ArrayList<>();
        for (int i = offset; i < params.size(); i++) {
            String type = fieldType(method, params.get(i).asType(), false);
            if (type == null) return;
            argTypes.add(type);
        }
        addAccessor(method, new Accessor(method, Accessor.Kind.INVOKE, isStatic, selector, returnType, argTypes));
    }

    private void addAccessor(ExecutableElement method, Accessor accessor) {
        accessors.computeIfAbsent((TypeElement) method.getEnclosingElement(), k -> new ArrayList<>()).add(accessor);
    }

    /** Returns INT|FLOAT|BOOL|OBJECT (and VOID when allowed), or null after reporting an error. */
    private String fieldType(Element where, TypeMirror type, boolean allowVoid) {
        switch (type.getKind()) {
            case INT: return "INT";
            case LONG: return "LONG";
            case FLOAT: return "FLOAT";
            case DOUBLE: return "DOUBLE";
            case BOOLEAN: return "BOOL";
            case VOID:
                if (allowVoid) return "VOID";
                error(where, "void is not a valid accessor value type");
                return null;
            case DECLARED:
                if (objectKind(type) != null) return "OBJECT";
                error(where, "Object accessor type must be Object or Il2CppObject, not " + type);
                return null;
            default:
                error(where, "Unsupported accessor type: " + type + " (use int, float, boolean, or Object)");
                return null;
        }
    }

    private String objectKind(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        String name = type.toString();
        return (name.equals(OBJECT) || name.equals(IL2CPP_OBJECT)) ? name : null;
    }

    private void generateImpls() {
        for (Map.Entry<TypeElement, List<Accessor>> entry : accessors.entrySet()) {
            TypeElement mixin = entry.getKey();
            if (mixin.getModifiers().contains(Modifier.FINAL) || !mixin.getModifiers().contains(Modifier.ABSTRACT)
                    || !mixin.getModifiers().contains(Modifier.PUBLIC)) {
                error(mixin, "A @Mixin with accessors must be public abstract (non-final)");
                return;
            }
            Mixin coords = mixin.getAnnotation(Mixin.class);
            String binary = processingEnv.getElementUtils().getBinaryName(mixin).toString();
            String pkg = packageOf(binary);
            String simple = mixin.getSimpleName() + IMPL_SUFFIX;
            String implBinary = pkg.isEmpty() ? simple : pkg + "." + simple;
            try {
                writeImpl(mixin, coords, entry.getValue(), pkg, simple);
            } catch (IOException error) {
                error(mixin, "Could not generate accessor implementation: " + error.getMessage());
                return;
            }
            impls.add(new MixinImpl(binary, implBinary));
        }
    }

    private void writeImpl(TypeElement mixin, Mixin coords, List<Accessor> list,
                           String pkg, String simple) throws IOException {
        String mixinName = mixin.getQualifiedName().toString();
        JavaFileObject file = processingEnv.getFiler().createSourceFile(
                (pkg.isEmpty() ? "" : pkg + ".") + simple, mixin);
        try (Writer out = file.openWriter()) {
            if (!pkg.isEmpty()) out.write("package " + pkg + ";\n\n");
            out.write("// Generated by Frostbite. Implements the mixin's declared accessors.\n");
            out.write("public final class " + simple + " extends " + mixinName + " {\n");
            int index = 0;
            for (Accessor accessor : list) {
                String target = "new dev.frostbite.api.Il2Cpp.Target(\"" + esc(coords.assembly()) + "\",\""
                        + esc(coords.namespace()) + "\",\"" + esc(coords.targetClass()) + "\",\""
                        + esc(accessor.member) + "\")";
                out.write("    private static final dev.frostbite.api.Il2Cpp.Target T" + index + " = " + target + ";\n");
                index++;
            }
            index = 0;
            for (Accessor accessor : list) {
                writeAccessorMethod(out, accessor, "T" + index);
                index++;
            }
            out.write("}\n");
        }
    }

    private void writeAccessorMethod(Writer out, Accessor accessor, String target) throws IOException {
        ExecutableElement method = accessor.method;
        List<? extends VariableElement> params = method.getParameters();
        StringBuilder signature = new StringBuilder();
        signature.append("    public ").append(method.getReturnType()).append(' ')
                .append(method.getSimpleName()).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) signature.append(", ");
            signature.append(params.get(i).asType()).append(" p").append(i);
        }
        signature.append(") {\n");
        out.write(signature.toString());

        String self = accessor.isStatic ? "null" : "(dev.frostbite.api.Il2CppObject) p0";
        String body;
        switch (accessor.kind) {
            case FIELD_GET ->
                body = "dev.frostbite.api.Il2Cpp.get" + cap(accessor.type) + "(" + self + ", " + target + ")";
            case FIELD_SET -> {
                int valueParam = accessor.isStatic ? 0 : 1;
                String value = accessor.type.equals("OBJECT")
                        ? "(dev.frostbite.api.Il2CppObject) p" + valueParam : "p" + valueParam;
                body = "dev.frostbite.api.Il2Cpp.set" + cap(accessor.type) + "(" + self + ", " + target + ", " + value + ")";
            }
            default -> {
                StringBuilder call = new StringBuilder("dev.frostbite.api.Il2Cpp.invoke")
                        .append(cap(accessor.type)).append('(').append(self).append(", ").append(target);
                int firstArg = accessor.isStatic ? 0 : 1;
                for (int i = firstArg; i < params.size(); i++) call.append(", p").append(i);
                call.append(')');
                body = call.toString();
            }
        }
        boolean returns = method.getReturnType().getKind() != TypeKind.VOID;
        out.write("        " + (returns ? "return " : "") + body + ";\n    }\n");
    }

    private static String cap(String type) {
        return switch (type) {
            case "INT" -> "Int";
            case "LONG" -> "Long";
            case "FLOAT" -> "Float";
            case "DOUBLE" -> "Double";
            case "BOOL" -> "Bool";
            case "OBJECT" -> "Object";
            case "VOID" -> "Void";
            default -> throw new IllegalStateException(type);
        };
    }

    private static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    // ---- manifests ------------------------------------------------------

    private void writeManifest() {
        written = true;
        hooks.sort(Comparator.comparing(Hook::target).thenComparing(Hook::selector)
                .thenComparing(h -> h.at().name()).thenComparing(Hook::callbackClass).thenComparing(Hook::callbackMethod));
        try (Writer out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                "META-INF/frostbite/hooks.json").openWriter()) {
            out.write("{\n  \"schemaVersion\": 1,\n  \"hooks\": [\n");
            for (int i = 0; i < hooks.size(); i++) {
                Hook h = hooks.get(i);
                out.write("    {\"assembly\":\"" + json(h.assembly()) + "\",\"namespace\":\"" + json(h.namespace())
                        + "\",\"class\":\"" + json(h.targetClass()) + "\",\"method\":\"" + json(h.selector())
                        + "\",\"at\":\"" + h.at().name() + "\",\"callbackClass\":\"" + json(h.callbackClass())
                        + "\",\"callbackMethod\":\"" + json(h.callbackMethod()) + "\",\"mode\":\"" + h.mode()
                        + "\",\"priority\":" + h.priority() + ",\"static\":" + h.isStatic() + ",\"parameters\":[");
                for (int p = 0; p < h.parameters().size(); p++) {
                    if (p > 0) out.write(",");
                    out.write("\"" + json(h.parameters().get(p)) + "\"");
                }
                out.write("]}" + (i + 1 == hooks.size() ? "\n" : ",\n"));
            }
            out.write("  ]\n}\n");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not generate Frostbite hook manifest: " + e.getMessage());
        }
        if (!impls.isEmpty()) {
            impls.sort(Comparator.comparing(MixinImpl::mixin));
            try (Writer out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/frostbite/mixins.json").openWriter()) {
                out.write("{\"schemaVersion\":1,\"mixins\":[");
                for (int i = 0; i < impls.size(); i++) {
                    MixinImpl m = impls.get(i);
                    out.write((i > 0 ? "," : "") + "{\"class\":\"" + json(m.mixin()) + "\",\"impl\":\"" + json(m.impl()) + "\"}");
                }
                out.write("]}\n");
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Could not generate Frostbite mixins manifest: " + e.getMessage());
            }
        }
        if (mod != null) {
            try (Writer out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/frostbite/mod.json").openWriter()) {
                out.write("{\"schemaVersion\":1,\"id\":\"" + json(mod.id()) + "\",\"version\":\"" + json(mod.version())
                        + "\",\"apiVersion\":1,\"entrypoint\":\"" + json(mod.entrypoint())
                        + "\",\"hooks\":\"META-INF/frostbite/hooks.json\"}\n");
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Could not generate Frostbite mod descriptor: " + e.getMessage());
            }
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String esc(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void error(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record Hook(String assembly, String namespace, String targetClass, String selector,
                        At at, String callbackClass, String callbackMethod, String mode, int priority,
                        boolean isStatic, List<String> parameters) {
        String target() { return assembly + "." + namespace + "." + targetClass; }
    }
    private record Mod(String id, String version, String entrypoint) {}
    private record MixinImpl(String mixin, String impl) {}

    private static final class Accessor {
        enum Kind { FIELD_GET, FIELD_SET, INVOKE }
        final ExecutableElement method;
        final Kind kind;
        final boolean isStatic;
        final String member;   // field name or method selector
        final String type;     // INT|FLOAT|BOOL|OBJECT (or VOID for void invokers)
        final List<String> argTypes;
        Accessor(ExecutableElement method, Kind kind, boolean isStatic, String member, String type, List<String> argTypes) {
            this.method = method; this.kind = kind; this.isStatic = isStatic;
            this.member = member; this.type = type; this.argTypes = argTypes;
        }
    }
}
