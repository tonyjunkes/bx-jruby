package modules.bxjruby;

import org.jruby.runtime.builtin.IRubyObject;

/** Opaque Ruby object. Only its owning session can unwrap it. */
public final class RubyObject {
    final RubySession owner;
    IRubyObject value;

    /**
     * Creates an opaque value handle owned by one Ruby session.
     *
     * @param owner the session that owns opaque Ruby values
     * @param value the Ruby value bounded by the owning session's lifetime
     */
    RubyObject(RubySession owner, IRubyObject value) {
        this.owner = owner;
        this.value = value;
    }

    /**
     * Returns a safe label without invoking arbitrary Ruby inspection code.
     *
     * @return a fixed opaque-handle description
     */
    @Override
    public String toString() {
        return "JRuby object handle";
    }
}
