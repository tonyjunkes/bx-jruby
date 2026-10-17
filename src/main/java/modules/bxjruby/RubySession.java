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
    private volatile boolean inCallback;
    private int operationDepth;
    private boolean drainPending;

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
        if (closing) {
            throw new BoxRuntimeException("JRuby session is closed");
        }
    }

    /**
     * Rejects callback reentry and new work during shutdown before any session monitor acquisition.
     *
     * @throws BoxRuntimeException if a callback is active or shutdown has begun
     */
    private void checkOperation() {
        if (inCallback) {
            throw new BoxRuntimeException("Cannot reenter the owning JRuby session from its block callback");
        }
        checkOpen();
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
            container.setInput(new java.io.ByteArrayInputStream(options.stdin()));
            container.setArgv(options.argv().toArray(String[]::new));
            container.setOutput(stdout);
            container.setError(stderr);
            var environment = new LinkedHashMap<String, String>(System.getenv());
            environment.putAll(options.env());
            if (options.gemHome() != null) environment.put("GEM_HOME", options.gemHome());
            if (!options.gemPaths().isEmpty()) environment.put("GEM_PATH", String.join(java.io.File.pathSeparator, options.gemPaths()));
            container.setEnvironment(environment);
            // These are added to JRuby's default paths, retaining its embedded stdlib.
            if (!options.loadPaths().isEmpty()) container.setLoadPaths(options.loadPaths());
            if (options.gemfile() != null) {
                try {
                    container.runScriptlet("""
                        begin
                          require 'bundler'
                          # Bundler may reset Settings during setup. Suppress automatic
                          # installers/re-exec at their entry points as well as settings.
                          class << Bundler
                            def auto_install
                              nil
                            end
                            def auto_switch
                              nil
                            end
                          end
                          Bundler.settings.set_command_option(:auto_install, false)
                          Bundler.settings.set_command_option(:version, 'system')
                          require 'bundler/setup'
                          Bundler.settings.set_command_option(:auto_install, false)
                          Bundler.settings.set_command_option(:version, 'system')
                        rescue SystemExit => error
                          raise "Bundler setup attempted to exit with status #{error.status}"
                        end
                        nil
                        """);
                } catch (RuntimeException error) {
                    try {
                        container.terminate();
                    } catch (RuntimeException cleanup) {
                        error.addSuppressed(cleanup);
                    } finally {
                        container = null;
                    }
                    throw error;
                }
            }
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
        checkOperation();
        synchronized (this) {
            checkOperation();
            return runCaptured(source, operation);
        }
    }

    /**
     * Executes under the session monitor and completes reentrant activation shutdown after Ruby unwinds.
     *
     * @param source the diagnostic source label
     * @param operation the Ruby operation to capture and convert
     * @return the converted value and operation output
     * @throws RubyExecutionException if execution, conversion or deferred termination fails
     */
    private IStruct runCaptured(String source, Supplier<Object> operation) {
        stdout = new RubyOutput(options.outputLimit());
        stderr = new RubyOutput(options.outputLimit());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Throwable failure = null;
        operationDepth++;
        try {
            Thread.currentThread().setContextClassLoader(loader);
            if (container != null) {
                container.setWriter(stdout);
                container.setErrorWriter(stderr);
            }
            Object value = RubyValues.fromRuby(operation.get(), this, handles);
            return Struct.of("value", value, "stdout", stdout.toString(), "stderr", stderr.toString());
        } catch (RuntimeException error) {
            var wrapped = new RubyExecutionException(source, error, stdout.toString(), stderr.toString());
            failure = wrapped;
            throw wrapped;
        } catch (Error error) {
            failure = error;
            throw error;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            operationDepth--;
            if (operationDepth == 0 && drainPending) {
                try {
                    finishClose();
                } catch (RuntimeException | Error cleanup) {
                    if (failure != null) {
                        failure.addSuppressed(cleanup);
                    } else {
                        throw cleanup;
                    }
                }
            }
        }
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
    public IStruct eval(String script, Map<?, ?> bindings) {
        return run("(eval)", () -> {
            bind(bindings);
            return engine().parse(new StringReader(script), "(eval)").run();
        });
    }

    /**
     * Evaluates source while retaining even scalar results as session-owned handles.
     * @param script Ruby source
     * @param bindings explicit local bindings
     * @return the usual result envelope with an opaque handle in value
     */
    public IStruct evalHandle(String script, Map<?, ?> bindings) {
        return run("(eval handle)", () -> {
            bind(bindings);
            return handle((IRubyObject) engine().parse(new StringReader(script), "(eval)").run());
        });
    }

    /**
     * Converts a native value explicitly without losing Ruby type or binary encoding.
     * @param value the native value, ISO date/time string, or owned handle
     * @param type auto, binary, string, symbol, date, or time
     * @return a handle owned by this session
     */
    public RubyObject toRuby(Object value, String type) {
        return (RubyObject) run("toRuby(" + type + ")", () ->
            handle(RubyConversions.toRuby(value, type, this, engine().getProvider().getRuntime()))).get("value");
    }

    /**
     * Copies an owned handle using an explicit conversion without changing default result conversion.
     * @param value the handle owned by this session
     * @param type auto, binary, string, symbol, date, or time
     * @return a native value (date and time produce BoxLang DateTime values)
     */
    public Object toBoxLang(RubyObject value, String type) {
        return run("toBoxLang(" + type + ")", () -> RubyConversions.toBoxLang(unwrap(value), type, this)).get("value");
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
    public IStruct evalFile(String path, Map<?, ?> bindings) {
        checkOperation();
        Path supplied = Path.of(path);
        Path target = (supplied.isAbsolute() ? supplied : options.workingDirectory().resolve(supplied)).normalize();
        return run(target.toString(), () -> {
            if (!Files.isRegularFile(target)) {
                throw new BoxRuntimeException("Ruby file not found: " + target);
            }
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
    public void put(String name, Object value) {
        run("put(" + name + ")", () -> {
            bind(java.util.Collections.singletonMap(name, value));
            return null;
        });
    }

    /**
     * Reads and converts a local under this session's ownership rules.
     *
     * @param name the Ruby local-variable identifier
     * @return the converted value, or null for an absent or nil local
     */
    public Object get(String name) {
        checkOperation();
        synchronized (this) {
            checkOpen();
            validateName(name);
            return run("get(" + name + ")", () -> engine().get(name)).get("value");
        }
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
    public IStruct call(Object receiver, String method, java.util.List<?> args, Map<?, ?> kwargs) {
        return callWithBlock(receiver, method, args, kwargs, null);
    }

    /**
     * Invokes a method with an operation-scoped synchronous block callback.
     * @param receiver the receiver or null for top-level methods
     * @param method the Ruby method name
     * @param args positional arguments
     * @param kwargs keyword arguments
     * @param callback the callback, or null for no block; it must not reenter this session
     * @return the result envelope
     */
    public IStruct callWithBlock(Object receiver, String method, java.util.List<?> args, Map<?, ?> kwargs,
            java.util.function.Function<Object[], Object> callback) {
        return run("call(" + method + ")", () -> {
            var ruby = engine().getProvider().getRuntime();
            Object target = receiver == null ? null : RubyValues.toRuby(receiver, this, ruby);
            Object[] positional = args.stream().map(value -> RubyValues.toRuby(value, this, ruby)).toArray();
            var keywords = new LinkedHashMap<String, Object>();
            RubyOptions.normalize(kwargs).forEach((name, value) -> keywords.put(name, RubyValues.toRuby(value, this, ruby)));
            if (callback == null) {
                return keywords.isEmpty()
                    ? engine().callMethod(target, method, positional, IRubyObject.class)
                    : engine().callMethodWithKeywordArgs(target, method, positional, keywords, IRubyObject.class);
            }
            var lease = new RubyBlock(this, callback);
            try {
                var block = org.jruby.runtime.CallBlock.newCallClosure(ruby.getCurrentContext(), ruby.getTopSelf(),
                    org.jruby.runtime.Signature.OPTIONAL, (context, values, nested) -> lease.invoke(values));
                return engine().callMethodWithKeywordArgs(target, method, positional, keywords, block, IRubyObject.class);
            } finally {
                lease.close();
            }
        });
    }

    /**
     * Executes a validated block lease with the session reentry guard enabled.
     * @param callback the live operation callback
     * @param values the Ruby yield arguments
     * @return the callback result converted to Ruby
     */
    IRubyObject invokeBlock(java.util.function.Function<Object[], Object> callback, IRubyObject[] values) {
        Object[] converted = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            converted[i] = RubyValues.fromRuby(values[i], this, true);
        }
        inCallback = true;
        try {
            return RubyValues.toRuby(callback.apply(converted), this, engine().getProvider().getRuntime());
        } finally {
            inCallback = false;
        }
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
        // Shutdown rejects new operations, but the operation being drained may still return an owned value.
        if (closed || (closing && !(Thread.holdsLock(this) && operationDepth > 0))) {
            throw new BoxRuntimeException("JRuby session is closed");
        }
        if (handle.owner != this || handle.value == null) {
            throw new BoxRuntimeException("Ruby object belongs to another or closed JRuby session");
        }
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
     * @throws BoxRuntimeException if a block callback is active, including on another thread
     */
    public IStruct closeAndCapture() {
        rejectCallbackClose();
        closing = true;
        synchronized (this) {
            if (!closed) {
                stdout = new RubyOutput(options.outputLimit());
                stderr = new RubyOutput(options.outputLimit());
                if (container != null) {
                    container.setWriter(stdout);
                    container.setErrorWriter(stderr);
                }
                finishClose();
            }
            return Struct.of("stdout", stdout.toString(), "stderr", stderr.toString());
        }
    }

    /**
     * Idempotently rejects new work, waits for the active operation, terminates Ruby and invalidates handles.
     * Manager ownership is released even on failure; active Ruby is not forcibly cancelled.
     *
     * @throws RubyExecutionException if termination fails after cleanup is attempted
     * @throws BoxRuntimeException if a block callback is active, including on another thread
     */
    @Override
    public void close() {
        rejectCallbackClose();
        closing = true;
        synchronized (this) {
            finishClose();
        }
    }

    /** Marks an activation-owned session as draining without waiting for active Ruby. */
    void beginDrain() {
        closing = true;
    }

    /**
     * Completes manager-owned shutdown after active work, deferring termination on an active owning thread.
     * Public close methods retain their callback rejection; activation cleanup must not abandon a session.
     *
     * @throws RubyExecutionException if runtime termination fails after cleanup is attempted
     */
    void drain() {
        beginDrain();
        synchronized (this) {
            if (operationDepth > 0) {
                drainPending = true;
            } else {
                finishClose();
            }
        }
    }

    /**
     * Terminates Ruby and releases ownership exactly once while holding the session monitor.
     *
     * @throws RubyExecutionException if termination fails after handles and manager ownership are released
     */
    private void finishClose() {
        drainPending = false;
        if (closed) {
            return;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            if (container != null) {
                container.terminate();
            }
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

    /**
     * Rejects close before acquiring the session monitor while a callback may be waiting for its caller.
     *
     * @throws BoxRuntimeException if a block callback is active on the owning operation thread
     */
    private void rejectCallbackClose() {
        if (inCallback) {
            throw new BoxRuntimeException("Cannot close the owning JRuby session from its block callback");
        }
    }
}
