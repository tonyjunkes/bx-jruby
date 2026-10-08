# BoxLang JRuby Support

Run Ruby in BoxLang with explicit inputs, captured output, and reusable Ruby
sessions. Requires **BoxLang 1.18.0+ and Java 21+**. The module bundles
**JRuby 10.1.2.0**, targeting Ruby 4.0; no separate JRuby installation is needed.

## Installation

Install from ForgeBox using BoxLang's module installer or CommandBox:

```sh
# BoxLang
install-bx-module bx-jruby

# CommandBox
box install bx-jruby
```

The ForgeBox package includes the compiled module and JRuby runtime; no source
build is required.

## AI Ready

For AI-assisted development and integration, use the [bx-jruby skill](SKILL.md).

## Evaluate Ruby

```js
result = jrubyEval(
    script = "puts 'Hello from Ruby'; numbers.sum",
    bindings = { numbers: [10, 20, 30] }
);
println(result.value);  // 60
print(result.stdout);  // Hello from Ruby

fromFile = jrubyEvalFile("./ruby/calculate.rb", { amount: 12 });
```

`jrubyEval(script, bindings={}, options={})` and
`jrubyEvalFile(path, bindings={}, options={})` return a struct with `value`,
`stdout`, and `stderr`. `value` is the last Ruby expression, including null for
Ruby nil. Output is never automatically written into the BoxLang page buffer.
Each call creates an independent runtime and closes it, even on failure. Output
from successful one-shot `at_exit` handlers is included in the returned capture.

Bindings require Ruby local-variable names such as `amount` or `user_name`.
Caller scopes are not copied. Structs and arrays are copied into native Ruby
hashes/arrays; Ruby modifications do not change the original collections.
Other Java objects are passed as Java proxies, so their methods can mutate
the original Java object. Ruby variable reassignment does not update BoxLang
variables. Execute only trusted Ruby code: JRuby can access Java, files, and
the network with the host process's permissions.

## Reusable sessions and Ruby objects

```js
ruby = jrubySession();
try {
    ruby.evalFile("./ruby/greeter.rb");
    greeter = ruby.eval("Greeter.new").value;
    greeting = ruby.call(greeter, "greet", ["BoxLang"], { prefix: "Hi" });
    println(greeting.value);
    ruby.put("counter", 0);
    ruby.eval("counter += 1");
    println(ruby.get("counter"));
} finally {
    ruby.close();
}
```

The corresponding `ruby/greeter.rb`:

```ruby
class Greeter
  def greet(name, prefix: "Hello")
    "#{prefix}, #{name}"
  end
end
```

Session methods are Java methods; use positional arguments:

| Method | Returns |
| --- | --- |
| `eval(script, bindings={})` | `{ value, stdout, stderr }` |
| `evalFile(path, bindings={})` | `{ value, stdout, stderr }` |
| `put(name, value)` | void; sets a local variable |
| `get(name)` | Converted local value; null if absent or nil |
| `call(receiver, method, args=[], kwargs={})` | `{ value, stdout, stderr }` |
| `close()` | void; idempotently terminates the runtime |
| `isClosed()` | Whether closing has begun |

Use a null receiver for a top-level Ruby method:
`ruby.call(null, "calculate", [12])`. Hashes passed as positional arguments
remain Ruby hashes; keyword arguments use the distinct `kwargs` parameter.

Locals, globals, constants, classes, and required libraries persist within
a session. Calls on one session serialize, including output capture; separate
sessions run independently. Closing waits for current work and rejects new
work. It does not forcibly cancel Ruby code. Module unload closes all remaining
sessions; applications should still close their own sessions promptly.

Custom Ruby values become opaque handles owned by the session. Pass handles to
`call()` or through bindings in the same session. They become invalid after
close or module reload and cannot cross sessions. One-shot calls reject results
containing handles because their runtime is immediately closed.

Scalars, symbols (as strings), arrays, and hashes convert into independent
BoxLang values. Large integers and finite BigDecimal values retain their precision.
Non-finite BigDecimal values become Java doubles preserving NaN and signed
infinity. Ruby hashes become ordered, case-sensitive structs with string/symbol
keys. Mixed `:name` and `"name"` keys collide and produce an error; non-string/symbol keys and cyclic
collections also produce errors. Java proxies return their underlying Java
object. Ruby values such as classes, dates, and rational numbers are handles.

## Inline component

