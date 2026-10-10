package modules.bxjruby;

import java.io.StringReader;
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
    private RubyOutput stdout;
    private RubyOutput stderr;
    private volatile boolean closing;
    private boolean closed;

    /**
     * Creates a lazy owned session with immutable options and per-operation output capture.
     *
     * @param manager the module activation owning this session
     * @param loader the module class loader used for JRuby libraries and execution
     * @param options the immutable resolved session configuration
     * @param handles whether unsupported Ruby results may become owned handles
     */
    RubySession(RubyManager manager, ClassLoader loader, RubyOptions options, boolean handles) {
        this.manager = manager;
        this.loader = loader;
        this.options = options;
        this.handles = handles;
        stdout = new RubyOutput(options.outputLimit());
        stderr = new RubyOutput(options.outputLimit());
    }

    /**
     * Rejects new operations after closing has begun.
     *
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if closing has begun
     */
    private void checkOpen() {
        if (closing || closed) throw new BoxRuntimeException("JRuby session is closed");
    }

    /**
     * Lazily creates the JRuby container with the module loader, resolved paths and a fresh
     * process-environment overlay.
     *
     * @return the session's scripting container
     */
    private ScriptingContainer engine() {
        if (container == null) {
            container = new ScriptingContainer(LocalContextScope.SINGLETHREAD, LocalVariableBehavior.PERSISTENT);
            container.setClassLoader(loader);
            container.setCurrentDirectory(options.workingDirectory().toString());
            container.setInput(new java.io.ByteArrayInputStream(new byte[0]));
            container.setOutput(stdout);
            container.setError(stderr);
            var environment = new LinkedHashMap<String, String>(System.getenv());
            environment.putAll(options.env());
            if (options.gemHome() != null) environment.put("GEM_HOME", options.gemHome());
            if (!options.gemPaths().isEmpty()) environment.put("GEM_PATH", String.join(java.io.File.pathSeparator, options.gemPaths()));
            container.setEnvironment(environment);
            // These are added to JRuby's default paths, retaining its embedded stdlib.
            if (!options.loadPaths().isEmpty()) container.setLoadPaths(options.loadPaths());
        }
        return container;
    }

    /**
     * Executes with the module context loader and a fresh capture, converting the result before returning.
     * Failures retain output and the original cause.
     *
     * @param source the Ruby source or diagnostic source label
     * @param operation the Ruby operation evaluated under the module context loader
     * @return a struct containing value, stdout and stderr
     * @throws RubyExecutionException if execution or result conversion fails
     */
    private IStruct run(String source, Supplier<Object> operation) {
        checkOpen();
        stdout = new RubyOutput(options.outputLimit());
        stderr = new RubyOutput(options.outputLimit());
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

    /**
     * Evaluates source in this serialized session. Supplied bindings update locals and runtime state persists
     * between operations.
     *
     * @param script the Ruby source to evaluate
     * @return a struct containing the converted value and operation stdout/stderr
     * @throws RubyExecutionException if execution, bindings or result conversion fail
     */
    public IStruct eval(String script) {
        return eval(script, Map.of());
    }

    /**
     * Evaluates source in this serialized session. Supplied bindings update locals and runtime state persists
     * between operations.
     *
     * @param script the Ruby source to evaluate
     * @param bindings explicit Ruby local bindings, leaving other session locals intact
     * @return a struct containing the converted value and operation stdout/stderr
     * @throws RubyExecutionException if execution, bindings or result conversion fail
     */
    public synchronized IStruct eval(String script, Map<?, ?> bindings) {
        return run("(eval)", () -> {
            bind(bindings);
            return engine().parse(new StringReader(script), "(eval)").run();
        });
    }

    /**
     * Evaluates a file in this serialized session. Relative paths resolve from its working directory and
     * supplied bindings update locals.
     *
     * @param path the absolute Ruby file path or a path relative to the working directory
     * @return a struct containing the file result and operation stdout/stderr
     * @throws RubyExecutionException if file execution, bindings or result conversion fail
     */
    public IStruct evalFile(String path) {
        return evalFile(path, Map.of());
    }

    /**
     * Evaluates a file in this serialized session. Relative paths resolve from its working directory and
     * supplied bindings update locals.
     *
     * @param path the absolute Ruby file path or a path relative to the working directory
     * @param bindings explicit Ruby local bindings, leaving other session locals intact
     * @return a struct containing the file result and operation stdout/stderr
     * @throws RubyExecutionException if file execution, bindings or result conversion fail
     */
    public synchronized IStruct evalFile(String path, Map<?, ?> bindings) {
        Path supplied = Path.of(path);
        Path target = (supplied.isAbsolute() ? supplied : options.workingDirectory().resolve(supplied)).normalize();
        return run(target.toString(), () -> {
            if (!Files.isRegularFile(target)) throw new BoxRuntimeException("Ruby file not found: " + target);
            bind(bindings);
            return engine().parse(PathType.ABSOLUTE, target.toString()).run();
        });
    }

    /**
     * Validates names and converts all values before updating locals, preventing partial updates on
     * conversion failure.
     *
     * @param bindings explicit Ruby local bindings, leaving other session locals intact
     */
    private void bind(Map<?, ?> bindings) {
        var normalized = RubyOptions.normalize(bindings);
        normalized.keySet().forEach(RubySession::validateName);
        // Convert every input first so invalid handles cannot partially update bindings.
        var converted = new LinkedHashMap<String, Object>();
        normalized.forEach((name, value) -> converted.put(name, RubyValues.toRuby(value, this, engine().getProvider().getRuntime())));
        converted.forEach((name, value) -> engine().put(name, value));
    }

    /**
     * Sets a Ruby local using evaluation binding ownership and conversion rules.
     *
     * @param name the Ruby local-variable identifier
     * @param value the value to convert or write
     * @throws RubyExecutionException if the variable name or value is invalid
     */
    public synchronized void put(String name, Object value) {
        run("put(" + name + ")", () -> { bind(java.util.Collections.singletonMap(name, value)); return null; });
    }

    /**
     * Reads and converts a local under this session's ownership rules.
     *
     * @param name the Ruby local-variable identifier
     * @return the converted value, or null for an absent or nil local
     */
    public synchronized Object get(String name) {
        checkOpen();
        validateName(name);
        return run("get(" + name + ")", () -> engine().get(name)).get("value");
    }

    /**
     * Invokes a Ruby method in this serialized session. A null receiver targets top-level methods; positional
     * and keyword arguments stay separate.
     *
     * @param receiver the method receiver, or null for a top-level method
     * @param method the Ruby method name
     * @return a struct containing the method result and operation stdout/stderr
     * @throws RubyExecutionException if invocation or argument/result conversion fails
     */
    public IStruct call(Object receiver, String method) {
        return call(receiver, method, java.util.List.of(), Map.of());
    }

    /**
     * Invokes a Ruby method in this serialized session. A null receiver targets top-level methods; positional
     * and keyword arguments stay separate.
     *
     * @param receiver the method receiver, or null for a top-level method
     * @param method the Ruby method name
     * @param args positional arguments, preserving positional hashes
     * @return a struct containing the method result and operation stdout/stderr
     * @throws RubyExecutionException if invocation or argument/result conversion fails
     */
    public IStruct call(Object receiver, String method, java.util.List<?> args) {
        return call(receiver, method, args, Map.of());
    }

    /**
     * Invokes a Ruby method in this serialized session. A null receiver targets top-level methods; positional
     * and keyword arguments stay separate.
     *
     * @param receiver the method receiver, or null for a top-level method
     * @param method the Ruby method name
     * @param args positional arguments, preserving positional hashes
     * @param kwargs the keyword arguments, separate from positional arguments
     * @return a struct containing the method result and operation stdout/stderr
     * @throws RubyExecutionException if invocation or argument/result conversion fails
     */
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

    /**
     * Tracks an opaque Ruby value handle without retaining handles discarded by callers.
     *
     * @param value the value to convert or write
     * @return a handle valid only in this session and activation
     */
    RubyObject handle(IRubyObject value) {
        var handle = new RubyObject(this, value);
        objects.add(handle);
        return handle;
    }

    /**
     * Checks ownership and lifetime before exposing a handle's Ruby value internally.
     *
     * @param handle the opaque Ruby handle to validate and unwrap
     * @return the live Ruby value
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if the handle is foreign or closed
     */
    IRubyObject unwrap(RubyObject handle) {
        checkOpen();
        if (handle.owner != this || handle.value == null) throw new BoxRuntimeException("Ruby object belongs to another or closed JRuby session");
        return handle.value;
    }

    /**
     * Validates a Ruby local-variable identifier before passing it to JRuby.
     *
     * @param name the candidate Ruby local-variable identifier
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if the name is not a Ruby local
     * identifier
     */
    private static void validateName(String name) {
        if (name == null || !name.matches("[a-z_][a-zA-Z0-9_]*"))
            throw new BoxRuntimeException("Ruby bindings require local variable names: " + name);
    }

    /**
     * Updates a result or failure with final captures, including exit-handler output produced during close.
     *
     * @param result the evaluation result to update
     */
    synchronized void snapshotOutput(IStruct result) {
        result.put("stdout", stdout.toString());
        result.put("stderr", stderr.toString());
    }

    /**
     * Updates a result or failure with final captures, including exit-handler output produced during close.
     *
     * @param error the execution failure to update
     */
    synchronized void snapshotOutput(RubyExecutionException error) {
        error.captureFinalOutput(stdout.toString(), stderr.toString());
    }

    /**
     * Reports whether closing has begun, even while an active operation is finishing.
     *
     * @return true once new operations are rejected
     */
    public boolean isClosed() {
        return closing;
    }

    /**
     * Closes with a fresh exit-handler capture, discarding earlier operation output on the first close.
     * New work is rejected before waiting for an active operation. Repeated calls return the final capture
     * without rerunning handlers.
     *
     * @return a struct containing exit-handler stdout and stderr
     */
    public IStruct closeAndCapture() {
        closing = true;
        synchronized (this) {
            if (!closed) {
                stdout = new RubyOutput(options.outputLimit());
                stderr = new RubyOutput(options.outputLimit());
                if (container != null) {
                    container.setWriter(stdout);
                    container.setErrorWriter(stderr);
                }
                close();
            }
            return Struct.of("stdout", stdout.toString(), "stderr", stderr.toString());
        }
    }

    /**
     * Idempotently rejects new work, waits for the active operation, terminates Ruby and invalidates handles.
     * Manager ownership is released even on failure; active Ruby is not forcibly cancelled.
     *
     * @throws RubyExecutionException if termination fails after cleanup is attempted
     */
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
