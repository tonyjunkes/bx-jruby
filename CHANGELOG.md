# Changelog

## 1.2.0 (unreleased)

- Move public BIFs, the inline component and descriptor cleanup to native BoxLang.
- Add copied stdin/ARGV, opt-in Bundler setup, explicit binary/symbol/date/time conversion and scalar handles.
- Add operation-scoped BoxLang block callbacks through jrubyCall with reentry and lifetime checks.
- Reject cross-thread close and captured-close attempts during callbacks before waiting on the session lock.
- Expand interop regression tests, examples, skills and contributor guidance.
- Publish a concrete compiled GitHub ZIP location to ForgeBox after the release asset exists; validate the versioned location during builds.

## 1.1.0

- Make captured close reject new work before waiting for active execution; document the new core options and cleanup API in the agent skill.

- Add immutable runtime-local `env` options with string validation and gem-setting conflict detection.
- Add opt-in bounded `outputLimit` captures, preserving unlimited capture by default.
- Add public request-independent session creation and pre-close callbacks for extensions such as bx-jruby-rack. Extensions must drain their work before returning from a callback.
- Document Java methods and constructors with Javadoc, including conversion and runtime ownership contracts; validate Javadoc during normal builds.

## 1.0.0

- Add isolated Ruby string/file evaluation and the `bx:jruby` component.
- Add explicitly owned sessions, Ruby object handles, and keyword method calls.
- Capture output and preserve Ruby failure diagnostics.
- Bundle JRuby 10.1.2.0 with its standard library; support preinstalled gems.
- Require BoxLang 1.18.0+ and Java 21+.
- Define metadata, settings, and lifecycle hooks in `ModuleConfig.bx`, backed by Java runtime management.
- Organize BoxLang tests and fixtures under `src/test/bx` alongside Java unit tests.
- Add shared PR/release checks, version validation, and publication of the tested distribution with a SHA-256 checksum.
- Align configuration and lifecycle cleanup with the `bxjruby` runtime name.
- Reject colliding input keys and duplicate runtime options; release discarded Ruby object handles for garbage collection.
- Preserve non-finite BigDecimal results as NaN/infinity rather than converting them to zero.
- Build with the Gradle 9.8.0 wrapper and verify its distribution checksum.
