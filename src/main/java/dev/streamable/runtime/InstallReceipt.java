package dev.streamable.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Written into an install directory only after its archive verified and fully
 * extracted. Its presence (with a matching digest) is what "installed" means,
 * so a half-extracted directory left by a crash is never mistaken for a
 * working runtime.
 *
 * @param component   component id
 * @param version     installed version
 * @param platform    platform id the artifact was built for
 * @param sha256      digest of the archive the files came from
 * @param installedAt epoch milliseconds
 */
public record InstallReceipt(String component, String version, String platform, String sha256, long installedAt) {

    public static final String FILE_NAME = ".stream-able-install.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Optional<InstallReceipt> read(Path installDirectory) {
        Path file = installDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            InstallReceipt receipt = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), InstallReceipt.class);
            if (receipt == null || receipt.component() == null || receipt.version() == null || receipt.sha256() == null) {
                return Optional.empty();
            }
            return Optional.of(receipt);
        } catch (IOException | JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    public void write(Path installDirectory) throws IOException {
        Files.writeString(installDirectory.resolve(FILE_NAME), GSON.toJson(this), StandardCharsets.UTF_8);
    }

    /** Whether this receipt certifies exactly the pinned artifact. */
    public boolean matches(RuntimeDescriptor descriptor, RuntimeArtifact artifact) {
        return descriptor.id().equals(component)
                && descriptor.version().equals(version)
                && Sha256.matches(artifact.sha256(), sha256);
    }
}
