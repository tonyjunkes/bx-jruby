package modules.bxjruby;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ortus.boxlang.runtime.types.DateTime;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Exercises additive interoperability through the public session interface. */
class RubyInteropTest {
    @TempDir Path directory;

    /** Input and arguments are copied, remain session-local, and reach EOF predictably. */
    @Test void inputAndArgumentsAreOwned() {
        byte[] input = {(byte) 0xff, 0, 65};
        var args = new java.util.ArrayList<>(List.of("first"));
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of("stdin", input, "argv", args), directory, true)) {
            input[0] = 1;
            args.add("later");
            assertEquals("255,0,65|first", ruby.eval("STDIN.read.bytes.join(',') + '|' + ARGV.join(',')").get("value"));
            assertEquals("", ruby.eval("STDIN.read").get("value"));
            assertThrows(RuntimeException.class, () -> manager.create(Map.of("argv", List.of(3)), directory, true));
        }
    }

    /** Binary, scalar handles and temporal values retain explicit type and ownership. */
    @Test void explicitConversionsPreserveBytesTypesAndOwnership() {
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true);
             var other = manager.create(Map.of(), directory, true)) {
            byte[] input = {(byte) 0xff, 0, 65};
            var binary = ruby.toRuby(input, "binary");
            input[0] = 0;
            assertArrayEquals(new byte[] {(byte) 0xff, 0, 65}, (byte[]) ruby.toBoxLang(binary, "binary"));
            assertEquals("ASCII-8BIT", ruby.eval("data.encoding.name", Map.of("data", binary)).get("value"));
            var raw = (RubyObject) ruby.evalHandle("'\u00ff'.encode('UTF-8')", Map.of()).get("value");
            assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xbf}, (byte[]) ruby.toBoxLang(raw, "binary"));
            var symbol = ruby.toRuby("answer", "symbol");
            assertEquals("Symbol", ruby.eval("value.class.name", Map.of("value", symbol)).get("value"));
            assertEquals("answer", ruby.toBoxLang(symbol, "symbol"));
            var time = ruby.toRuby("2026-10-16T12:34:56.123456789+05:30", "time");
            assertEquals("2026-10-16T07:04:56.123456789Z", ((DateTime) ruby.toBoxLang(time, "time")).toInstant().toString());
            var date = ruby.toRuby("2026-10-16", "date");
            assertEquals("2026-10-16", ((DateTime) ruby.toBoxLang(date, "date")).toLocalDate().toString());
            assertThrows(RuntimeException.class, () -> other.toBoxLang(binary, "binary"));
            assertThrows(RuntimeException.class, () -> ruby.toBoxLang(symbol, "binary"));
            var invalid = (RubyObject) ruby.evalHandle("[255].pack('C').force_encoding('UTF-8')", Map.of()).get("value");
            assertArrayEquals(new byte[] {(byte) 0xff}, (byte[]) ruby.toBoxLang(invalid, "binary"));
            assertThrows(RuntimeException.class, () -> ruby.toBoxLang(invalid, "string"));
        }
    }

    /** A callback has bounded lifetime, honors keywords, and cannot reenter or terminate its session. */
    @Test void blocksAreSynchronousAndExpire() {
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true)) {
            ruby.eval("def invoke(value, scale:, &block); $saved = block; block.call(value * scale); end; nil");
            assertEquals(7L, ruby.callWithBlock(null, "invoke", List.of(3), Map.of("scale", 2),
                args -> ((Number) args[0]).longValue() + 1).get("value"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("$saved.call(1)"));
            assertThrows(RubyExecutionException.class, () -> ruby.callWithBlock(null, "invoke", List.of(1), Map.of("scale", 1),
                args -> ruby.eval("1")));
            assertThrows(RubyExecutionException.class, () -> ruby.callWithBlock(null, "invoke", List.of(1), Map.of("scale", 1),
                args -> { ruby.close(); return null; }));
            ruby.eval("def threaded(&block); Thread.new { block.call(1) }.value; end; nil");
            assertThrows(RubyExecutionException.class, () -> ruby.callWithBlock(null, "threaded", List.of(), Map.of(), args -> 1));
            var failure = assertThrows(RubyExecutionException.class, () -> ruby.callWithBlock(null, "invoke", List.of(1), Map.of("scale", 1),
                args -> { throw new IllegalArgumentException("callback failure"); }));
            assertTrue(failure.getMessage().contains("callback failure"));
            assertEquals(2L, ruby.eval("1+1").get("value"));
        }
    }

    /**
     * Callback-spawned close attempts fail before waiting for the callback's session monitor.
     *
     * @param capture whether the worker uses closeAndCapture rather than close
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbacksRejectCrossThreadClose(boolean capture) {
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true);
             var worker = Executors.newSingleThreadExecutor()) {
            ruby.eval("def invoke_close_check; puts 'before'; result = yield; puts 'after'; result; end; nil");
            var result = ruby.callWithBlock(null, "invoke_close_check", List.of(), Map.of(), args -> {
                var closing = worker.submit(() -> {
                    if (capture) {
                        ruby.closeAndCapture();
                    } else {
                        ruby.close();
                    }
                });
                var failure = assertThrows(ExecutionException.class, () -> closing.get(2, TimeUnit.SECONDS));
                assertInstanceOf(BoxRuntimeException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("block callback"));
                assertFalse(ruby.isClosed());
                return 42;
            });
            assertEquals(42L, result.get("value"));
            assertEquals("before\nafter\n", result.get("stdout"));
            assertEquals(2L, ruby.eval("1+1").get("value"));
        }
    }

    /** Bundler setup is explicit, validates frozen configuration and performs no installation. */
    @Test void bundlerIsOptInAndValidatesLockfiles() throws Exception {
        Files.writeString(directory.resolve("Gemfile"), "source 'https://rubygems.org'\n");
        Files.createDirectories(directory.resolve(".bundle"));
        Files.writeString(directory.resolve(".bundle/config"), "---\nBUNDLE_AUTO_INSTALL: true\nBUNDLE_VERSION: 4.0.0\n");
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of())) {
            assertThrows(RuntimeException.class, () -> manager.create(Map.of("bundler", Map.of("frozen", true)), directory, true));
            assertThrows(RuntimeException.class, () -> manager.create(Map.of("bundler", true, "env", Map.of("BUNDLE_GEMFILE", "other")), directory, true));
            try (var ruby = manager.create(Map.of("bundler", true), directory, true)) {
                assertEquals("constant", ruby.eval("defined?(Bundler)").get("value"));
                assertEquals(true, ruby.eval("Bundler.settings[:auto_install] == false && Bundler.settings[:version] == 'system'").get("value"));
            }
            Files.writeString(directory.resolve("Gemfile"), "gem 'bx_nonexistent_fixture_gem', '0.0.0'\n");
            try (var ruby = manager.create(Map.of("bundler", true), directory, true)) {
                assertThrows(RubyExecutionException.class, () -> ruby.eval("42"));
                assertThrows(RubyExecutionException.class, () -> ruby.eval("42"));
            }
            Files.writeString(directory.resolve("Gemfile"), "exit 7\n");
            try (var ruby = manager.create(Map.of("bundler", true), directory, true)) {
                assertThrows(RubyExecutionException.class, () -> ruby.eval("42"));
            }
        }
    }

    /** Embedded execution includes ordinary Ruby libraries/Java interop and contains process exits. */
    @Test void standardLibrariesJavaInteropAndExits() {
        try (var manager = new RubyManager(getClass().getClassLoader(), Map.of());
             var ruby = manager.create(Map.of(), directory, true)) {
            assertEquals(true, ruby.eval("""
                require 'csv'; require 'set'; require 'uri'; require 'digest'; require 'json'
                CSV.parse('a,b').first == ['a','b'] && Set[1,1,2].size == 2 &&
                  URI.parse('https://example.com/path').host == 'example.com' &&
                  Digest::SHA256.hexdigest('hello').size == 64 && JSON.parse('{"a":1}')['a'] == 1
                """).get("value"));
            assertEquals("hello", ruby.eval("values = java.util.ArrayList.new; values.add('hello'); values.get(0)").get("value"));
            assertEquals(7L, ruby.eval("exit 7").get("value"));
            assertThrows(RubyExecutionException.class, () -> ruby.eval("exit! 9"));
            assertEquals(42L, ruby.eval("6*7").get("value"));
        }
    }
}
