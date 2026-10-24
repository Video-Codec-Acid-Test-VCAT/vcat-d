<p align="center">
  <img src="app/src/main/res/drawable/vcat_logo_tnsp_with_tm.png" alt="vcat-d Logo" width="260"> 
</p>

<h1 align="center">vcat-d™ (formerly VCAT™) — Video Codec Acid Test™</h1>

## About vcat-d

vcat-d (Video Codec Acid Test for Decoders) is a video decoder benchmarking tool for Android devices.

It is designed to evaluate real-world decode performance and platform stability through long-running playback workloads with detailed telemetry collection.

## Key Capabilities

- **Built on the ExoPlayer framework**  
  – stable, modern media pipeline with full Android platform support

- **Supports all system video decoders**  
  – hardware-accelerated H.264, HEVC, VP9, AV1 (where available)

- **AV1 software decoding via the dav1d plugin**  
  – a consistent performance baseline across devices, delivered as an external decoder plugin ([vcatd-dav1d-plugin](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav1d-plugin))

- **Long-running decode workloads**  
  – e.g., battery drain testing, thermal behavior, performance throttling studies

- **Detailed telemetry logging**  
  – battery / CPU usage / CPU frequency / memory / frame drops

- **Open Source — GPL-3.0-or-later**  
  – free to use, modify, and improve
- **Clean decoder plugin model**  
  – All decoders are external `.aar` plugins: [dav1d (AV1)](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav1d-plugin), [vvdec (VVC/H.266)](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-vvdec-plugin)

## Vision

vcat-d's mission is to enable users, developers, and OEMs to understand **how well devices handle modern video standards under real conditions**, not just short synthetic tests.

## Decoder Plugin Exception

Android devices vary enormously in their ability to handle software video decoding.
vcat-d's mission is to make that capability visible and comparable across devices — but
that mission is only as useful as the decoders it can test.

Some codecs, including current-generation VVC and future formats, may not have
open-source decoder implementations. Excluding them would limit vcat-d's relevance
precisely where benchmarking matters most: at the frontier of what devices can handle.

To address this, vcat-d provides a narrow license exception allowing decoder vendors to
integrate a closed-source `.aar` plugin without open-sourcing their decoder SDK.

**If you are a decoder vendor** and would like your decoder evaluated within vcat-d,
see [LICENSE-PLUGIN-EXCEPTION.md](LICENSE-PLUGIN-EXCEPTION.md) for the full terms.
The short version:

- Your decoder `.aar` and integration instructions must be publicly available
- Any limitations (trial expiry, watermarking, usage limits, etc.) must be disclosed in your
  integration instructions
- You do not need to open-source your decoder
- Modifications to `vcat-d` itself remain GPL

## Components
| Component | Role |
|-----------|------|
| **vcat-d (app)** | Benchmarking UI, telemetry, test orchestration, reporting, container parsing (MP4), and the decoder-plugin loader/registry |
| **[vcatd-dav1d-plugin](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav1d-plugin)** | External AV1 software decoder plugin (dav1d) |
| **[vcatd-dav2d-plugin](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav2d-plugin)** | External AV2 software decoder plugin (dav2d), built from VideoLAN upstream |
| **vcatd-dav2d-plugin** *(branch `vcat-dav2d-ee033a4_at_salomethirot-arm`)* | The same plugin built against Arm's optimization branch of dav2d — installs alongside the upstream build |
| **[vcatd-vvdec-plugin](https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-vvdec-plugin)** | External VVC/H.266 software decoder plugin (vvdec) |

Decoders are delivered as external `.aar` plugins dropped into `decoder-plugins/`. This keeps the app lightweight and lets each decoder evolve independently from the UI and workflow code.

## Building the vcat-d plugin api
This API is used by all decoder plugins.

./gradlew :decoder-plugin-api:publishLocal

## Building vcat-d with decoder plugins

Decoders are external `.aar` plugins. Build the plugin(s) you need, drop the `.aar` into
`vcat-d/decoder-plugins/`, then build the app — the `copyDecoderPluginsToAssets` task bundles
every `.aar` in that folder automatically.

### Prerequisites

- Android Studio (latest stable)
- JDK 17+
- NDK 27.0.12077973 (install via Android Studio SDK Manager)
- CMake 3.22+ and Ninja (install via SDK Manager)
- For the vvdec and dav2d plugins: Python 3.8+ and Meson ≥ 1.2 (`pip install meson`) — both build their decoder from source

### Repositories

Clone into the same parent directory:
```bash
git clone https://github.com/Video-Codec-Acid-Test-VCAT/vcat-d.git
git clone https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav1d-plugin.git
git clone https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav2d-plugin.git   # optional (AV2)
git clone https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-vvdec-plugin.git   # optional (VVC/H.266)
```

### AV1 (dav1d) plugin

```bash
cd vcatd-dav1d-plugin
./gradlew :vcatd-dav1d-plugin:dist
cp app/build/outputs/dist/vcatd-dav1d-plugin.aar ../vcat-d/decoder-plugins/
```
dav1d links from prebuilt static libraries bundled in the plugin, so this build is fast.

### AV2 (dav2d) plugin — optional, and why there are two of them

AV2 is built twice, from the same plugin source against two different revisions of dav2d, and
both `.aar` files are installed at once. They register as distinct decoders — `vcat.dav2d` and
`vcat.dav2d.st-arm` — so `video/av02` can be pointed at either from **Conditions → Decoder
Configuration** without rebuilding or reinstalling.

