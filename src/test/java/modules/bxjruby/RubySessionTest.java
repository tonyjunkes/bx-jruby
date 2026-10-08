package modules.bxjruby;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ortus.boxlang.runtime.types.IStruct;

class RubySessionTest {
    @TempDir
    Path directory;

    RubyManager manager = new RubyManager(getClass().getClassLoader(), Map.of());

    RubySession session() {
        return manager.create(Map.of(), directory, true);
    }

    Object value(RubySession session, String source) {
        return session.eval(source).get("value");
    }

    @AfterEach
    void cleanup() {
        manager.close();
    }

    @Test
    void capturesOutputAndReturnsIndependentNativeValues() {
        try (var ruby = session()) {
            var result = ruby.eval("puts 'héllo 🦊'; warn 'warning'; { 'Name' => [nil, true, 2 ** 100], 'name' => :symbol }");
            assertEquals("héllo 🦊\n", result.get("stdout"));
            assertEquals("warning\n", result.get("stderr"));
            var hash = (IStruct) result.get("value");
            assertEquals(2, hash.size());
            var nested = (List<?>) hash.get("Name");
            assertNull(nested.get(0));
            assertEquals(true, nested.get(1));
            assertEquals(BigInteger.ONE.shiftLeft(100), nested.get(2));
            assertEquals("symbol", hash.get("name"));
            ruby.close();
            assertEquals(BigInteger.ONE.shiftLeft(100), nested.get(2));
        }
    }

    @Test
    void copiesBindingsAndPersistsLocals() {
        try (var ruby = session()) {
            var input = new ArrayList<>(List.of(1, 2));
            assertEquals(6L, ruby.eval("items << 3; count = items.sum", Map.of("items", input)).get("value"));
            assertEquals(List.of(1, 2), input);
            assertEquals(7L, value(ruby, "count += 1"));
            assertEquals(7L, ruby.get("count"));
            ruby.put("count", 12);
            assertEquals(12L, value(ruby, "count"));
        }
    }

    @Test
    void decimalBindingsAndResultsRetainPrecision() {
        try (var ruby = session()) {
            var amount = new java.math.BigDecimal("12345678901234567890.123456789");
            assertEquals(amount.add(java.math.BigDecimal.ONE), ruby.eval("amount + 1", Map.of("amount", amount)).get("value"));
        }
    }

    @Test
    void nonFiniteDecimalsRemainNumericInsteadOfBecomingZero() {
        try (var ruby = session()) {
            value(ruby, "require 'bigdecimal'; nil");
            assertTrue(Double.isNaN((Double) value(ruby, "BigDecimal('NaN')")));
            assertEquals(Double.POSITIVE_INFINITY, value(ruby, "BigDecimal('Infinity')"));
            assertEquals(Double.NEGATIVE_INFINITY, value(ruby, "BigDecimal('-Infinity')"));
        }
    }

    @Test
    void sessionsIsolateGlobalsConstantsAndRequiredLibraries() throws Exception {
        Files.writeString(directory.resolve("library.rb"), "LIBRARY_VALUE = 9\n");
        try (var first = session(); var second = session()) {
            value(first, "VALUE = 1; $global_value = 2; local_value = 3; require './library'");
            assertEquals(java.util.Arrays.asList(null, null, null, null), java.util.Arrays.asList(
                value(second, "defined?(VALUE)"), value(second, "defined?($global_value)"),
                value(second, "defined?(local_value)"), value(second, "defined?(LIBRARY_VALUE)")));
        }
    }

    @Test
    void standardLibraryAndConfiguredLoadPathsWork() throws Exception {
        Files.writeString(directory.resolve("helper.rb"), "def helper; 23; end");
        try (var ruby = manager.create(Map.of("loadPaths", List.of(".")), directory, true)) {
            assertEquals("{\"answer\":23}", value(ruby, "require 'json'; require 'helper'; JSON.generate({answer: helper})"));
        }
    }

