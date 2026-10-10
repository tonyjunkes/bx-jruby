---
name: bx-jruby
description: "Use this skill when executing Ruby inside BoxLang with the bx-jruby module: jrubyEval(), jrubyEvalFile(), jrubySession(), the bx:jruby component, explicit bindings, captured results, session-owned Ruby objects, and JRuby load paths or installed gems."
---

# bx-jruby: Execute Ruby in BoxLang

## Installation

Requires BoxLang 1.18.0+ and Java 21+. The ForgeBox slug is `bx-jruby`; use
`bxjruby` for runtime settings and module reloads.

```sh
install-bx-module bx-jruby
# CommandBox
box install bx-jruby
```

## BIFs

| Function | Behavior |
| --- | --- |
| `jrubyEval(script, bindings={}, options={})` | Evaluate source in a fresh runtime, capture the result, then close it. |
| `jrubyEvalFile(path, bindings={}, options={})` | Resolve a BoxLang file path, including mappings, and evaluate it in a fresh runtime. |
| `jrubySession(options={})` | Create an explicitly owned, reusable Ruby runtime. |

## Basic Usage

```js
result = jrubyEval("puts 'Hello from Ruby'; 6 * 7");
println(result.value); // 42
print(result.stdout);  // Hello from Ruby

sum = jrubyEval("numbers.sum", { numbers: [10, 20, 30] });
println(sum.value); // 60

fromFile = jrubyEvalFile("./ruby/calculate.rb", { amount: 12 });
```

The file example assumes `ruby/calculate.rb` exists. Each evaluation is isolated:
locals, constants, globals, and loaded application code do not survive into the
next one-shot call. Use a session when subsequent calls need that state.

## Variable Bindings

Pass inputs explicitly; caller scopes are never copied automatically.
Binding names must be Ruby local-variable names matching
`[a-z_][a-zA-Z0-9_]*`.

```js
inputs = { amount: 12, items: [1, 2] };
result = jrubyEval("items << 3; amount = amount * 2; [amount, items]", inputs);
println(result.value); // [24, [1, 2, 3]]
println(inputs.amount); // 12
println(inputs.items);  // [1, 2]
```

Arrays and structs are copied into Ruby arrays and hashes. Ruby assignments and
collection mutations do not update the input struct. Other Java objects pass as
Java proxies; invoking their mutating methods can change the original objects.

## Using Ruby Modules (Files)

Create `ruby/mathutils.rb`:

```ruby
def factorial(n)
  (1..n).reduce(1, :*)
end
```

Load the file and call its method within the same session:

```js
ruby = jrubySession({ workingDirectory: expandPath("./ruby") });
try {
    ruby.evalFile("mathutils.rb");
    result = ruby.call(null, "factorial", [10]);
    println(result.value); // 3628800
} finally {
    ruby.close();
}
```

Session file paths resolve against the fixed working directory and do not use
later request mappings. Resolve a mapped path with `expandPath()` before passing
it to `ruby.evalFile()`. `require_relative` uses the source file's location.

## Reusable Sessions and Ruby Objects

Session methods are Java methods; pass their arguments positionally.

| Method | Returns |
| --- | --- |
| `eval(script, bindings={})` | `{ value, stdout, stderr }` |
| `evalFile(path, bindings={})` | `{ value, stdout, stderr }` |
| `put(name, value)` | Sets a persistent local; returns void. |
| `get(name)` | Converted local value; null for absent/nil. |
| `call(receiver, method, args=[], kwargs={})` | `{ value, stdout, stderr }`; null receiver calls a top-level Ruby method. |
| `close()` | Idempotently closes the runtime; returns void. |
| `closeAndCapture()` | Closes with a fresh `{ stdout, stderr }` capture of exit handlers; rejects new work while waiting for active work. |

```js
ruby = jrubySession();
try {
    ruby.put("counter", 0);
    ruby.eval("counter += 1");
    println(ruby.get("counter")); // 1

    ruby.eval("class Greeter; def greet(name, prefix: 'Hello'); prefix + ', ' + name; end; end");
    greeter = ruby.eval("Greeter.new").value;
    greeting = ruby.call(greeter, "greet", ["BoxLang"], { prefix: "Hi" });
    println(greeting.value); // Hi, BoxLang
} finally {
    ruby.close();
}
```

