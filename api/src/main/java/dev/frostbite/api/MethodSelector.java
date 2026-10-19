package dev.frostbite.api;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** A selector is matched against a whole canonical IL2CPP method signature. */
public final class MethodSelector {
    private static final Pattern EXACT = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*\\((?:self|static)(?:;[A-Za-z_$][A-Za-z0-9_.$\\[\\]]*)*\\)");
    private final String expression;
    private final SelectorMode mode;
    private final Pattern pattern;

    public MethodSelector(String expression, SelectorMode mode) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (mode == SelectorMode.EXACT && !EXACT.matcher(expression).matches())
            throw new IllegalArgumentException("Invalid exact selector: " + expression);
        try {
            pattern = mode == SelectorMode.REGEX ? Pattern.compile(expression) : null;
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("Invalid selector regex: " + expression, error);
        }
    }

    public boolean matches(String canonicalSignature) {
        return mode == SelectorMode.EXACT
                ? expression.equals(canonicalSignature)
                : pattern.matcher(canonicalSignature).matches();
    }

    public String expression() { return expression; }
    public SelectorMode mode() { return mode; }
}
