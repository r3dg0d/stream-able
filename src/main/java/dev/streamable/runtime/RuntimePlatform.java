package dev.streamable.runtime;

import java.util.Locale;

/**
 * The operating system and CPU architecture a runtime artifact is built for.
 *
 * <p>Identifiers are stable strings ({@code linux-x86_64}, {@code windows-x86_64},
 * ...) because they key the pinned runtime manifest. {@link #ANY} marks
 * platform-independent artifacts such as AI model weights.</p>
 *
 * @param os   operating system family
 * @param arch CPU architecture
 */
public record RuntimePlatform(Os os, Arch arch) {

    public enum Os { LINUX, WINDOWS, MACOS, ANY, UNKNOWN }

    public enum Arch { X86_64, AARCH64, ANY, UNKNOWN }

    public static final RuntimePlatform ANY = new RuntimePlatform(Os.ANY, Arch.ANY);
    public static final RuntimePlatform LINUX_X86_64 = new RuntimePlatform(Os.LINUX, Arch.X86_64);
    public static final RuntimePlatform WINDOWS_X86_64 = new RuntimePlatform(Os.WINDOWS, Arch.X86_64);

    /** The platform of the running JVM. */
    public static RuntimePlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Pure detection, separated from system properties so it can be tested. */
    public static RuntimePlatform detect(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        Os detectedOs;
        if (os.contains("win")) {
            detectedOs = Os.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            detectedOs = Os.MACOS;
        } else if (os.contains("linux")) {
            detectedOs = Os.LINUX;
        } else {
            detectedOs = Os.UNKNOWN;
        }
        Arch detectedArch = switch (arch) {
            case "amd64", "x86_64", "x64", "x86-64" -> Arch.X86_64;
            case "aarch64", "arm64" -> Arch.AARCH64;
            default -> Arch.UNKNOWN;
        };
        return new RuntimePlatform(detectedOs, detectedArch);
    }

    /** Parses an identifier such as {@code linux-x86_64}; {@code any} for {@link #ANY}. */
    public static RuntimePlatform parse(String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("any")) {
            return ANY;
        }
        String[] parts = id.toLowerCase(Locale.ROOT).split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed platform id: " + id);
        }
        Os os = switch (parts[0]) {
            case "linux" -> Os.LINUX;
            case "windows" -> Os.WINDOWS;
            case "macos" -> Os.MACOS;
            default -> throw new IllegalArgumentException("Unknown OS in platform id: " + id);
        };
        Arch arch = switch (parts[1]) {
            case "x86_64" -> Arch.X86_64;
            case "aarch64" -> Arch.AARCH64;
            default -> throw new IllegalArgumentException("Unknown architecture in platform id: " + id);
        };
        return new RuntimePlatform(os, arch);
    }

    public String id() {
        if (this.equals(ANY)) {
            return "any";
        }
        return os.name().toLowerCase(Locale.ROOT) + "-" + arch.name().toLowerCase(Locale.ROOT);
    }

    public boolean isWindows() {
        return os == Os.WINDOWS;
    }

    public boolean isKnown() {
        return os != Os.UNKNOWN && arch != Arch.UNKNOWN;
    }

    /** Whether an artifact built for {@code target} runs here. */
    public boolean accepts(RuntimePlatform target) {
        return target.equals(ANY) || target.equals(this);
    }

    /** Human-readable name for the Runtime page. */
    public String displayName() {
        if (this.equals(ANY)) {
            return "Any platform";
        }
        String osName = switch (os) {
            case LINUX -> "Linux";
            case WINDOWS -> "Windows";
            case MACOS -> "macOS";
            default -> "Unknown OS";
        };
        String archName = switch (arch) {
            case X86_64 -> "x86-64";
            case AARCH64 -> "ARM64";
            default -> "unknown CPU";
        };
        return osName + " " + archName;
    }

    @Override
    public String toString() {
        return id();
    }
}