Locals and supplied bindings persist within a session. Ruby objects become
opaque handles: use them as `call()` receivers or inputs in their owning session.
Do not inspect them as BoxLang structs, move them between sessions, or reuse
them after close/module reload. One-shot evaluations reject results containing
Ruby objects, including nested objects; use a session for those results.

Close sessions in `finally`. Work on one session serializes; separate sessions
are independent. Closing waits for active work and rejects subsequent work.
Module unload closes outstanding sessions, but applications should release their
own sessions promptly. Closing does not forcibly cancel running Ruby code.

## `bx:jruby` Component

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

The body and `variable` assignment target are required. Optional `bindings` and
`options` follow the BIF behavior. The component captures the body as source,
evaluates it in a fresh runtime, and assigns the result struct.

BoxLang processes the template body first. Inside `bx:output`, write Ruby
interpolation `#{name}` as `##{name}`. Prefer `.rb` files for complex source
containing BoxLang template delimiters or tags.

## Capturing the Result

Evaluation and method calls return `{ value, stdout, stderr }`:

- `value`: the last Ruby expression or method return; Ruby `nil` becomes null.
- `stdout`: captured standard output, including Unicode and line breaks.
- `stderr`: separately captured standard error.

Nothing prints automatically or copies back into caller scopes. Use `print()`
for captured output to preserve its existing line breaks.

Ordinary scalars, arrays, and hashes convert to independent BoxLang values.
Symbols become strings; large integers and finite BigDecimal values retain
precision. Non-finite BigDecimal values become doubles preserving NaN/infinity. Ruby hashes become
ordered, case-sensitive structs. Only string/symbol keys are supported;
`:name` and `"name"` collide and raise an error. Unsupported keys and cyclic
collections also raise errors. Custom values such as Ruby classes, dates, and
rational numbers require session handles.

Ruby execution failures are catchable BoxLang exceptions retaining the original
cause, source/backtrace, and captured output:

```js
try {
    jrubyEval("puts 'before failure'; raise 'failed'");
} catch (any error) {
    print(error.getStdout());
    print(error.getStderr());
    println(error.getRubyBacktrace());
}
```

These accessors belong to `modules.bxjruby.RubyExecutionException`; ordinary
BoxLang argument/validation errors may have different accessors. A failed Ruby
operation does not roll back session state.

## Common Patterns

Use the bundled standard library:

```js
json = jrubyEval("require 'json'; JSON.generate({answer: 42})");
println(json.value); // {"answer":42}
```

Configure a runtime for application libraries and preinstalled gems:

```js
ruby = jrubySession({
    workingDirectory: expandPath("./ruby"),
    loadPaths: ["./lib"],
    gemHome: "./vendor/gems",
    gemPaths: ["./vendor/gems"]
});
try {
    // Requires a compatible gem already installed in ruby/vendor/gems.
    result = ruby.eval("require 'my_gem'; true");
} finally {
    ruby.close();
}
```

The same options can be module defaults in `boxlang.json` under
`modules["bxjruby"].settings`. Explicit runtime options replace the corresponding
defaults; path lists are not merged. Relative working directories resolve from
the caller directory; other paths resolve from the selected working directory.
Additional load paths preserve JRuby's bundled standard-library paths.

Core 1.1.0 also accepts `env` and `outputLimit`:

```js
result = jrubyEval("puts ENV.fetch('APP_MODE'); 42", {}, {
    env: { APP_MODE: "batch" }, outputLimit: 65536
});
```

`env` is a string map applied over the process environment within this runtime.
Explicit `env` replaces the module's default overlay. `GEM_HOME` and `GEM_PATH`
must agree with separately configured gem options. `outputLimit` bounds retained
characters per stream per operation; zero preserves unlimited capture. Truncated
output includes a marker. For a session's final exit-handler output, call
`closeAndCapture()` instead of `close()`; repeated calls return the final capture.

## Common Pitfalls

- Do not load a method with `jrubyEvalFile()` and expect a later `jrubyEval()` to find it; use one session.
- Keep positional Ruby hashes in `args`; pass Ruby keywords through the separate `kwargs` struct.
- Preserve hash key case when reading converted results; Ruby `"Name"` and `"name"` remain distinct.
- Execute trusted code only: JRuby has the host process's Java, file, and network permissions.
- Use JRuby-compatible pure Ruby or Java-platform gems. MRI C extensions are unsupported; this module does not install gems or manage Bundler.
