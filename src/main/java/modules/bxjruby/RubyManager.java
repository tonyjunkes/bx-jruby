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
    private final Set<Runnable> closeHooks = new java.util.LinkedHashSet<>();
    private boolean closed;

    /**
     * Creates the owner for one activation and snapshots its default option keys.
     *
     * @param loader the module class loader used for JRuby libraries and execution
     * @param defaults the module-level default options
     */
    public RubyManager(ClassLoader loader, Map<?, ?> defaults) {
        this.loader = loader;
        this.defaults = RubyOptions.normalize(defaults);
    }

    /**
     * Registers a lazy session using defaults and explicit overrides. Closed managers reject new sessions.
     *
     * @param options the explicit runtime configuration options
     * @param base the physical base directory for relative paths
     * @param handles whether unsupported Ruby results may become owned handles
     * @return the owned session, which the caller must close
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if the manager is closed or options
     * are invalid
     */
    public synchronized RubySession create(Map<?, ?> options, Path base, boolean handles) {
        if (closed) throw new BoxRuntimeException("JRuby module is closed");
        var session = new RubySession(this, loader, RubyOptions.resolve(defaults, options, base), handles);
        sessions.add(session);
        return session;
    }

    /**
     * Removes a terminated session from manager ownership.
     *
     * @param session the worker-owned core session
     */
    synchronized void release(RubySession session) {
        sessions.remove(session);
    }

    /**
     * Counts sessions still owned by this activation.
     *
     * @return the number of outstanding sessions
     */
    public synchronized int getSessionCount() {
        return sessions.size();
    }

    /**
     * Registers extension cleanup before ordinary session shutdown. Hooks run without the manager lock and
     * must wait for all owned work.
     *
     * @param hook the extension drain callback, which must wait for its owned work
     * @return a handle whose close method unregisters the hook
     * @throws ortus.boxlang.runtime.types.exceptions.BoxRuntimeException if shutdown has begun
     */
    public synchronized AutoCloseable beforeClose(Runnable hook) {
        if (closed) throw new BoxRuntimeException("JRuby module is closed");
        closeHooks.add(java.util.Objects.requireNonNull(hook));
        return () -> { synchronized (RubyManager.this) { closeHooks.remove(hook); } };
    }

    /**
     * Idempotently stops session creation, runs extension hooks and closes remaining sessions. All cleanup is
     * attempted and later failures are suppressed on the first failure.
     */
    @Override
    public void close() {
        Set<RubySession> outstanding;
        Set<Runnable> hooks;
        synchronized (this) {
            if (closed) return;
            closed = true;
            hooks = new java.util.LinkedHashSet<>(closeHooks);
            closeHooks.clear();
        }
        RuntimeException failure = null;
        for (var hook : hooks) {
            try { hook.run(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        synchronized (this) { outstanding = Set.copyOf(sessions); }
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
