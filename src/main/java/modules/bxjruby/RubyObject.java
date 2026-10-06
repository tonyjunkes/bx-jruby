package modules.bxjruby;

import org.jruby.runtime.builtin.IRubyObject;

/** Opaque Ruby object. Only its owning session can unwrap it. */
public final class RubyObject {
    final RubySession owner;
    IRubyObject value;

    RubyObject(RubySession owner, IRubyObject value) {
        this.owner = owner;
        this.value = value;
    }

    @Override
    public String toString() {
        return "JRuby object handle";
    }
}