That is the point of carrying both. dav2d is under active optimization, and Arm maintain a branch
of it carrying AArch64 assembly that has not yet landed upstream. Having both decoders resident
on the same device lets a run isolate the effect of that work: same clip, same thread count, same
display and thermal conditions, same battery, one variable. On a MediaTek MT6878 the upstream
build and Arm's branch differ enough to change the outcome of a test — Arm's branch has measured
**32% longer battery life on identical content at identical throughput**, and reaches real-time
decode on 1080p30 AV2 where the upstream build of the day did not.

The upstream build is the reference. The st-arm build is the thing being measured against it.

**Upstream dav2d:**

```bash
cd vcatd-dav2d-plugin
./gradlew dist
cp app/build/outputs/dist/vcatd-dav2d-plugin.aar ../vcat-d/decoder-plugins/
```

**Arm's optimization branch** — a branch of the same repository, so clone it a second time into a
separate directory rather than switching branches in place:

```bash
git clone -b vcat-dav2d-ee033a4_at_salomethirot-arm \
    https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-dav2d-plugin.git \
    vcatd-dav2d-st-arm-plugin
cd vcatd-dav2d-st-arm-plugin
./gradlew dist
cp app/build/outputs/dist/vcatd-dav2d-st-arm-plugin.aar ../vcat-d/decoder-plugins/
```

> **Note:** both builds compile dav2d from source with Meson/Ninja, so the first build takes
> several minutes per ABI. Each pins a specific dav2d commit, recorded in `app/build.gradle`, so a
> benchmark number can always be traced back to one decoder revision. The st-arm branch also
> carries local patches under `patches/`, applied at build time.

### VVC / H.266 (vvdec) plugin — optional

```bash
cd vcatd-vvdec-plugin
./gradlew :app:dist
cp app/build/outputs/dist/vcatd-vvdec-plugin.aar ../vcat-d/decoder-plugins/
```
> **Note:** the vvdec plugin builds vvdec (Fraunhofer H.266 decoder) from source — the first build takes ~10–15 minutes per ABI. Subsequent builds are cached.

### Build the app

```bash
cd ../vcat-d
./gradlew :app:assembleRelease
```

## Project Status

vcat-d is currently in active development and work is ongoing.

* Source code
* Telemetry pipeline
* dav1d & vvdec integration

### Help needed

* Continuous UI improvements
* Additional test vector libraries
* Additional decoder plugins and container-format support (IVF, MKV) beyond the current MP4 + AV1/VVC.
* vcat-d-Neg mode to use VLC as the video player to prevent unscrupulous vendors from gaming their system when vcat-d is running.

Please contribute!

Contributions are handled through merge requests on the vcat-d project.

Feedback is welcome — issues and PRs encouraged!

### Feedback

* Use the discussion thread in this project for feedback

### Bugs

* Open issues on the vcat-d GitHub project (or the relevant decoder-plugin project). If unsure, use vcat-d.
* Include: **steps to reproduce**, **expected vs actual behavior**, **timestamp & timezone**, **browser/app version**, and **screenshots**.

## Disclaimer of Suitability

vcat-d is provided for general benchmarking and evaluation purposes only. RoncaTech makes no representations or guarantees that vcat-d is suitable for any particular purpose, environment, or workflow. Benchmark results produced by vcat-d reflect conditions at the time of testing and may vary based on device state, configuration, test vector selection, and other factors outside RoncaTech's control. You are solely responsible for determining whether vcat-d and its results meet your needs. Under no circumstances should reliance on vcat-d or its output substitute for your own testing, validation, or professional judgment.

## Limitation of Liability
TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL RONCATECH LLC OR ITS AFFILIATES, CONTRIBUTORS, OR SUPPLIERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS, REVENUE, DATA, OR USE, ARISING OUT OF OR IN CONNECTION WITH YOUR USE OF vcat-d, EVEN IF RONCATECH HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

RoncaTech does not provide any remedy beyond the right to discontinue use of the software.

## Patent Notice (No Patent Rights Granted)
vcat-d and its decoder plugins are distributed under GPL-3.0-or-later. Nothing in this README, the source code, or the license grants you any rights under third-party patents, including without limitation patents essential to implement or use media codecs and container formats (e.g., AVC/H.264, HEVC/H.265, VVC/H.266, AV1, MPEG-2, AAC, etc.).  
- You are solely responsible for determining whether your use, distribution, or deployment of vcat-d requires patent licenses from any third party (including patent pools or individual patent holders) and for obtaining any such licenses.  
- Contributions to this project may include a limited patent grant from contributors as specified by GPL-3.0-or-later, but no additional patent rights are provided, and no rights are granted on behalf of any third party.  
- Use of bundled or integrated decoders/parsers does not imply or provide patent clearance for any jurisdiction. Your compliance with all applicable intellectual property laws remains your responsibility.

## License

vcat-d is licensed under **GPL-3.0-or-later** with a narrow additional permission for decoder `.aar` plugins.  
See:
- GPL-3.0 license text: https://www.gnu.org/licenses/gpl-3.0.html
- Decoder plugin additional permission: [LICENSE-PLUGIN-EXCEPTION.md](LICENSE-PLUGIN-EXCEPTION.md)

This additional permission applies only to `vcat-d` code and does not waive, modify, or supersede any license obligations arising from third-party code, tools, or libraries included in or used by a decoder plugin.

The public exception only covers public release under the stated conditions. It does not authorize private external distribution to customers, partners, or other third parties. Those rights require a separate commercial license from RoncaTech.

Use of the vcat-d logo and artwork is permitted when discussing, documenting, demonstrating, or promoting vcat-d itself. Any other usage requires prior written permission from RoncaTech LLC.

Contact: https://www.roncatech.com/contact