    @Test
    void evalFilePreservesRelativeRequireAndFilename() throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/helper.rb"), "VALUE_FROM_HELPER = 41");
        Files.writeString(directory.resolve("nested/main.rb"), "require_relative 'helper'; VALUE_FROM_HELPER + 1");
        try (var ruby = session()) {
            assertEquals(42L, ruby.evalFile("nested/main.rb").get("value"));
            Files.writeString(directory.resolve("nested/failure.rb"), "puts 'before'; raise 'broken'");
            var error = assertThrows(RubyExecutionException.class, () -> ruby.evalFile("nested/failure.rb"));
            assertTrue(error.getMessage().contains("failure.rb"));
            assertTrue(error.getRubyBacktrace().stream().anyMatch(line -> line.contains("failure.rb:1")));
            assertEquals("before\n", error.getStdout());
        }
    }

    @Test
    void capturesFailureOutputAndOriginalCause() {
        try (var ruby = session()) {
            var error = assertThrows(RubyExecutionException.class, () -> ruby.eval("puts 'before'; warn 'problem'; raise 'oops'"));
            assertEquals("before\n", error.getStdout());
            assertEquals("problem\n", error.getStderr());
            assertNotNull(error.getCause());
            assertFalse(error.getRubyBacktrace().isEmpty());
            assertThrows(RubyExecutionException.class, () -> ruby.eval("def invalid("));
            assertThrows(RubyExecutionException.class, () -> ruby.evalFile("absent.rb"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("require 'bx_jruby_nonexistent_gem'"));
            assertEquals(2L, value(ruby, "1 + 1"));
        }
    }

    @Test
    void handlesSupportCallsKeywordsAndInputBindings() {
        try (var ruby = session(); var other = session()) {
            Object handle = value(ruby, "class Greeter; def greet(name, prefix: 'Hello'); \"#{prefix}, #{name}\"; end; end; Greeter.new");
            assertInstanceOf(RubyObject.class, handle);
            assertEquals("Hi, BoxLang", ruby.call(handle, "greet", List.of("BoxLang"), Map.of("prefix", "Hi")).get("value"));
            assertEquals("Hello, input", ruby.eval("greeter.greet('input')", Map.of("greeter", handle)).get("value"));
            value(ruby, "def add(a, b); a + b; end");
            assertEquals(5L, ruby.call(null, "add", List.of(2, 3)).get("value"));
            assertThrows(RubyExecutionException.class, () -> other.call(handle, "greet", List.of("wrong")));
            ruby.close();
            assertThrows(RuntimeException.class, () -> ruby.call(handle, "greet"));
            assertThrows(RubyExecutionException.class, () -> other.eval("input", Map.of("input", handle)));
        }
    }

    @Test
    void oneShotRejectsNestedObjectHandlesAndAlwaysTerminates() {
        var ruby = manager.create(Map.of(), directory, false);
        try (ruby) {
            var error = assertThrows(RubyExecutionException.class, () -> ruby.eval("[Object.new]"));
            assertTrue(error.getMessage().contains("jrubySession"));
        }
        assertEquals(0, manager.getSessionCount());
    }

    @Test
    void rejectsLossyAndCyclicConversions() {
        try (var ruby = session()) {
            assertThrows(RubyExecutionException.class, () -> ruby.eval("{1 => 'number'}"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("{:name => 1, 'name' => 2}"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("a = []; a << a; a"));
            var input = new ArrayList<>(); input.add(input);
            assertThrows(RubyExecutionException.class, () -> ruby.eval("input", Map.of("input", input)));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("1", Map.of("invalid-name", 1)));
        }
    }

    @Test
    void rejectsCollidingInputHashBindingsAndKeywordsWithoutUpdatingLocals() {
        try (var ruby = session()) {
            var collisions = new java.util.LinkedHashMap<Object, Object>();
            collisions.put("name", 1);
            collisions.put(new StringBuilder("name"), 2);
            ruby.put("name", "original");
            var hashError = assertThrows(RubyExecutionException.class,
                () -> ruby.eval("payload", Map.of("payload", collisions)));
            assertTrue(hashError.getMessage().contains("collision"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("name", collisions));
            assertEquals("original", ruby.get("name"));
            value(ruby, "def keywords(**values); values; end");
            assertThrows(RubyExecutionException.class, () -> ruby.call(null, "keywords", List.of(), collisions));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("1", Map.of(1, "invalid")));
        }
    }

    @Test
    void getDetachesCollectionsAndClearsLiveHandlesOnClose() {
        var ruby = session();
        assertNull(ruby.get("missing"));
        value(ruby, "state = {'Name' => [1], 'name' => 2}; object = Object.new; nil");
        var state = (IStruct) ruby.get("state");
        var handle = (RubyObject) ruby.get("object");
        value(ruby, "state['Name'] << 3; nil");
        assertEquals(List.of(1L), state.get("Name"));
        assertEquals(2L, state.get("name"));
        assertNotNull(handle.value);
        ruby.close();
        assertNull(handle.value);
        assertEquals(List.of(1L), state.get("Name"));
        assertThrows(RuntimeException.class, () -> ruby.get("state"));
    }

    @Test
    void concurrentCallsSerializeAndDoNotMixOutput() throws Exception {
        try (var ruby = session(); var workers = Executors.newFixedThreadPool(4)) {
            value(ruby, "counter = 0");
            var tasks = new ArrayList<java.util.concurrent.Future<IStruct>>();
            for (int index = 0; index < 24; index++) tasks.add(workers.submit(() -> ruby.eval("counter += 1; puts counter; counter")));
            var values = new java.util.HashSet<Object>();
            for (var task : tasks) {
                var result = task.get(30, TimeUnit.SECONDS);
                values.add(result.get("value"));
                assertEquals(result.get("value") + "\n", result.get("stdout"));
            }
            assertEquals(24, values.size());
            assertEquals(24L, ruby.get("counter"));
        }
    }

    @Test
    void closeWaitsForWorkAndRejectsNewWork() throws Exception {
        var ruby = session();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var running = workers.submit(() -> ruby.eval("started.countDown; finish.await; 42", Map.of("started", started, "finish", finish)));
            assertTrue(started.await(30, TimeUnit.SECONDS));
            var closing = workers.submit(ruby::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ruby.isClosed() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(ruby.isClosed());
            assertFalse(closing.isDone());
            finish.countDown();
            assertEquals(42L, running.get(30, TimeUnit.SECONDS).get("value"));
            closing.get(30, TimeUnit.SECONDS);
            ruby.close();
            assertThrows(RuntimeException.class, () -> ruby.eval("1"));
        } finally { finish.countDown(); }
    }

    @Test
    void managerClosesInitializedAndLazySessions() {
        var first = session(); var lazy = session();
        value(first, "1");
        manager.close();
        assertTrue(first.isClosed()); assertTrue(lazy.isClosed());
        assertEquals(0, manager.getSessionCount());
        assertThrows(RuntimeException.class, () -> session());
    }

    @Test
    void managerContinuesCleanupAfterExitHandlerFailure() {
        var failing = session();
        var other = session();
        value(failing, "at_exit { raise 'close failure' }; nil");
        value(other, "1");
        manager.close();
        assertTrue(failing.isClosed());
        assertTrue(other.isClosed());
        assertEquals(0, manager.getSessionCount());
        var captured = new ortus.boxlang.runtime.types.Struct();
        failing.snapshotOutput(captured);
        assertTrue(captured.get("stderr").toString().contains("close failure"));
    }

    @Test
    void preservesThreadContextClassLoaderOnSuccessAndFailure() {
        var original = Thread.currentThread().getContextClassLoader();
        try (var ruby = session()) {
            value(ruby, "1");
            assertSame(original, Thread.currentThread().getContextClassLoader());
            assertThrows(RubyExecutionException.class, () -> ruby.eval("raise 'error'"));
            assertSame(original, Thread.currentThread().getContextClassLoader());
        }
    }

    @Test
    void configuredPreinstalledGemWorks() throws Exception {
        Path gems = directory.resolve("gems");
        Files.createDirectories(gems.resolve("specifications"));
        Files.createDirectories(gems.resolve("gems/bx_fixture-1.0.0/lib"));
        Files.writeString(gems.resolve("specifications/bx_fixture-1.0.0.gemspec"), "Gem::Specification.new do |s|\n s.name = 'bx_fixture'\n s.version = '1.0.0'\n s.summary = 'Local test fixture'\n s.authors = ['Test']\n s.files = ['lib/bx_fixture.rb']\n s.require_paths = ['lib']\nend\n");
        Files.writeString(gems.resolve("gems/bx_fixture-1.0.0/lib/bx_fixture.rb"), "module BXFixture; VALUE = 73; end");
        try (var ruby = manager.create(Map.of("gemHome", "gems", "gemPaths", List.of("gems")), directory, true)) {
            assertEquals(73L, value(ruby, "require 'bx_fixture'; BXFixture::VALUE"));
            assertEquals("{}", value(ruby, "require 'json'; JSON.generate({})"));
        }
    }

    @Test
    void optionsOverrideDefaultsWithoutMutatingThem() throws Exception {
        var defaults = Map.of("loadPaths", List.of("missing"));
        var configured = new RubyManager(getClass().getClassLoader(), defaults);
        try (configured; var ruby = configured.create(Map.of("loadPaths", List.of()), directory, true)) {
            // JRuby expands Windows short-path aliases (e.g. RUNNER~1); compare filesystem identity.
            assertTrue(Files.isSameFile(directory, Path.of(value(ruby, "Dir.pwd").toString())));
            assertEquals(List.of("missing"), defaults.get("loadPaths"));
        }
        assertThrows(RuntimeException.class, () -> manager.create(Map.of("typo", true), directory, true));
        assertThrows(RuntimeException.class,
            () -> manager.create(Map.of("loadPaths", List.of(), "LOADPATHS", List.of("missing")), directory, true));
        try (var ruby = manager.create(Map.of("LOADPATHS", List.of()), directory, true)) {
            assertEquals("{}", value(ruby, "require 'json'; JSON.generate({})"));
        }
    }
}
