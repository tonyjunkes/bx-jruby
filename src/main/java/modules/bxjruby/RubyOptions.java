package modules.bxjruby;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/**
 * Immutable configuration resolved before a Ruby runtime is initialized.
 *
 * @param workingDirectory the normalized physical working directory
 * @param loadPaths the additional absolute Ruby load paths
 * @param gemHome the absolute GEM_HOME override, or null when inherited
 * @param gemPaths the absolute GEM_PATH entries, empty when inherited
 * @param env the immutable overlay applied to a fresh copy of the process environment
 * @param outputLimit the retained characters per stream and operation, zero for unlimited capture
 */
record RubyOptions(
    Path workingDirectory,
    List<String> loadPaths,
    String gemHome,
    List<String> gemPaths,
    Map<String, String> env,
    int outputLimit
) {
    private static final Set<String> SUPPORTED = Set.of("workingdirectory", "loadpaths", "gemhome", "gempaths", "env", "outputlimit");

    /**
     * Copies keys to strings, preserving case and rejecting invalid types or conversion collisions.
     *
     * @param values the supplied configuration values
     * @return a mutable map with normalized string keys
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if keys have invalid types or
     * conversion collisions
     */
    static Map<String, Object> normalize(Map<?, ?> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> {
            if (!(key instanceof CharSequence) && !(key instanceof Key))
                throw new BoxRuntimeException("JRuby argument keys must be strings");
            String normalized = name(key);
            if (result.containsKey(normalized))
                throw new BoxRuntimeException("JRuby argument key conversion collision: " + normalized);
            result.put(normalized, value);
        });
        return result;
    }

    /**
     * Converts a validated BoxLang key or character sequence to its spelling.
     *
     * @param key a validated BoxLang Key or character sequence
     * @return the key name without case normalization
     */
    static String name(Object key) {
        return key instanceof Key k ? k.getName() : key.toString();
    }

    /**
     * Merges defaults and overrides and resolves immutable paths, environment values and limits. Overrides
     * replace corresponding default values.
     *
     * @param defaults the module-level default options
     * @param overrides the call-level options replacing corresponding defaults
     * @param base the physical base directory for relative paths
     * @return the validated session options
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if options, paths or environment
     * values are invalid
     */
    static RubyOptions resolve(Map<?, ?> defaults, Map<?, ?> overrides, Path base) {
        var lookup = optionNames(defaults);
        lookup.putAll(optionNames(overrides));
        Object configuredDirectory = lookup.get("workingdirectory");
        Path directory = configuredDirectory == null || configuredDirectory.toString().isBlank()
            ? base.toAbsolutePath().normalize() : path(base, configuredDirectory.toString());
        if (!Files.isDirectory(directory)) throw new BoxRuntimeException("JRuby workingDirectory is not a directory: " + directory);
        Object configuredGemHome = lookup.get("gemhome");
        String gemHome = configuredGemHome == null || configuredGemHome.toString().isBlank()
            ? null : path(directory, configuredGemHome.toString()).toString();
        var gemPaths = paths(directory, lookup.get("gempaths"));
        var environment = environment(lookup.get("env"));
        if (gemHome != null && environment.containsKey("GEM_HOME") && !gemHome.equals(environment.get("GEM_HOME")))
            throw new BoxRuntimeException("JRuby env.GEM_HOME conflicts with gemHome");
        if (!gemPaths.isEmpty() && environment.containsKey("GEM_PATH") &&
            !String.join(java.io.File.pathSeparator, gemPaths).equals(environment.get("GEM_PATH")))
            throw new BoxRuntimeException("JRuby env.GEM_PATH conflicts with gemPaths");
        int outputLimit = 0;
        if (lookup.containsKey("outputlimit")) {
            try { outputLimit = Integer.parseInt(lookup.get("outputlimit").toString()); }
            catch (RuntimeException error) { throw new BoxRuntimeException("JRuby outputLimit must be a non-negative integer"); }
            if (outputLimit < 0) throw new BoxRuntimeException("JRuby outputLimit must be a non-negative integer");
        }
        return new RubyOptions(directory, paths(directory, lookup.get("loadpaths")), gemHome, gemPaths, environment, outputLimit);
    }

    /**
     * Validates environment names and string values without mutating the process environment.
     *
     * @param value the environment map, or null when omitted
     * @return an immutable overlay, empty when omitted
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if names or values are invalid
     * environment strings
     */
    private static Map<String, String> environment(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new BoxRuntimeException("JRuby env must be a map of strings");
        var result = new LinkedHashMap<String, String>();
        normalize(map).forEach((key, entry) -> {
            if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf('\0') >= 0 ||
                !(entry instanceof String text) || text.indexOf('\0') >= 0)
                throw new BoxRuntimeException("JRuby env requires valid names and string values");
            result.put(key, (String) entry);
        });
        return Map.copyOf(result);
    }

    /**
     * Normalizes option names case-insensitively and rejects unknown or duplicate options.
     *
     * @param values the supplied configuration values
     * @return a mutable map with canonical option keys
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if an option is unknown or
     * duplicated
     */
    private static Map<String, Object> optionNames(Map<?, ?> values) {
        var result = new LinkedHashMap<String, Object>();
        normalize(values).forEach((name, value) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (!SUPPORTED.contains(key)) throw new BoxRuntimeException("Unknown JRuby option: " + name);
            if (result.containsKey(key)) throw new BoxRuntimeException("Duplicate JRuby option: " + name);
            result.put(key, value);
        });
        return result;
    }

    /**
     * Resolves a configured path against its base and normalizes it.
     *
     * @param base the physical base directory for relative paths
     * @param value the configured absolute or relative path string
     * @return the normalized absolute path
     */
    private static Path path(Path base, String value) {
        Path target = Path.of(value);
        return (target.isAbsolute() ? target : base.resolve(target)).toAbsolutePath().normalize();
    }

    /**
     * Resolves a configured path array against the session directory.
     *
     * @param directory the resolved session working directory
     * @param values the path array, or null when omitted
     * @return an immutable absolute-path list, empty when omitted
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if the value is not an array of
     * nonblank paths
     */
    private static List<String> paths(Path directory, Object values) {
        if (values == null) return List.of();
        if (!(values instanceof Iterable<?> entries)) throw new BoxRuntimeException("JRuby path options must be arrays");
        var result = new ArrayList<String>();
        for (Object entry : entries) {
            if (entry == null || entry.toString().isBlank()) throw new BoxRuntimeException("JRuby paths must be non-empty strings");
            result.add(path(directory, entry.toString()).toString());
        }
        return List.copyOf(result);
    }
}
