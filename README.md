<p align="center">
  <img src="app/src/main/res/drawable/vcat_logo_tnsp_with_tm.png" alt="VCAT Logo" width="260"> 
</p>

<h1 align="center">VCAT™ — Video Codec Acid Test™</h1>

## About vcat-d

vcat-d (Video Codec Acid Test for Decoders) is a video decoder benchmarking tool for Android devices.

It is designed to evaluate real-world decode performance and platform stability through long-running playback workloads with detailed telemetry collection.

## Key Capabilities

- **Built on the ExoPlayer framework**  
  – stable, modern media pipeline with full Android platform support

- **Supports all system video decoders**  
  – hardware-accelerated H.264, HEVC, VP9, AV1 (where available)

- **Bundled dav1d software decoder for AV1**  
  – raises a consistent performance baseline across devices

- **Long-running decode workloads**  
  – e.g., battery drain testing, thermal behavior, performance throttling studies

- **Detailed telemetry logging**  
  – battery / CPU usage / CPU frequency / memory / frame drops

- **Open Source — GPL-3.0-or-later**  
  – free to use, modify, and improve
- **Clean decoder plugin model
   - Special handling for decoders not supported by ExoPlayer: [vvdec example][https://github.com/Video-Codec-Acid-Test-VCAT/vcatd-vvdec-plugin]

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
- Any limitations (trial expiry, watermarking, usage caps) must be disclosed in your
  integration instructions
- You do not need to open-source your decoder
- Modifications to `vcat-d` itself remain GPL

## Components
| Component | Role |
|-----------|------|
| **vcat-d (app)** | Benchmarking UI, telemetry collection, test orchestration, reporting |
| **[libvcatd](https://github.com/jonathannah/libvcat)** | Core media stack: decoder adapters (e.g., dav1d AV1, optional vvdec VVC), parsers/extractors, JNI/native glue, capability probes |

This separation keeps the app lightweight and lets media-layer work (decoders, parsing, performance hooks) evolve independently from UI and workflow code.

## Project Status

vcat-d is currently in active development and work is ongoing.

* Source code  
* Telemetry pipeline  
* dav1d integration

### Help needed
* Continuous UI improvements  
* Additional test vector libraries
* H264, H265, VP9, and VVC bundled decoders (in libvcatd).
* vcat-d-Neg mode to use VLC as the video player to prevent unscrupulous vendors from gaming their system when vcat-d is running.

Please contribute!

Contributions are handled through merge requests on the vcat-d project.

Feedback is welcome — issues and PRs encouraged!

### Feedback
- Use the discussion thread in this project for feedback

### Bugs
- Open issues on vcat-d or libvcatd github projects.  If unsure which to use, use vcat-d.
- Include: **steps to reproduce**, **expected vs actual behavior**, **timestamp & timezone**, **browser/app version**, and **screenshots**.

## Disclaimer of Suitability
vcat-d is provided for general benchmarking and evaluation purposes only. RoncaTech makes no representations or guarantees that vcat-d is suitable for any particular purpose, environment, or workflow. You are solely responsible for determining whether vcat-d meets your needs. Under no circumstances should reliance on vcat-d substitute for your own testing, validation, or professional judgment.

## Limitation of Liability
TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL RONCATECH LLC OR ITS AFFILIATES, CONTRIBUTORS, OR SUPPLIERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS, REVENUE, DATA, OR USE, ARISING OUT OF OR IN CONNECTION WITH YOUR USE OF vcat-d, EVEN IF RONCATECH HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

RoncaTech does not provide any remedy beyond the right to discontinue use of the software.

## Patent Notice (No Patent Rights Granted)
vcat-d and libvcat are distributed under GPL-3.0-or-later. Nothing in this README, the source code, or the license grants you any rights under third-party patents, including without limitation patents essential to implement or use media codecs and container formats (e.g., AVC/H.264, HEVC/H.265, VVC/H.266, MPEG-2, AAC, etc.).  
- You are solely responsible for determining whether your use, distribution, or deployment of vcat-d/libvcat requires patent licenses from any third party (including patent pools or individual patent holders) and for obtaining any such licenses.  
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
