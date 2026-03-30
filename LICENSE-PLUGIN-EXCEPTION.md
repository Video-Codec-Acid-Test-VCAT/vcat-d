# Additional Permission Under GPLv3 Section 7 for Decoder `.aar` Plugins

#THIS IS DRAFT TEXT AND NOT CURRENTLY ADOPTED< NO RIGHTS ARE GIVEN BY THIS DRAFT LICENSE.

The VCAT project (`vcat-d`) is licensed under GNU General Public License version 3 or, at your option, any later version ("GPL-3.0-or-later").

As an additional permission under GPLv3 Section 7, the copyright holder for `vcat-d` grants the following narrow exception for decoder plugins:

1. A distributor may provide a closed-source decoder `.aar` plugin that implements the published VCAT plugin API and use it to build an APK from the official `vcat-d` codebase.

2. The decoder `.aar` plugin binary, together with clear build and integration instructions, must be made available in a public repo accessible without NDA or similar restrictions, but the source code for the decoder `.aar` plugin may remain private.

3. Any modifications to `vcat-d`, other than the decoder `.aar` plugin itself, must remain publicly available in source form under GPL-3.0-or-later.

4. This additional permission applies only to the decoder `.aar` plugin and does not permit non-public changes to the benchmark harness, test logic, telemetry, UI, orchestration, or other `vcat-d` code.

5. A distributor may publicly distribute an APK built from the official public `vcat-d` codebase together with its decoder `.aar` plugin, subject to the above conditions.

6. Private distribution of such an APK to customers, partners, or other third parties is not covered by this additional permission.

7. This additional permission applies only to `vcat-d` code and does not waive, modify, or supersede any license obligations arising from third-party code, tools, or libraries included in or used by a decoder plugin.

## Benchmark Integrity

Benchmark integrity rules are governed separately and are not expanded by this additional permission. In particular, this additional permission does not authorize undisclosed benchmark-only optimizations or non-public benchmark-affecting changes to `vcat-d`.
