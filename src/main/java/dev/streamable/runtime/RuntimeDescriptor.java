package dev.streamable.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Pinned description of one runtime component, as declared in the manifest.
 *
 * @param id          stable component id, e.g. {@code ffmpeg}; also the install directory name
 * @param displayName name shown on the Runtime page
 * @param version     pinned version string; changing it triggers an upgrade
 * @param license     licence summary shown to the user and in NOTICE
 * @param source      where the bytes come from, for the Runtime page
 * @param artifacts   one artifact per supported platform
 */
public record RuntimeDescriptor(String id, String displayName, String version, String license,
                                String source, List<RuntimeArtifact> artifacts) {

    public RuntimeDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid runtime component id: " + id);
        }
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")) {
            throw new IllegalArgumentException("Invalid runtime version for " + id + ": " + version);
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        license = license == null ? "" : license;
        source = source == null ? "" : source;
        artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
    }

    /** The artifact for a platform: an exact match first, then a platform-independent one. */
    public Optional<RuntimeArtifact> artifactFor(RuntimePlatform platform) {
        for (RuntimeArtifact artifact : artifacts) {
            if (artifact.platform().equals(platform)) {
                return Optional.of(artifact);
            }
        }
        for (RuntimeArtifact artifact : artifacts) {
            if (artifact.platform().equals(RuntimePlatform.ANY)) {
                return Optional.of(artifact);
            }
        }
        return Optional.empty();
    }

    public boolean supports(RuntimePlatform platform) {
        return artifactFor(platform).isPresent();
    }
}
