# Capability review: core 1.2.0

| Status | Capabilities |
| --- | --- |
| Implemented and tested | Isolated source/file evaluation; native inline component; persistent sessions; owned handles; positional/keyword calls and synchronous blocks; captured output/errors; copied arrays/hashes; case and numeric precision; input/ARGV; explicit binary, symbol and temporal conversion; module reload/unload; installed gems and Bundler setup. |
| Available through Ruby/JRuby | Standard library, classes/modules, Ruby exceptions and threads, Java interoperability, filesystem/network/process APIs, and compatible pure Ruby or Java gems. These use normal Ruby evaluation rather than separate BoxLang wrappers. |
| Unverified | Exhaustive Ruby 4.0 language conformance, every stdlib API/gem, arbitrary threaded Ruby libraries, all regional timezone round trips. Consult JRuby's own compatibility scope. |
| Unsupported by the module | MRI C-extension binaries, opaque handles across sessions/reloads, cross-thread or retained BoxLang callbacks, forced cancellation, process sandboxing, core session pooling. |

Ruby language support comes from bundled JRuby 10.1.2.0. A missing convenience BIF
is not a missing Ruby language feature. When a library needs native extensions,
select its Java platform alternative where one exists.

JRuby's embedded evaluator treats normal `exit` as script termination and returns
its status as the result. `exit!` fails as an embedded exception. Neither terminates
the BoxLang JVM; a reusable session remains available afterward. This behavior is
not a sandbox against deliberate access to Java process APIs.

Web frameworks are hosted by sibling [bx-jruby-rack](https://github.com/tonyjunkes/bx-jruby-rack),
which depends on core's public extension contract. Core owns JRuby classes and
runtimes. The modules load as siblings, with no duplicate runtime JARs.

The BIFs, inline component, lifecycle wiring and registration cleanup are native
BoxLang. Java retains sessions, handles, conversion, immutable options and thread
synchronization. This keeps low-frequency glue editable in BoxLang while direct
JRuby interaction and execution ownership stay together.
