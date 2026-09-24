package dev.streamable.diagnostics;

import dev.streamable.util.SecretRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "Copy diagnostics" text for bug reports.
 *
 * <p>Built from plain values so it can be tested without a game. Every line
 * passes through {@link SecretRedactor} with every configured stream key as a
 * known secret, so a key cannot leak through an error message, a URL or a
 * device name - even one that happens to contain it.</p>
 */
public final class DiagnosticsReport {

    /**
     * @param sections    ordered sections of "label: value" lines
     * @param secrets     every stream key currently configured (never printed)
     */
    public record Input(Map<String, List<String>> sections, List<String> secrets) {
    }

    private DiagnosticsReport() {
    }

    public static String build(Input input) {
        String[] secrets = input.secrets().stream().filter(s -> s != null && !s.isEmpty()).toArray(String[]::new);
        List<String> lines = new ArrayList<>();
        lines.add("Stream-able diagnostics (stream keys and URL credentials removed)");
        for (Map.Entry<String, List<String>> section : input.sections().entrySet()) {
            lines.add("");
            lines.add("## " + section.getKey());
            for (String line : section.getValue()) {
                lines.add(SecretRedactor.redact(line == null ? "" : line, secrets));
            }
        }
        return String.join(System.lineSeparator(), lines);
    }
}
