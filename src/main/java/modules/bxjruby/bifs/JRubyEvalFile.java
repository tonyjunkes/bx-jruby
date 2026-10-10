package modules.bxjruby.bifs;

import modules.bxjruby.JRubyRuntime;
import ortus.boxlang.runtime.bifs.BIF;
import ortus.boxlang.runtime.bifs.BoxBIF;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.ArgumentsScope;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Argument;
import ortus.boxlang.runtime.types.Struct;

/** Execute a Ruby file resolved in the current BoxLang context. */
@BoxBIF
public final class JRubyEvalFile extends BIF {
    /**
     * Declares the file path, bindings and runtime settings for jrubyEvalFile.
     */
    public JRubyEvalFile() {
        declaredArguments = new Argument[] {
            new Argument(true, "string", Key.of("path")),
            new Argument(false, "struct", Key.of("bindings"), new Struct()),
            new Argument(false, "struct", Key.of("options"), new Struct())
        };
    }

    /**
     * Evaluates a context-resolved Ruby file in a one-shot session.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param arguments the validated BoxLang function arguments
     * @return a struct containing the value, stdout and stderr
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope arguments) {
        return JRubyRuntime.evaluate(context, arguments.getAsString(Key.of("path")),
            arguments.getAsStruct(Key.of("bindings")), arguments.getAsStruct(Key.of("options")), true);
    }
}
