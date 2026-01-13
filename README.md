<p align="center">
  <img src="app/src/main/res/drawable/vcat_logo_tnsp_with_tm.png" alt="VCAT Logo" width="260"> 
</p>

<h1 align="center">VCAT™ — Video Codec Acid Test™</h1>

## About VCAT

VCAT (Video Codec Acid Test) is a video decoder benchmarking tool for Android devices.

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

## Vision

VCAT’s mission is to enable users, developers, and OEMs to understand **how well devices handle modern video standards under real conditions**, not just short synthetic tests.

## Components
| Component | Role |
|-----------|------|
| **VCAT (app)** | Benchmarking UI, telemetry collection, test orchestration, reporting |
| **[libvcat](https://github.com/jonathannah/libvcat)** | Core media stack: decoder adapters (e.g., dav1d AV1, optional vvdec VVC), parsers/extractors, JNI/native glue, capability probes |

This separation keeps the app lightweight and lets media-layer work (decoders, parsing, performance hooks) evolve independently from UI and workflow code.

## Project Status

VCAT is currently in active development and work is ongoing.

* Source code  
* Telemetry pipeline  
* dav1d integration

### Help needed
* Continuous UI improvements  
* Additional test vector libraries
* H264, H265, VP9, and VVC bundled decoders (in libvcat).
* VCAT-Neg mode to use VLC as the video player to prevent unscrupulous vendors from gaming their system when VCAT is running.

Please contribute!

Contributions are handled through merge requests on the VCAT project.

Feedback is welcome — issues and PRs encouraged!

### Feedback
- [Use the discord channel for VCAT conversations](https://discord.gg/36XQYATF)

### Bugs
- Open issues on VCAT or libvcat github projects.  If unsure which to use, use VCAT.
- Include: **steps to reproduce**, **expected vs actual behavior**, **timestamp & timezone**, **browser/app version**, and **screenshots**.

## Disclaimer of Suitability
VCAT is provided for general benchmarking and evaluation purposes only. RoncaTech makes no representations or guarantees that VCAT is suitable for any particular purpose, environment, or workflow. You are solely responsible for determining whether VCAT meets your needs. Under no circumstances should reliance on VCAT substitute for your own testing, validation, or professional judgment.

## Limitation of Liability
TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL RONCATECH, LLC OR ITS AFFILIATES, CONTRIBUTORS, OR SUPPLIERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS, REVENUE, DATA, OR USE, ARISING OUT OF OR IN CONNECTION WITH YOUR USE OF VCAT, EVEN IF RONCATECH HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.  

You agree that your sole and exclusive remedy for any claim under or related to VCAT will be to discontinue use of the software.

## Patent Notice (No Patent Rights Granted)
VCAT and libvcat are distributed under GPL-3.0-or-later. Nothing in this README, the source code, or the license grants you any rights under third-party patents, including without limitation patents essential to implement or use media codecs and container formats (e.g., AVC/H.264, HEVC/H.265, VVC/H.266, MPEG-2, AAC, etc.).  
- You are solely responsible for determining whether your use, distribution, or deployment of VCAT/libvcat requires patent licenses from any third party (including patent pools or individual patent holders) and for obtaining any such licenses.  
- Contributions to this project may include a limited patent grant from contributors as specified by GPL-3.0-or-later, but no additional patent rights are provided, and no rights are granted on behalf of any third party.  
- Use of bundled or integrated decoders/parsers does not imply or provide patent clearance for any jurisdiction. Your compliance with all applicable intellectual property laws remains your responsibility.

## License

VCAT is licensed under **GPL-3.0-or-later**.  
See: https://www.gnu.org/licenses/gpl-3.0.html

Contact us for commercial licensing if you can’t use GPL

Use of the VCAT logo and artwork is permitted when discussing, documenting, demonstrating, or promoting VCAT itself.  Any other usage requires prior written permission from RoncaTech LLC.

Contact: https://www.roncatech.com/contact
