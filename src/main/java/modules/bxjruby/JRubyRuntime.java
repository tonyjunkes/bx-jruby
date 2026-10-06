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

    private JRubyRuntime() {}

    public static void start(ModuleRecord record) {
        manager = new RubyManager(record.getModuleClassLoader().toClassLoader(), record.settings);
    }

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

    private static RubyManager active() {
        RubyManager current = manager;
        if (current == null) throw new BoxRuntimeException("bx-jruby module is not active");
        return current;
    }

    public static RubySession session(IBoxContext context, Map<?, ?> options) {
        Path base = FileSystemUtil.expandPath(context, ".").absolutePath();
        return active().create(options, base, true);
    }

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
