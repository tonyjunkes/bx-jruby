package modules.bxjruby.components;

import java.util.Set;
import modules.bxjruby.JRubyRuntime;
import ortus.boxlang.runtime.components.Attribute;
import ortus.boxlang.runtime.components.BoxComponent;
import ortus.boxlang.runtime.components.Component;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.dynamic.ExpressionInterpreter;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.validation.Validator;

/** Captures template body text as Ruby source and assigns its result. */
@BoxComponent(allowsBody = true, requiresBody = true)
public final class JRuby extends Component {
    public JRuby() {
        declaredAttributes = new Attribute[] {
            new Attribute(Key.variable, "string", Set.of(Validator.REQUIRED, Validator.NON_EMPTY)),
            new Attribute(Key.of("bindings"), "struct", new Struct()),
            new Attribute(Key.of("options"), "struct", new Struct())
        };
    }

    @Override
    public BodyResult _invoke(IBoxContext context, IStruct attributes, ComponentBody body, IStruct executionState) {
        StringBuffer source = new StringBuffer();
        BodyResult bodyResult = processBody(context, body, source);
        if (bodyResult.isEarlyExit()) return bodyResult;
        IStruct result = JRubyRuntime.evaluate(context, source.toString(), attributes.getAsStruct(Key.of("bindings")),
            attributes.getAsStruct(Key.of("options")), false);
        ExpressionInterpreter.setVariable(context, attributes.getAsString(Key.variable), result);
        return DEFAULT_RETURN;
    }
}
