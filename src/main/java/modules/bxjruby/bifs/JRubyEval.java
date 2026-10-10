package modules.bxjruby.bifs;

import modules.bxjruby.JRubyRuntime;
import ortus.boxlang.runtime.bifs.BIF;
import ortus.boxlang.runtime.bifs.BoxBIF;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.ArgumentsScope;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Argument;
import ortus.boxlang.runtime.types.Struct;

/** Evaluate a Ruby source string with explicit bindings and captured output. */
@BoxBIF
public final class JRubyEval extends BIF {
    /**
     * Declares the source, bindings and runtime settings for jrubyEval.
     */
    public JRubyEval() {
        declaredArguments = new Argument[] {
            new Argument(true, "string", Key.of("script")),
            new Argument(false, "struct", Key.of("bindings"), new Struct()),
            new Argument(false, "struct", Key.of("options"), new Struct())
        };
    }

    /**
     * Evaluates Ruby source with explicit bindings in a one-shot session.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param arguments the validated BoxLang function arguments
     * @return a struct containing the value, stdout and stderr
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope arguments) {
        return JRubyRuntime.evaluate(context, arguments.getAsString(Key.of("script")),
            arguments.getAsStruct(Key.of("bindings")), arguments.getAsStruct(Key.of("options")), false);
    }
}
