package dev.frostbite.processor;

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
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@SupportedAnnotationTypes({"dev.frostbite.api.Mixin", "dev.frostbite.api.Inject", "dev.frostbite.api.FrostbiteMod"})
public final class MixinProcessor extends AbstractProcessor {
    private final List<Hook> hooks = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
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
        for (Element element : round.getElementsAnnotatedWith(Inject.class)) {
            if (!(element instanceof ExecutableElement callback)) {
                error(element, "@Inject can only annotate a method");
                continue;
            }
            Element parent = callback.getEnclosingElement();
            Mixin mixin = parent.getAnnotation(Mixin.class);
            Inject inject = callback.getAnnotation(Inject.class);
            if (mixin == null) {
                error(callback, "@Inject method must be inside a @Mixin class");
                continue;
            }
            if (callback.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID) {
                error(callback, "Injection callbacks must return void");
                continue;
            }
            String selector = inject.method().trim();
            try {
                new MethodSelector(selector, inject.mode());
            } catch (IllegalArgumentException badSelector) {
                error(callback, badSelector.getMessage());
                continue;
            }
            if (!callback.getModifiers().contains(Modifier.PUBLIC) ||
                    !parent.getModifiers().contains(Modifier.PUBLIC)) {
                error(callback, "Mixin class and injection callback must be public");
                continue;
            }
            String key = parent + "#" + callback.getSimpleName() + callback.getParameters();
            if (!seen.add(key)) {
                error(callback, "Duplicate injection callback");
                continue;
            }
            hooks.add(new Hook(mixin.assembly(), mixin.namespace(), mixin.targetClass(), selector,
                    inject.at(), processingEnv.getElementUtils().getBinaryName((TypeElement) parent).toString(),
                    callback.getSimpleName().toString(), inject.mode().name(), inject.priority(),
                    callback.getModifiers().contains(Modifier.STATIC),
                    callback.getParameters().stream().map(p -> p.asType().toString()).toList()));
        }
        if (round.processingOver() && !written && !invalid) writeManifest();
        return true;
    }

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
}
