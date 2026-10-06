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
    public JRubySession() {
        declaredArguments = new Argument[] { new Argument(false, "struct", Key.of("options"), new Struct()) };
    }

    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope arguments) {
        return JRubyRuntime.session(context, arguments.getAsStruct(Key.of("options")));
    }
}
