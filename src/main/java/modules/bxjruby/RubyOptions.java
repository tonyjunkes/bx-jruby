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

/** Immutable configuration resolved before a Ruby runtime is initialized. */
record RubyOptions(Path workingDirectory, List<String> loadPaths, String gemHome, List<String> gemPaths) {
    private static final Set<String> SUPPORTED = Set.of("workingdirectory", "loadpaths", "gemhome", "gempaths");

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

    static String name(Object key) {
        return key instanceof Key k ? k.getName() : key.toString();
    }

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
        return new RubyOptions(directory, paths(directory, lookup.get("loadpaths")), gemHome, paths(directory, lookup.get("gempaths")));
    }

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

    private static Path path(Path base, String value) {
        Path target = Path.of(value);
        return (target.isAbsolute() ? target : base.resolve(target)).toAbsolutePath().normalize();
    }

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