```xml
<bx:script>
input = { name: "BoxLang" };
</bx:script>
<bx:jruby variable="result" bindings="#input#">
puts "Hello, " + name
6 * 7
</bx:jruby>
<bx:script>
println(result.value);
print(result.stdout);
</bx:script>
```

The component requires a body and a `variable` assignment target. Optional
`bindings` and `options` match the functions. Its body passes through BoxLang
template processing first. Within `bx:output`, escape Ruby interpolation
`#{name}` as `##{name}`. Files or string evaluation are easiest for Ruby source
containing BoxLang template delimiters or tags.

## Configuration and gems

The runtime name is `bxjruby`; the ForgeBox installation slug is `bx-jruby`.
All factory/functions accept `options`; the same defaults can be configured
in `boxlang.json` under `modules["bxjruby"].settings`:

```json
{
  "modules": {
    "bxjruby": {
      "enabled": true,
      "settings": {
        "workingDirectory": "./ruby",
        "loadPaths": ["./lib"],
        "gemHome": "./vendor/gems",
        "gemPaths": ["./vendor/gems"]
      }
    }
  }
}
```

| Option | Default | Meaning |
| --- | --- | --- |
| `workingDirectory` | Caller directory | Fixed runtime working directory |
| `loadPaths` | `[]` | Additional paths for Ruby `require` |
| `gemHome` | Inherited JRuby/environment behavior | Sets runtime `GEM_HOME` |
| `gemPaths` | `[]` | Sets runtime `GEM_PATH` when nonempty |

An explicit option replaces that module default; path lists are not merged.
An empty `workingDirectory` selects the caller directory; an empty `gemHome`
or `gemPaths` uses inherited environment behavior. Relative working directories
resolve from the BoxLang caller directory; other paths resolve from the chosen
working directory. JRuby's embedded standard library remains available.

One-shot file paths support BoxLang mappings. Session `evalFile()` paths are
physical paths resolved against its fixed working directory, independent of
later request contexts. Executing a file runs it again each time; Ruby `require`
uses Ruby's own loaded-feature tracking. `require_relative` uses the source
file's actual location.

Preinstall pure Ruby or Java-platform gems using a matching JRuby installation,
then point this module at that gem directory. The standard library and packaged
default gems are included. MRI-native C extensions are not supported. The module
does not install gems or manage Bundler environments. Runtime pools, forced
timeouts, and Rails hosting are outside this release.

## Failures

Failures throw `modules.bxjruby.RubyExecutionException`, a BoxLang runtime
exception with the original Java/Ruby cause and source in its message. The
exception exposes `getStdout()`, `getStderr()`, `getSource()`, and
`getRubyBacktrace()`. Ruby execution errors do not roll back session state.
Successful calls capture only their own output. Session closing can execute
Ruby `at_exit` handlers. JRuby reports errors from those handlers to stderr;
one-shot calls include that output in their capture. Java termination failures
remain exceptions.

## Development

To build from source, set `JAVA_HOME` to a supported JDK (21–27 for the
checked-in Gradle 9.8.0 wrapper). Java 21 is used for compilation and tests.
On Windows, use `gradlew.bat` in place of `./gradlew`.

```sh
./gradlew build consumerTest
box install
./gradlew testBox
```

The build produces an installable ZIP and SHA-256 checksum under
`build/distributions/`. To test that local package in a consuming project,
install it with CommandBox:

```sh
box install /path/to/bx-jruby/build/distributions/bx-jruby-1.0.0.zip
```

Build output `build/module/` is also a complete module that can be placed in a
configured module directory. Installing the source checkout alone does not
build its JARs.

`consumerTest` extracts the ZIP to an isolated consumer and starts Java with
only BoxLang on its application classpath. TestBox uses that installed module
too. Its JSON report is `build/testbox/report.json`; require `totalFail == 0`,
`totalError == 0`, and `totalPass > 0`. JUnit reports are in `build/reports/tests/`.

CI verifies JUnit and packaged consumers on Java 21/Windows/Linux, plus TestBox
against BoxLang latest, and snapshot.
PR and main-branch release workflows share these checks; releases publish the
verified distribution to ForgeBox and attach the ZIP and checksum to GitHub.

The BoxLang descriptor is authored in `src/main/bx/ModuleConfig.bx` and copied to
the ZIP root during packaging. BoxLang specs, consumer checks, and
fixtures live in `src/test/bx`; Java unit tests live in `src/test/java`.

The module uses MIT licensing. Bundled dependencies retain their own licenses;
see [third-party notices](THIRD-PARTY-NOTICES.md).
