package modules.bxjruby.bifs;

import modules.bxjruby.JRubyRuntime;
import ortus.boxlang.runtime.bifs.BIF;
import ortus.boxlang.runtime.bifs.BoxBIF;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.ArgumentsScope;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Argument;
import ortus.boxlang.runtime.types.Struct;

/** Create an explicitly owned Ruby session. Close it in finally. */
@BoxBIF
public final class JRubySession extends BIF {
    /**
     * Declares optional runtime settings for jrubySession.
     */
    public JRubySession() {
        declaredArguments = new Argument[] { new Argument(false, "struct", Key.of("options"), new Struct()) };
    }

    /**
     * Creates a reusable session with settings resolved from the calling context.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param arguments the validated BoxLang function arguments
     * @return the session, which the caller must close
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope arguments) {
        return JRubyRuntime.session(context, arguments.getAsStruct(Key.of("options")));
    }
}
