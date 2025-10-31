<p align="center">
  <img src="app/src/main/res/drawable/vcat_logo_tnsp.png" alt="VCAT Logo" width="260">
</p>

<h1 align="center">VCAT — Video Codec Acid Test</h1>

---

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

---

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

---

## License

VCAT is licensed under **GPL-3.0-or-later**.  
See: https://www.gnu.org/licenses/gpl-3.0.html

Use of the VCAT logo and artwork is permitted when discussing, documenting, demonstrating, or promoting VCAT itself.  Any other usage requires prior written permission from RoncaTech LLC.

Contact: legal@roncatech.com • https://roncatech.com/legal
