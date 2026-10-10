package modules.bxjruby;

import java.nio.file.Path;
import java.util.Map;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.modules.ModuleRecord;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;
import ortus.boxlang.runtime.util.FileSystemUtil;

/** Java runtime bridge activated by ModuleConfig.bx; owns all public Ruby sessions. */
public final class JRubyRuntime {
    private static volatile RubyManager manager;

    /**
     * Prevents construction of the static module bridge.
     */
    private JRubyRuntime() {}

    /**
     * Activates the core manager with this activation's loader and settings.
     *
     * @param record this activation's module record and settings
     */
    public static void start(ModuleRecord record) {
        manager = new RubyManager(record.getModuleClassLoader().toClassLoader(), record.settings);
    }

    /**
     * Closes the active manager and removes only descriptors owned by the named module, even when cleanup
     * fails.
     *
     * @param runtime the active BoxLang runtime
     * @param moduleName the runtime module name whose descriptors may be removed
     */
    public static void stop(BoxRuntime runtime, String moduleName) {
        RubyManager active = manager;
        manager = null;
        try { if (active != null) active.close(); }
        finally {
            var functions = runtime.getFunctionService();
            for (String name : java.util.List.of("jrubyEval", "jrubyEvalFile", "jrubySession")) {
                var function = functions.getGlobalFunction(name);
                if (function != null && moduleName.equalsIgnoreCase(function.module)) functions.unregisterGlobalFunction(Key.of(name));
            }
            var service = runtime.getComponentService();
            var descriptor = service.getComponent("jruby");
            if (descriptor != null && moduleName.equalsIgnoreCase(descriptor.module)) service.unregisterComponent(Key.of("jruby"));
        }
    }

    /**
     * Obtains the current manager and rejects calls outside an active activation.
     *
     * @return the active manager
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if core is inactive
     */
    private static RubyManager active() {
        RubyManager current = manager;
        if (current == null) throw new BoxRuntimeException("bx-jruby module is not active");
        return current;
    }

    /**
     * Creates a reusable core session using the supplied base or context. No request context is retained;
     * extensions should use the physical-path overload.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param options the explicit runtime configuration options
     * @return the session, which the caller must close
     */
    public static RubySession session(IBoxContext context, Map<?, ?> options) {
        Path base = FileSystemUtil.expandPath(context, ".").absolutePath();
        return active().create(options, base, true);
    }

    /**
     * Creates a reusable core session using the supplied base or context. No request context is retained;
     * extensions should use the physical-path overload.
     *
     * @param base the physical base directory; no request context is retained
     * @param options the explicit runtime configuration options
     * @return the session, which the caller must close
     */
    public static RubySession session(Path base, Map<?, ?> options) {
        return active().create(options, base, true);
    }

    /**
     * Registers a dependent extension's drain callback before core closes remaining sessions.
     *
     * @param hook the extension drain callback, which must wait for its owned work
     * @return a handle whose close method unregisters the callback
     */
    public static AutoCloseable beforeClose(Runnable hook) {
        return active().beforeClose(hook);
    }

    /**
     * Reports the public extension contract supported by this bridge.
     *
     * @return extension API version 1
     */
    public static int extensionVersion() { return 1; }

    /**
     * Evaluates source or a file in a one-shot session and closes before returning. Successful captures
     * include exit-handler output; opaque handles are rejected because their runtime is closed.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param source the source text or file path selected by the file flag
     * @param bindings explicit Ruby local bindings, leaving other session locals intact
     * @param options the explicit runtime configuration options
     * @param file true to evaluate a resolved file; false to evaluate source text
     * @return a struct containing the converted value, stdout and stderr
     * @throws RubyExecutionException if evaluation or termination fails
     */
    public static IStruct evaluate(IBoxContext context, String source, Map<?, ?> bindings, Map<?, ?> options, boolean file) {
        Path base = FileSystemUtil.expandPath(context, ".").absolutePath();
        String target = file ? FileSystemUtil.expandPath(context, source).absolutePath().toString() : source;
        RubySession session = active().create(options, base, false);
        IStruct result;
        try (session) {
            result = file ? session.evalFile(target, bindings) : session.eval(target, bindings);
        } catch (RubyExecutionException error) {
            session.snapshotOutput(error);
            throw error;
        }
        session.snapshotOutput(result); // Includes output from Ruby at_exit handlers.
        return result;
    }
}
