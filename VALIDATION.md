# Validation — DakotaAC 0.19

Verified on 2026-10-23. This document describes compilation and automated regression coverage, not a live Minecraft gameplay certification.

| Target | JDK | Paper API | ProtocolLib API/JAR | Result |
| --- | --- | --- | --- | --- |
| Baseline | 21.0.2 | 1.21.11-R0.1-SNAPSHOT | Maven Central net.dmulloy2:ProtocolLib:5.4.0 | Compile/package successful; 34 tests passed |
| Newer API | 25.0.1 | 26.3.build.218-stable | Official development Paper JAR 5.5.0-SNAPSHOT-d89a5de | Compile/package successful; 34 tests passed |

The same final Java sources were used for both targets. The distributed JAR is the baseline build, emits Java 21 bytecode (class version 65), and contains DakotaAC classes and resources only. Paper, ProtocolLib, and ViaVersion are not bundled.

The newer ProtocolLib JAR was installed into the validation machine's local Maven cache as `net.dmulloy2:ProtocolLib:5.5.0-local` for the second build. This local coordinate is not a published dependency and is not required to build the source distribution. The normal POM uses the public 5.4.0 API baseline.

## Automated coverage

- Flat-double and vector position layouts, boolean and packed-bit ground state, missing fields, and non-finite values.
- Optional packet-name lookup and matching for legacy/split attack and swing families. These tests explicitly set ProtocolLib's version metadata without starting a server.
- VarInt-prefixed brands, case normalization, multi-byte UTF-8, truncation, trailing bytes, overflow, and invalid UTF-8.
- Message placeholders, repeated placeholders, literal dollar/backslash characters, and preventing recursive placeholder expansion.
- Original multiple-based punishment boundaries and weighted flags crossing a boundary.
- Typed settings, numeric bounds, negative timer balances, optional empty lists, and required nonempty decoy lists.
- Configuration coverage for all 44 checks and all 238 annotated check defaults.
- Language-key coverage and command/dependency declarations.

The source contains 615 configuration leaves and 222 language templates. An archive comparison confirmed that all 154 original named numeric/boolean tuning defaults that map to the new settings retain their values.

## Scope and remaining validation

No running Paper server/client session or real cheat-client gameplay test was completed in this environment. In particular, bot rendering, packet serialization against actual NMS classes, movement false-positive rates, and cheat detection rates need testing on the intended server/client combination. API compilation and shape fixtures cannot prove those behaviors.

The uploaded reference ProtocolLib JAR declares `api-version: 26.2`, which is a dependency loading constraint before DakotaAC starts. Use a compatible Bukkit/Spigot artifact on 1.21.11 and a release with the required packet mappings on newer Minecraft versions. A future unsupported ProtocolLib/Minecraft pair may require another adapter update; diagnostics and `/dac status` make such failures visible.

## Reference hashes

- Uploaded ProtocolLib 5.5.0-SNAPSHOT-67ce937: `dfc959e78d9b52637b7fea3549f63911fdc751fa67561dbe6d50889c588502c4`
- Official development ProtocolLib 5.5.0-SNAPSHOT-d89a5de used for validation: `ca3ec620315ff92bee2d6d09af297d77362e99b4791b163135b745262df75ecc`

Only DakotaAC is included in this distribution. Obtain ProtocolLib from its official project.
