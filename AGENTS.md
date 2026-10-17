# bx-jruby contributor guidance

Read `README.md` for public usage, `SKILL.md` when integrating the module, and
`docs/interoperability.md` when changing input, conversion, blocks or Bundler.
`docs/capabilities.md` records verified behavior and remaining gaps.

## Build and validation

- Use Java 21. Dependencies and versions are declared in `build.gradle` and `gradle.properties`.
- Windows: `./gradlew.bat build testBox`; other hosts: `./gradlew build testBox`.
- TestBox must already be installed; `box install` installs the development dependency.
- `consumerTest` and `consumerOverrideTest` load the extracted module with only BoxLang on the host classpath.
- `benchmark` records local BIF/session observations; its Gradle properties can select a released module root and output path.
- Root metadata versions must agree across `box.json`, `gradle.properties` and `src/main/bx/ModuleConfig.bx`.
- Update the manifest's compiled GitHub ZIP location with its version. Upload the release ZIP before ForgeBox publication.

| Verified command | Purpose |
| --- | --- |
| `bash ./gradlew build testBox` | Build and verify |

## Language and ownership

- Native BIFs/components and lifecycle wiring live in `src/main/bx`.
- Java in `src/main/java/modules/bxjruby` owns JRuby, sessions, handles, conversion, immutable options and synchronization.
- Preserve caller-relative paths when changing native wrappers. Resolve Java through the module activation's classloader.
- Shared BIF instances may retain only activation-local metadata, never a calling request/context.
- Handles are valid only in their owning live session and activation. Keep automatic conversion stable; new conversions are explicit.
- Callback leases expire after synchronous invocation. Reject callback reentry at every executable session entry before acquiring its monitor, including cross-thread attempts.
- Public close methods reject active callbacks. Manager-owned shutdown marks all sessions draining, waits for active work and defers reentrant termination until the outermost operation unwinds.
- Module cleanup drains extensions before sessions and unregisters only owned descriptors, even after failure.

## Changes

- Keep readable four-space indentation, expanded blocks and blank lines between methods/tasks.
- Document Java methods/constructors with Javadoc and native interfaces with useful comments.
- Exercise behavioral changes through session interfaces and installed-package consumers.
- Update README, relevant docs, examples, SKILL.md and this file with changed public behavior or workflows.
- Core owns the single JRuby distribution; sibling extensions use the public runtime contract.
