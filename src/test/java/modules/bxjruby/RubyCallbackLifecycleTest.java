package modules.bxjruby;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Exercises callback lock ordering and activation-owned shutdown through public session methods. */
class RubyCallbackLifecycleTest {
    @TempDir Path directory;

    /**
     * Every executable session entry rejects a callback-spawned worker before acquiring its monitor.
     *
     * @param operation the public overload exercised by the worker
     * @throws Exception if the Ruby file fixture cannot be written
     */
    @ParameterizedTest
    @ValueSource(strings = {"eval", "evalBindings", "evalHandle", "evalFile", "evalFileBindings", "put", "get",
        "toRuby", "toBoxLang", "call", "callArgs", "callKeywords", "callWithBlock", "callWithoutBlock"})
    void rejectsCrossThreadOperations(String operation) throws Exception {
        Files.writeString(directory.resolve("callback.rb"), "42");
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true);
             var worker = Executors.newSingleThreadExecutor()) {
            var handle = ruby.toRuby("owned", "string");
            ruby.eval("def identity(value = 1, **kwargs); block_given? ? yield(value) : value; end; "
                + "def invoke; puts 'before'; result = yield; puts 'after'; result; end; value = 1; nil");
            var result = ruby.callWithBlock(null, "invoke", List.of(), Map.of(), args -> {
                var nested = worker.submit(() -> {
                    switch (operation) {
                        case "eval" -> ruby.eval("42");
                        case "evalBindings" -> ruby.eval("value", Map.of("value", 42));
                        case "evalHandle" -> ruby.evalHandle("42", Map.of());
                        case "evalFile" -> ruby.evalFile("callback.rb");
                        case "evalFileBindings" -> ruby.evalFile("callback.rb", Map.of());
                        case "put" -> ruby.put("value", 42);
                        case "get" -> ruby.get("value");
                        case "toRuby" -> ruby.toRuby("value", "string");
                        case "toBoxLang" -> ruby.toBoxLang(handle, "string");
                        case "call" -> ruby.call(null, "identity");
                        case "callArgs" -> ruby.call(null, "identity", List.of(42));
                        case "callKeywords" -> ruby.call(null, "identity", List.of(), Map.of("answer", 42));
                        case "callWithBlock" -> ruby.callWithBlock(null, "identity", List.of(), Map.of(), values -> 42);
                        case "callWithoutBlock" -> ruby.callWithBlock(null, "identity", List.of(), Map.of(), null);
                        default -> throw new AssertionError(operation);
                    }
                });
                var failure = assertThrows(ExecutionException.class, () -> nested.get(2, TimeUnit.SECONDS));
                assertInstanceOf(BoxRuntimeException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("block callback"));
                assertFalse(ruby.isClosed());
                return 42;
            });
            assertEquals(42L, result.get("value"));
            assertEquals("before\nafter\n", result.get("stdout"));
            assertEquals(1L, ruby.get("value"));
        }
    }

    /**
     * Activation shutdown waits for callbacks, rejects new work and invalidates every owned handle.
     *
     * @param failCallback whether the callback fails after shutdown begins
     * @throws Exception if the asynchronous lifecycle fixture fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managerDrainsActiveCallbacks(boolean failCallback) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exits = new AtomicInteger();
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true);
             var lazy = manager.create(Map.of(), directory, true);
             var workers = Executors.newFixedThreadPool(2)) {
            var handle = ruby.toRuby("owned", "string");
            ruby.eval("at_exit { exits.incrementAndGet }; def invoke; puts 'before'; result = yield; puts 'after'; result; end; nil",
                Map.of("exits", exits));
            var running = workers.submit(() -> ruby.callWithBlock(null, "invoke", List.of(), Map.of(), args -> {
                entered.countDown();
                assertTrue(assertDoesNotThrow(() -> release.await(10, TimeUnit.SECONDS)));
                if (failCallback) {
                    throw new IllegalArgumentException("original callback failure");
                }
                return handle;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var shutdown = workers.submit(manager::close);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while ((!ruby.isClosed() || !lazy.isClosed()) && !shutdown.isDone() && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertTrue(ruby.isClosed(), "shutdown must reject new work while draining");
                    assertTrue(lazy.isClosed(), "all sessions must reject work before shutdown waits");
                    assertFalse(shutdown.isDone(), "shutdown must wait for the active callback");
                    assertThrows(BoxRuntimeException.class, () -> ruby.eval("99"));
                } finally {
                    release.countDown();
                }
                if (failCallback) {
                    var failure = assertThrows(ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
                    assertTrue(failure.getCause().getMessage().contains("original callback failure"));
                } else {
                    var result = running.get(10, TimeUnit.SECONDS);
                    assertEquals("owned", result.get("value"));
                    assertEquals("before\nafter\n", result.get("stdout"));
                }
                shutdown.get(10, TimeUnit.SECONDS);
                assertNull(handle.value);
                assertEquals(0, manager.getSessionCount());
                assertEquals(1, exits.get());
                manager.close();
                assertEquals(1, exits.get());
                assertThrows(BoxRuntimeException.class, () -> manager.create(Map.of(), directory, true));
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A reentrant manager shutdown defers this session's termination until its Ruby operation unwinds.
     *
     * @param failCallback whether the callback fails after requesting shutdown
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managerShutdownFromCallbackDefersTermination(boolean failCallback) {
        var exits = new AtomicInteger();
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true);
             var lazy = manager.create(Map.of(), directory, true)) {
            var handle = ruby.toRuby("owned", "string");
            ruby.eval("at_exit { exits.incrementAndGet }; def invoke; puts 'before'; result = yield; puts 'after'; result; end; nil",
                Map.of("exits", exits));
            java.util.function.Supplier<ortus.boxlang.runtime.types.IStruct> call = () -> ruby.callWithBlock(
                null, "invoke", List.of(), Map.of(), args -> {
                    manager.close();
                    assertTrue(ruby.isClosed());
                    assertNotNull(handle.value, "the active callback still owns its runtime");
                    assertEquals(0, exits.get());
                    if (failCallback) {
                        throw new IllegalArgumentException("original callback failure");
                    }
                    return handle;
                });
            if (failCallback) {
                var failure = assertThrows(RubyExecutionException.class, call::get);
                assertTrue(failure.getMessage().contains("original callback failure"));
            } else {
                var result = call.get();
                assertEquals("owned", result.get("value"));
                assertEquals("before\nafter\n", result.get("stdout"));
            }
            assertTrue(lazy.isClosed());
            assertNull(handle.value);
            assertEquals(0, manager.getSessionCount());
            assertEquals(1, exits.get());
        }
    }
}
