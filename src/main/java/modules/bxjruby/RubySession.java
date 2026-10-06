package modules.bxjruby;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;
import org.jruby.runtime.builtin.IRubyObject;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Explicitly owned, lazily initialized Ruby runtime. Public operations serialize. */
public final class RubySession implements AutoCloseable {
    private final RubyManager manager;
    private final ClassLoader loader;
    private final RubyOptions options;
    private final boolean handles;
    // Track live caller handles for close(), without retaining discarded Ruby values.
    private final Set<RubyObject> objects = Collections.newSetFromMap(new WeakHashMap<>());
    private ScriptingContainer container;
    private StringWriter stdout = new StringWriter();
    private StringWriter stderr = new StringWriter();
    private volatile boolean closing;
    private boolean closed;

    RubySession(RubyManager manager, ClassLoader loader, RubyOptions options, boolean handles) {
        this.manager = manager;
        this.loader = loader;
        this.options = options;
        this.handles = handles;
    }

    private void checkOpen() {
        if (closing || closed) throw new BoxRuntimeException("JRuby session is closed");
    }

    private ScriptingContainer engine() {
        if (container == null) {
            container = new ScriptingContainer(LocalContextScope.SINGLETHREAD, LocalVariableBehavior.PERSISTENT);
            container.setClassLoader(loader);
            container.setCurrentDirectory(options.workingDirectory().toString());
            container.setInput(new java.io.ByteArrayInputStream(new byte[0]));
            container.setOutput(stdout);
            container.setError(stderr);
            var environment = new LinkedHashMap<String, String>(System.getenv());
            if (options.gemHome() != null) environment.put("GEM_HOME", options.gemHome());
            if (!options.gemPaths().isEmpty()) environment.put("GEM_PATH", String.join(java.io.File.pathSeparator, options.gemPaths()));
            container.setEnvironment(environment);
            // These are added to JRuby's default paths, retaining its embedded stdlib.
            if (!options.loadPaths().isEmpty()) container.setLoadPaths(options.loadPaths());
        }
        return container;
    }

    private IStruct run(String source, Supplier<Object> operation) {
        checkOpen();
        stdout = new StringWriter();
        stderr = new StringWriter();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            if (container != null) {
                container.setWriter(stdout);
                container.setErrorWriter(stderr);
            }
            Object value = RubyValues.fromRuby(operation.get(), this, handles);
            return Struct.of("value", value, "stdout", stdout.toString(), "stderr", stderr.toString());
        } catch (RuntimeException error) {
            throw new RubyExecutionException(source, error, stdout.toString(), stderr.toString());
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    public IStruct eval(String script) {
        return eval(script, Map.of());
    }

    public synchronized IStruct eval(String script, Map<?, ?> bindings) {
        return run("(eval)", () -> {
            bind(bindings);
            return engine().parse(new StringReader(script), "(eval)").run();
        });
    }

    public IStruct evalFile(String path) {
        return evalFile(path, Map.of());
    }

    public synchronized IStruct evalFile(String path, Map<?, ?> bindings) {
        Path supplied = Path.of(path);
        Path target = (supplied.isAbsolute() ? supplied : options.workingDirectory().resolve(supplied)).normalize();
        return run(target.toString(), () -> {
            if (!Files.isRegularFile(target)) throw new BoxRuntimeException("Ruby file not found: " + target);
            bind(bindings);
            return engine().parse(PathType.ABSOLUTE, target.toString()).run();
        });
    }

    private void bind(Map<?, ?> bindings) {
        var normalized = RubyOptions.normalize(bindings);
        normalized.keySet().forEach(RubySession::validateName);
        // Convert every input first so invalid handles cannot partially update bindings.
        var converted = new LinkedHashMap<String, Object>();
        normalized.forEach((name, value) -> converted.put(name, RubyValues.toRuby(value, this, engine().getProvider().getRuntime())));
        converted.forEach((name, value) -> engine().put(name, value));
    }

    public synchronized void put(String name, Object value) {
        run("put(" + name + ")", () -> { bind(java.util.Collections.singletonMap(name, value)); return null; });
    }

    public synchronized Object get(String name) {
        checkOpen();
        validateName(name);
        return run("get(" + name + ")", () -> engine().get(name)).get("value");
    }

    public IStruct call(Object receiver, String method) {
        return call(receiver, method, java.util.List.of(), Map.of());
    }

    public IStruct call(Object receiver, String method, java.util.List<?> args) {
        return call(receiver, method, args, Map.of());
    }

    public synchronized IStruct call(Object receiver, String method, java.util.List<?> args, Map<?, ?> kwargs) {
        return run("call(" + method + ")", () -> {
            var ruby = engine().getProvider().getRuntime();
            Object target = receiver == null ? null : RubyValues.toRuby(receiver, this, ruby);
            Object[] positional = args.stream().map(value -> RubyValues.toRuby(value, this, ruby)).toArray();
            var keywords = new LinkedHashMap<String, Object>();
            RubyOptions.normalize(kwargs).forEach((name, value) -> keywords.put(name, RubyValues.toRuby(value, this, ruby)));
            return keywords.isEmpty()
                ? engine().callMethod(target, method, positional, IRubyObject.class)
                : engine().callMethodWithKeywordArgs(target, method, positional, keywords, IRubyObject.class);
        });
    }

    RubyObject handle(IRubyObject value) {
        var handle = new RubyObject(this, value);
        objects.add(handle);
        return handle;
    }

    IRubyObject unwrap(RubyObject handle) {
        checkOpen();
        if (handle.owner != this || handle.value == null) throw new BoxRuntimeException("Ruby object belongs to another or closed JRuby session");
        return handle.value;
    }

    private static void validateName(String name) {
        if (name == null || !name.matches("[a-z_][a-zA-Z0-9_]*"))
            throw new BoxRuntimeException("Ruby bindings require local variable names: " + name);
    }

    synchronized void snapshotOutput(IStruct result) {
        result.put("stdout", stdout.toString());
        result.put("stderr", stderr.toString());
    }

    synchronized void snapshotOutput(RubyExecutionException error) {
        error.captureFinalOutput(stdout.toString(), stderr.toString());
    }

    public boolean isClosed() {
        return closing;
    }

    @Override
    public void close() {
        closing = true;
        synchronized (this) {
            if (closed) return;
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                if (container != null) container.terminate();
            } catch (RuntimeException error) {
                throw new RubyExecutionException("close()", error, stdout.toString(), stderr.toString());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                closed = true;
                container = null;
                objects.forEach(object -> object.value = null);
                objects.clear();
                manager.release(this);
            }
        }
    }
}
