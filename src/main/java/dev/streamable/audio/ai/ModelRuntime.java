package dev.streamable.audio.ai;

import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One downloadable model file, stored under {@code runtime/<model-id>/<version>/}. */
public final class ModelRuntime extends ManagedRuntime {

    private final ModelSpec spec;

    public ModelRuntime(RuntimeContext context, ModelSpec spec) {
        super(context, context.manifest().require(spec.runtimeId()));
        this.spec = spec;
    }

    public ModelSpec spec() {
        return spec;
    }

    public Path modelFile() {
        return installDirectory().resolve(artifact().orElseThrow().fileName());
    }

    @Override
    protected void validateInstall(Path directory) throws IOException {
        if (!Files.isRegularFile(directory.resolve(artifact().orElseThrow().fileName()))) {
            throw new IOException(spec.name() + " model file missing");
        }
    }
}
