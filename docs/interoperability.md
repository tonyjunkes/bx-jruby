# Ruby interoperability

All new interfaces in 1.2.0 are additive. Existing automatic conversions and result
envelopes remain the defaults. Native BIFs resolve the Java bridge through their
own module loader and use the caller context only during the operation.

## Standard input and arguments

```js
result = jrubyEval("[STDIN.read, ARGV]", {}, {stdin: "hello", argv: ["one", "two"]});
```

stdin accepts a string (UTF-8) or BoxLang binary/Java byte[]. argv accepts an array
of strings. Both are copied when the session is created and initialized before
Ruby starts. Persistent sessions consume the same input stream across operations;
subsequent reads reach EOF. They default to empty input and arguments.

## Explicit conversions

Session methods use positional arguments:

| Method | Result |
| --- | --- |
| evalHandle(script, bindings) | The standard envelope with an owned handle, including scalar/nil results. Pass {} for no bindings. |
| toRuby(value, type) | A session-owned Ruby handle. |
| toBoxLang(handle, type) | A copied native value. |

Conversion names are case-insensitive: auto, binary, string, symbol, date, time.
Binary input requires byte[] and produces an ASCII-8BIT Ruby String. Binary output
requires a Ruby String and copies its original bytes, including invalid text.
Explicit string/symbol input requires a string; symbol creates a Ruby Symbol.
Explicit text output accepts Ruby String/Symbol and transcodes to UTF-8 with errors
for invalid or undefined conversion. auto keeps the existing automatic rules.

Date/time input accepts ISO-8601 text or BoxLang DateTime. date creates Ruby Date;
time creates Ruby Time. Matching explicit outputs produce BoxLang DateTime: dates
use midnight UTC; time preserves its offset and nanoseconds. A named regional time
zone is not retained beyond the ISO offset. Unsupported types stay available as
owned handles for ordinary Ruby method calls.

```js
ruby = jrubySession();
try {
    bytes = binaryDecode("00ff8041", "hex");
    data = ruby.toRuby(bytes, "binary");
    roundTrip = ruby.toBoxLang(data, "binary");
    raw = ruby.evalHandle("[255].pack('C')", {}).value;
    originalBytes = ruby.toBoxLang(raw, "binary");
    instant = ruby.toRuby("2026-10-16T12:00:00+05:30", "time");
    dateTime = ruby.toBoxLang(instant, "time");
} finally {
    ruby.close();
}
```

Handles remain valid only in their owning live session and module activation.
One-shot results continue rejecting handles; use a session for explicit conversion.

## Ruby blocks

jrubyCall(session, receiver, method, args=[], kwargs={}, block=null) is a native
BoxLang BIF supporting named arguments and an optional BoxLang closure. A null
receiver invokes a top-level Ruby method. Arguments and keyword hashes stay separate.

```js
ruby = jrubySession();
try {
    doubled = jrubyCall(session=ruby, receiver=[1,2,3], method="map", block=function(value) {
        return value * 2;
    });
    println(doubled.value);
} finally {
    ruby.close();
}
```

Yield arguments and callback results use normal conversion. The callback runs on
the invoking thread and expires when the Ruby call returns or fails. Ruby may retain
the Proc, but invoking it later or from another thread fails before entering BoxLang.
The lease releases the closure/context on expiry. Same-session execution or close
from its callback is rejected; use the yielded native values. Exceptions retain
their original cause and Ruby stdout/stderr capture. Callback BoxLang output uses
BoxLang's normal output facilities rather than Ruby's captured streams.

## Bundler-aware execution

```js
ruby = jrubySession({
    workingDirectory: expandPath("./ruby-app"),
    bundler: {gemfile: "Gemfile", frozen: true},
    env: {BUNDLE_PATH: expandPath("./bundles/ruby-app")}
});
try { result = ruby.evalFile("main.rb"); } finally { ruby.close(); }
```

bundler defaults to false. true selects Gemfile in workingDirectory; a struct accepts
gemfile and frozen (default false). Frozen setup requires Gemfile.lock. Bundler runs
before any user source, loads only installed gems and never invokes installation.
Explicit env.BUNDLE_GEMFILE/BUNDLE_FROZEN must agree with generated settings.
Embedded setup disables Bundler auto-install and automatic version switching in
memory, overriding local/global auto-install settings. Install the desired Bundler
and application gems explicitly; setup cannot fetch or exec a different Bundler.
Install the bundle explicitly using core's JRuby before running the application.
A failed Bundler initialization is discarded; later evaluation cannot bypass setup.
