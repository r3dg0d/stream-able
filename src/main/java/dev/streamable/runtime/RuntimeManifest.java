package dev.streamable.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pinned list of runtime components this build of Stream-able may install.
 *
 * <p>The manifest ships <em>inside</em> the mod jar and is never fetched from
 * the network: a remote manifest would let whoever controls that URL decide
 * which binaries players execute. Updating a runtime therefore means releasing a
 * new Stream-able version with new pins, which is exactly the review point it
 * deserves.</p>
 */
public final class RuntimeManifest {

    public static final String RESOURCE = "/assets/streamable/runtime/manifest.json";
    public static final int SUPPORTED_SCHEMA = 1;

    private final Map<String, RuntimeDescriptor> components;

    private RuntimeManifest(Map<String, RuntimeDescriptor> components) {
        this.components = Map.copyOf(components);
    }

    /** Loads the manifest bundled with the mod. */
    public static RuntimeManifest loadBundled() {
        try (InputStream in = RuntimeManifest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Runtime manifest is missing from the mod jar: " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled runtime manifest", e);
        }
    }

    public static RuntimeManifest parse(String json) {
        return parse(new java.io.StringReader(json));
    }

    /**
     * Parses and validates a manifest.
     *
     * @throws IllegalArgumentException on any structural problem; a manifest is
     *                                  all-or-nothing, never partially trusted
     */
    public static RuntimeManifest parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Runtime manifest must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        int schema = object.has("schema") ? object.get("schema").getAsInt() : 0;
        if (schema != SUPPORTED_SCHEMA) {
            throw new IllegalArgumentException("Unsupported runtime manifest schema: " + schema);
        }
        JsonObject componentsJson = object.getAsJsonObject("components");
        if (componentsJson == null) {
            throw new IllegalArgumentException("Runtime manifest has no components");
        }
        Map<String, RuntimeDescriptor> components = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : componentsJson.entrySet()) {
            JsonObject component = entry.getValue().getAsJsonObject();
            List<RuntimeArtifact> artifacts = new ArrayList<>();
            for (JsonElement artifactJson : component.getAsJsonArray("artifacts")) {
                artifacts.add(parseArtifact(entry.getKey(), artifactJson.getAsJsonObject()));
            }
            RuntimeDescriptor descriptor = new RuntimeDescriptor(
                    entry.getKey(),
                    string(component, "displayName", entry.getKey()),
                    string(component, "version", null),
                    string(component, "license", ""),
                    string(component, "source", ""),
                    artifacts);
            components.put(descriptor.id(), descriptor);
        }
        return new RuntimeManifest(components);
    }

    private static RuntimeArtifact parseArtifact(String componentId, JsonObject json) {
        List<URI> urls = new ArrayList<>();
        JsonArray urlArray = json.getAsJsonArray("urls");
        if (urlArray == null) {
            throw new IllegalArgumentException(componentId + ": artifact has no urls");
        }
        for (JsonElement url : urlArray) {
            urls.add(URI.create(url.getAsString()));
        }
        return new RuntimeArtifact(
                RuntimePlatform.parse(string(json, "platform", "any")),
                urls,
                string(json, "sha256", null),
                json.has("size") ? json.get("size").getAsLong() : -1,
                RuntimeArtifact.Format.parse(string(json, "format", "file")),
                string(json, "fileName", null),
                json.has("stripComponents") ? json.get("stripComponents").getAsInt() : 0,
                strings(json, "include"),
                strings(json, "executables"));
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            if (fallback == null) {
                throw new IllegalArgumentException("Runtime manifest entry is missing '" + key + "'");
            }
            return fallback;
        }
        return element.getAsString();
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonArray array = json.getAsJsonArray(key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    public Optional<RuntimeDescriptor> component(String id) {
        return Optional.ofNullable(components.get(id));
    }

    /** Looks up a component that the code requires to exist. */
    public RuntimeDescriptor require(String id) {
        RuntimeDescriptor descriptor = components.get(id);
        if (descriptor == null) {
            throw new IllegalStateException("Runtime component '" + id + "' is not in the manifest");
        }
        return descriptor;
    }

    public Map<String, RuntimeDescriptor> components() {
        return components;
    }
}
