package modules.bxjruby;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Owns sessions for one module activation. Never holds a request context. */
public final class RubyManager implements AutoCloseable {
    private final ClassLoader loader;
    private final Map<?, ?> defaults;
    private final Set<RubySession> sessions = new HashSet<>();
    private boolean closed;

    public RubyManager(ClassLoader loader, Map<?, ?> defaults) {
        this.loader = loader;
        this.defaults = RubyOptions.normalize(defaults);
    }

    public synchronized RubySession create(Map<?, ?> options, Path base, boolean handles) {
        if (closed) throw new BoxRuntimeException("JRuby module is closed");
        var session = new RubySession(this, loader, RubyOptions.resolve(defaults, options, base), handles);
        sessions.add(session);
        return session;
    }

    synchronized void release(RubySession session) {
        sessions.remove(session);
    }

    public synchronized int getSessionCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        Set<RubySession> outstanding;
        synchronized (this) {
            if (closed) return;
            closed = true;
            outstanding = Set.copyOf(sessions);
        }
        RuntimeException failure = null;
        for (var session : outstanding) {
            try { session.close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
