package modules.bxjruby;

import java.util.function.Function;
import org.jruby.runtime.builtin.IRubyObject;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** A retained Ruby Proc must never retain a BoxLang callback or request context after its call. */
final class RubyBlock implements AutoCloseable {
    private final RubySession owner;
    private final Thread thread = Thread.currentThread();
    private volatile Function<Object[], Object> callback;

    /**
     * Creates a lease bound to the current operation thread.
     * @param owner the session owning the block
     * @param callback the synchronous native callback
     */
    RubyBlock(RubySession owner, Function<Object[], Object> callback) {
        this.owner = owner;
        this.callback = callback;
    }

    /**
     * Rejects late and cross-thread yields before touching BoxLang or session state.
     * @param values Ruby yield arguments
     * @return the converted callback result
     */
    IRubyObject invoke(IRubyObject[] values) {
        var current = callback;
        if (current == null || Thread.currentThread() != thread) {
            throw new BoxRuntimeException("JRuby block callback is expired or invoked from another thread");
        }
        return owner.invokeBlock(current, values);
    }

    /** Releases the callback and any captured BoxLang context even if Ruby retains its Proc. */
    @Override
    public void close() {
        callback = null;
    }
}
