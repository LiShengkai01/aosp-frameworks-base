# MobileAgentService — AOSP Framework Modifications

Platform-layer mechanisms for Android that enable GUI agents to operate applications
efficiently and concurrently with users—without occupying the screen, competing for
GPU resources, or relying on the screenshot-to-pixel pipeline.

**Branch**: `exp_agent_dirty_readiness_stop` (based on AOSP `25Q3-release`, Android 16)

This branch adds the production dirty/readiness return policy on top of the
DisplayList export, on-demand traversal, foreground checkpoint, and traversal-only
freeze branches. Its observation contract is deliberately narrow: after an Agent
action has been injected, return the first complete active DisplayList for which
the current UI pipeline is quiet. It does not wait for unrelated future network or
timer updates to make the page permanently identical.

## Architecture

Three components spanning the windowing system and rendering pipeline:

```
┌─────────────────────────────────────────────────────────┐
│ C1: Isolated Agent Task Execution (services/wm)         │
│   ActivityStarter override → same-process dual-instance  │
│   on isolated Virtual Display                            │
├─────────────────────────────────────────────────────────┤
│ C2: DisplayList as UI Representation (hwui)              │
│   RenderNode tree → JSON export via dumpsys gfxinfo      │
├─────────────────────────────────────────────────────────┤
│ C3: On-Demand Perception, GPU Bypassed (view/hwui)       │
│   Dual-phase traversal: settle (measure+layout only)     │
│   → single record pass → zero GPU via mSyncOnlyFrame    │
│   Per-window freeze + stability detection + profiling    │
└─────────────────────────────────────────────────────────┘
```

## Modified Files

| File | Purpose |
|------|---------|
| `core/java/android/view/ViewRootImpl.java` | Freeze, dual-phase traversal, dirty/readiness observation return, profiling counters; **persistent GPU bypass (`mAgentPersistentNoDrawOverride` tri-state + process-wide `sAgentPersistentNoDrawDefault`, first-frame `mAgentHasDrawnOnce` bootstrap)** |
| `core/java/android/view/Choreographer.java` | `doFrameForAgent()` synthetic VSYNC pump |
| `core/java/android/view/ThreadedRenderer.java` | `syncForAgent()` record+sync without GPU draw |
| `core/java/android/view/WindowManagerGlobal.java` | Parse fresh/nodraw/vsyncN/freeze/traversalfreeze/readysettle/readywaitN and profiling controls; inject agent stats into DL output |
| `graphics/java/android/graphics/HardwareRenderer.java` | `setSyncOnlyNextFrame()` public hidden API |
| `libs/hwui/jni/android_graphics_HardwareRenderer.cpp` | JNI bridge for setSyncOnlyNextFrame |
| `libs/hwui/renderthread/DrawFrameTask.{h,cpp}` | `mSyncOnlyFrame` skips GPU draw after syncFrameState |
| `libs/hwui/renderthread/RenderProxy.{h,cpp}` | setSyncOnlyNextFrame passthrough |
| `libs/hwui/pipeline/skia/FlatExportOpsCanvas.h` | C2 export canvas: maps all op bounds to device (screen) space via `getLocalToDeviceAs3x3()`; emits per-run `{font, glyphs}` for external reverse-cmap |
| `services/core/.../wm/ActivityStarter.java` | C1: detect agent display, override to LAUNCH_MULTIPLE |
| `services/core/.../wm/ActivityTaskManagerService.java` | `isAgentDisplay()` / mAgentDisplayIds registry |
| `tools/dlglyph/dl_glyph_decode.py` | Host-side post-processor: reverses glyph IDs back to UTF-8 via fontTools |
| *(frameworks/native)* `services/surfaceflinger/SurfaceFlinger.{cpp,h}` | **L3 SF compose-bypass: backdoor code 1050 skips compositing an agent VD by layerStack — cuts SF composition GPU + back-pressures video producers without touching window/display/input state (separate repo)** |

## Quick Start: Build & Deploy

```bash
# Prerequisites: AOSP source tree with this branch checked out at frameworks/base
cd <AOSP_ROOT>
source build/envsetup.sh
lunch gsi_arm64-trunk_staging-userdebug  # or your target

# Build (choose based on what you modified):
m framework-minus-apex -j$(nproc)  # core/java + graphics/java changes
m libhwui -j$(nproc)               # libs/hwui C++ changes
m services -j$(nproc)              # services/wm C1 changes

# Deploy to device (must be rooted + remounted):
SERIAL=<your_device>
adb -s $SERIAL root && adb -s $SERIAL remount

OUT=out/target/product/<target>/system/framework
adb -s $SERIAL push $OUT/framework.jar /system/framework/
adb -s $SERIAL push $OUT/services.jar /system/framework/
adb -s $SERIAL push out/target/product/<target>/system/lib64/libhwui.so /system/lib64/

# Clear dalvik cache + soft reboot (NEVER hard reboot - overlayfs will lose changes)
adb -s $SERIAL shell "rm -rf /data/dalvik-cache/*/system@framework@*"
adb -s $SERIAL shell stop && sleep 3 && adb -s $SERIAL shell start
```

## API Reference

All interaction through `dumpsys gfxinfo <pkg> displaylist [subparams]`:

| Subparam | Effect |
|----------|--------|
| `fresh` | Force on-demand traversal (dual-phase) |
| `nodraw` | Skip GPU draw (sync-only, zero GPU/SF) |
| `vsyncN` | Pump up to N synthetic VSYNC frames in Phase 1 (default 3 if no number) |
| `freeze` | Freeze agent window (stop real-VSYNC self-rendering) |
| `unfreeze` | Resume normal rendering |
| `traversalfreeze` | Stop Agent traversal scheduling while preserving the current active DL and renderer content. Use this between Agent steps. |
| `traversalunfreeze` | Resume Agent traversal scheduling without reconstructing renderer content. |
| `asyncsettle` | Run settle passes as posted foreground-yielding continuations instead of one synchronous loop. |
| `readysettle` | Start dirty/readiness settle on the visible Agent root, publish one final active DL, and traversal-freeze atomically. Implies `fresh asyncsettle`. |
| `readywaitN` | Set the `readysettle` wall-clock fallback to N milliseconds. This is an upper bound, not a minimum sleep. Default: 2000. |
| `agentstate` | Return compact per-root control/readiness JSON without exporting renderer data. |
| `gpubypass` | Persistent no-draw: EVERY traversal (incl. the app's own real-VSYNC frames while unfrozen) skips the GPU draw. Process-wide default, inherited by newly-navigated agent windows automatically. Agent-UI only. Independent of freeze. |
| `nogpubypass` | Disable persistent no-draw (resume real drawing) |
| `reset` | Reset per-window profiling counters |

### Output Format

Per-window header with agent stats followed by DL JSON:
```
<pkg/activity/VRI@hash> (visibility=N){"settled":B,"phase1_traversals":P1,"phase2_traversals":P2}{"frame":"flat_render","width":W,"height":H,"nodes":[...]}
```

- `settled=true`: Phase 1 exited via early-break (view tree stable)
- `settled=false`: Phase 1 exhausted vsyncN budget (perpetual animation present)
- `phase1_traversals`: cumulative measure+layout-only passes since last reset
- `phase2_traversals`: cumulative full-record passes since last reset
- `observe_result=readiness`: dirty/readiness ended the observation before the fallback
- `observe_result=timeout`: `readywaitN` fired and the fallback snapshot was published
- `observe_result=sync_failed|detached`: no consumable snapshot; the caller must retry or use pixel fallback
- `observe_duration_ms` / `observe_passes`: device-side return latency and settle passes
- `last_sync_valid=true,last_sync_result=0`: the final root DL is valid and crossed the
  RenderThread staging-to-active sync boundary

When a package has both physical-display and Agent-display roots, match the root whose
stats contain `"agent_ui":true`, the expected `"display":<VD_ID>`, and
`visibility=0`. Do not select a window by package name or list order alone.

Each op's `bounds`/`dst` is in **device (screen) coordinates** — the export
canvas maps every local-space input through `SkCanvas::getLocalToDeviceAs3x3()`
at the time of capture, so rectangles can be used directly as tap targets
without further math.

`drawText` ops additionally carry the SkTextBlob's raw runs:

```json
{"op":"drawText","x":221,"y":309,"bounds":[224,322,565,355],"size":33,
 "color":"#ff30323b",
 "runs":[{"font":"Roboto","glyphs":[51,74,89,92,84,87,80,5,11,5,78,83,89,74,87,83,74,89]}]}
```

The framework intentionally does NOT do glyph→UTF-8 reverse-cmap inline. That
step is a pure font-table lookup; doing it in C++ would require shipping
GSUB/cmap logic and font fallback into HWUI for no benefit. Instead, the host
script `tools/dlglyph/dl_glyph_decode.py` performs the reverse with `fontTools`
(see *DisplayList Usability* section below).

## Usage Examples

### Basic: Get fresh DL with GPU bypassed
```bash
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw"
```

### With stability detection (wait up to 2s = 120 frames @60Hz)
```bash
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw vsync120"
```

### Recommended workflow: action to readiness-aligned observation
```bash
# 1. Start user instance on display 0
adb shell am start --display 0 -n com.example.app/.MainActivity

# 2. Create agent Virtual Display and launch agent instance
app_process -cp /data/local/tmp/vd_agent.dex / \
  com.agent.VirtualDisplayAgent offdump com.example.app/.MainActivity 600 iso

# 3. Get agent display ID from log
DID=$(cat /data/local/tmp/vd_off.log | grep -oE "READY displayId=[0-9]+" | grep -oE "[0-9]+")

# 4. Let the new Agent window complete its first real draw, then preserve that
#    active DL while stopping only traversal scheduling between steps.
adb shell "dumpsys gfxinfo com.example.app displaylist agentctl traversalfreeze"

# 5. Inject one complete action on the Agent display. The command must return
#    before starting observation; DOWN-only injection is not an action boundary.
adb shell input -d $DID tap 540 800

# 6. Immediately request the post-action observation. Dirty/readiness may return
#    early; 2000 ms is the former fixed wait retained only as a fallback bound.
adb exec-out "dumpsys gfxinfo com.example.app displaylist fresh nodraw \
  asyncsettle readysettle readywait2000" > /tmp/post_action_dl.txt

# 7. Verify the selected Agent root before giving the DL to the next Agent step.
adb shell "dumpsys gfxinfo com.example.app displaylist agentstate"
# Accept: observe_result is readiness or timeout, frozen=true,
#         last_sync_valid=true, last_sync_result=0, observe_active=false.
# Reject: sync_failed, detached, wrong display/root, empty DL, ANR/login/ad UI.
```

`readysettle` unfreezes the visible Agent root internally. After a stable candidate,
it performs one complete Java DL record and synchronous RenderThread handoff, checks
that recording did not schedule more UI work, and then traversal-freezes before the
dumpsys output is generated. If readiness never appears, the same final record/sync
runs at `readywaitN`. The command therefore replaces both the old unconditional
post-action sleep and the additional sleep used to wait for an unfrozen traversal.

## Caller Alignment

The readiness request must be aligned to an Agent action, not to inference time or a
host polling interval:

| Old caller behavior | This branch |
|---|---|
| `sleep(wait_after_action_seconds)` | Remove the sleep; call `readysettle` immediately after action injection returns |
| Unfreeze, then `sleep(300 ms)` before dumping DL | Remove both; `readysettle` owns unfreeze, settle, final sync, and refreeze |
| `wait_after_action_seconds=2.0` | `readywait2000`; preserve the value as fallback only |
| `freeze` between every step | `traversalfreeze`; avoid per-step `clearContent()` and DL reconstruction |
| Assume the first package window is the Agent UI | Select `agent_ui=true`, matching VD display ID, `visibility=0` |
| Treat every dumpsys return as valid | Gate on result, sync validity, root/display, non-empty DL, and blocking UI |

For this workspace, the aligned commercial caller is
`agent_os/experiments/exp_commercial_three_configs_profiled/code/m3a_dl_agent.py`.
It records before/after `observe_result`, `observe_duration_ms`, `observe_passes`, and
Phase-1/2 counters in each `step.json`. The device-side smoke entry is:

```bash
cd /home/lishengkai/scripts/agent_os/experiments
python3 exp_displaylist_ondemand/smoke_ready_settle.py \
  --serial <serial> --adb-port <port> --package <pkg> --display-id <VD_ID> \
  --action swipe_up --action tap:540:1200 --ready-wait-ms 2000
```

The smoke tool assumes the Agent VD and target app are already running. It does not
create, reassign, or destroy displays, which makes it safe to hand to a tester after
the device owner has confirmed the display and package.

### Profiling: measure traversal counts and GPU frames
```bash
# Reset counters at task start
adb shell "dumpsys gfxinfo com.example.app displaylist reset"

# ... run agent task (multiple actions + observations) ...

# Check cumulative stats
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw" | \
  grep -oP '(?<=visibility=\d\))\{[^{]*\}'
# Output: {"settled":true,"phase1_traversals":5,"phase2_traversals":3}

# Compare with GPU frames (should be 0 for agent window while frozen)
adb shell "dumpsys gfxinfo com.example.app" | grep "Total frames rendered"
```

## DisplayList Usability

This section quantifies what the DL representation actually delivers as an
agent-side input, relative to screenshots and the AccessibilityService XML.

### What the export captures

Each `drawText`/`drawRect`/`drawPath`/`drawImage`/... op in the JSON carries:
- **Device-space bounds** — directly usable as tap targets, no matrix math
  on the consumer side
- **Color** (ARGB hex), **font size**, **stroke style** where applicable
- For text: **font family name** + **raw glyph ID array** per Skia run
- For nested RenderNodes: hierarchical `drawDrawable.ops[...]` blocks that
  preserve the rendering tree (incl. HardwareLayers)

Three things the export does NOT carry, by design:
1. **Image pixel content** — `drawImage` emits dst rect + source w×h only.
   This is the only inherent limit of the DisplayList channel; agents that
   need image semantics still need a vision model for that subregion.
2. **View identity / clickable flags** — the op stream is a rendering view;
   `View.isClickable()` is application semantics and belongs in a thin
   side-channel (~50 bytes per interactive View), not in the op stream itself.
3. **UTF-8 text** — only glyph IDs are emitted (Skia loses the source text
   once it shapes into a TextBlob). Recovered by the host-side script below.

### Glyph → UTF-8 recovery (deterministic, model-free)

`tools/dlglyph/dl_glyph_decode.py` walks each `runs[]` entry and reverse-maps
glyph IDs to Unicode codepoints via `fontTools.ttLib`. For TrueType
Collections (Noto CJK ships as a TTC of 5 region variants), the cmaps of all
sub-fonts are unioned under the same family name.

```bash
# One-time: pull device fonts
adb -s <serial> pull /system/fonts/Roboto-Regular.ttf       ~/fonts/
adb -s <serial> pull /system/fonts/NotoSansCJK-Regular.ttc  ~/fonts/

# Optional: pull app-private fonts (rarely needed)
adb -s <serial> shell pm path <pkg> | sed 's/^package://' | \
  xargs -I {} adb -s <serial> pull {} /tmp/app.apk
unzip -j /tmp/app.apk "assets/*.ttf" "assets/*.otf" -d ~/fonts/

# Capture + decode
adb -s <serial> exec-out "dumpsys gfxinfo <pkg> displaylist fresh nodraw" \
  > /tmp/dl.json
tools/dlglyph/dl_glyph_decode.py --fonts ~/fonts /tmp/dl.json \
  -o /tmp/dl_decoded.json
```

The decoded JSON has the same structure as the raw output, with each
`drawText` op gaining a `text` field:

```json
{"op":"drawText","bounds":[224,322,565,355],"size":33,
 "color":"#ff30323b","text":"Network & internet", ...}
```

### Empirical recovery rate

Measured on Settings home, Zhihu home, Bilibili home (Android 16 GSI, Pixel 10
Pro) — every `drawText` op extracted, every glyph in every run reverse-mapped:

| App      | Total glyphs | Recovered | Rate    |
|----------|-------------:|----------:|--------:|
| Settings |          735 |       735 | 100.0%  |
| Zhihu    |          990 |       990 | 100.0%  |
| Bilibili |          288 |       287 |  99.7%  |

By font:

| Font                    | Glyphs | Recovered | Rate    |
|-------------------------|-------:|----------:|--------:|
| Roboto (Latin/digits)   |  1,519 |     1,519 | 100.0%  |
| Noto Sans CJK SC        |    493 |       493 | 100.0%  |
| App-private iconfont    |      1 |         0 |   0.0%  |

The single missed glyph on Bilibili comes from an in-app font name reported
by `SkTypeface::getFamilyName()` as `"bilibili"`. The corresponding glyph is
not present in any of the system fonts; if the app's `assets/*.ttf` is
unpacked and supplied via `--fonts`, that gap closes too.

### Known limitations (and what each costs)

| Source of loss | Effect | Severity for Chinese/English UI |
|----------------|--------|---------------------------------|
| App-private fonts not unpacked from APK | Glyphs in that font reverse to `�` | 0.05% on the test set; fix is one-time per app |
| GSUB ligatures (Latin `fi`, `ffi`) | Reverse yields one codepoint instead of two | Negligible — apps rarely use Latin ligatures |
| Complex shaping (Arabic, Devanagari) | Glyph order ≠ logical char order; positional variants need GSUB reverse | Out of scope for our target apps |
| Unicode variant codepoints (e.g. Kangxi radical "⻔" vs "门") | Decoded char is visually identical but has a different codepoint | Visual equivalence preserved; downstream string match needs normalization |
| Image content (`drawImage`) | Only dst-rect + w/h, no pixels | Inherent to DL channel; out of scope |

### What this means relative to existing channels

- **vs Screenshot**: the DL op stream carries equivalent visual information
  (modulo image internals) in structured form — a button rendered as
  drawRRect-background + centered drawText is recognizable from the op
  attributes the same way a VLM recognizes it from pixels, but at a fraction
  of the token cost.
- **vs AccessibilityService**: DL captures every glyph that is drawn,
  including text rendered by custom `canvas.drawText` calls that the a11y
  layer cannot see. Conversely, DL does not carry `clickable`/`focusable`
  flags — those come from `View` state and require a thin side-channel.
- **Limits that remain after this work**: only image pixel content (the one
  inherent limit) and the optional `{id, clickable, role}` side-channel
  (~50 bytes/View) the agent needs to disambiguate semantically-clickable-
  but-visually-passive elements.

## Experiment Notes

1. **Never `adb reboot`** — overlayfs is tmpfs, hard reboot loses all pushed changes.
   Always use `adb shell stop && start` for soft reboot.

2. **Never `m framework`** — that builds the class jar, not the DEX jar.
   Always use `m framework-minus-apex`.

3. **C1 verification**: check logcat for `AgentDisplay: Overriding to LAUNCH_MULTIPLE`
   after launching on agent display.

4. **GPU isolation verification**: after traversal-freeze, `Total frames rendered` should not
   increase for the agent window. Small increments (1-4) may come from system UI on
   display 0, not from the agent window.

5. **`uiautomator dump --display N`** is unreliable in multi-display setups — always
   use `dumpsys gfxinfo ... displaylist` + `screencap` for cross-validation.

6. **Display ID is dynamic** — each `vd_agent` launch gets a new ID. Always read it
   from the vd_agent log, never hardcode.

7. **Readiness fallback selection**:
   - Start validation with `readywait2000` to align with the previous commercial
     runner's `wait_after_action_seconds=2.0`.
   - Keep `readywaitN` constant across compared configurations; report readiness and
     timeout samples separately.
   - `readywaitN` does not certify future network completion. It bounds when the
     current action observation is delivered.
   - `vsyncN` remains a legacy pass-budget interface for synchronous experiments; do
     not mix it with production readiness latency numbers.

## Commit History

Current branch `exp_agent_dirty_readiness_stop` (newest first):
```
6b300f7a Agent UI: stop observations on cheap readiness
de2203f8 Agent UI: refine cheap readiness profiling
654664c6 Agent UI: profile DisplayList readiness generations
daa61196 Agent UI: add traversal-only freeze controls
fa317639 Agent UI: checkpoint on-demand traversals
2eef669a Agent UI: dispatch foreground frames at node checkpoints
8e6fb21d Agent C3 L2: persistent GPU bypass as an auto agent-UI window property
6ed448b  dlglyph: multi-candidate font matching + CJK word-freq disambiguation
1bb67aae Agent C3: release agent window GPU resources on freeze (agent-UI-only)
1005f63e Agent C3 Layer-1: idle-driven decouple traversal + anti-starvation bound
e4b6f442 add bounds and drawimage/drawpath functions (DL visual completion)
a90aa32d Agent C3 Layer-1: make decouple an automatic window property of agent UIs
cb00a319 Agent C3: three-layer decoupled architecture (callback reroute / GPU bypass / DL semantic-hash stability)
42f0934d Agent on-demand: add display id + frozen flag to stats JSON
d58973d5 Agent on-demand: switch settle to posted self-continuation
c8fcd683 Agent on-demand: idle-driven foreground-yielding settle + animation attribution
831e8169 DisplayList Export: device-space op bounds + per-run glyph IDs + reverse-cmap script
```

### Three-layer GPU-bypass design (current)

The agent UI's GPU cost is cut by three orthogonal, independently-switchable,
agent-UI-only layers. None touches window visibility / display power / input /
activity lifecycle, so the agent stays perceivable (DisplayList) and operable (tap).
The table separately lists the long-idle resource-release variant of L1 because it
must not be substituted for the per-step scheduling freeze:

| Layer | Cuts | Mechanism | Switch |
|-------|------|-----------|--------|
| **L1 scheduling freeze** | no Agent traversal between steps while retaining the active DL | `setAgentTraversalFrozen`: `scheduleTraversals` early-return, no `clearContent()` | `displaylist traversalfreeze` / `traversalunfreeze`; `readysettle` refreezes automatically |
| **Long-idle resource freeze** | additionally releases this window's GPU resources when reconstruction cost is acceptable | `setAgentFrozen`: scheduling gate + `clearContent()` | `displaylist freeze` / `unfreeze`; do not use per Agent step |
| **L2 persistent no-draw** | EVERY traversal skips `context->draw()` (rasterization + SF submit), incl. the app's own VSYNC frames — not just the observe frame | `mAgentPersistentNoDraw*`: `performDraw` routes to `syncForAgent` (record DL + RT sync, skip draw). Process-wide default so navigated sub-windows inherit; first real frame builds the DisplayList, then bypass | `displaylist gpubypass` / `nogpubypass` |
| **L3 SF compose-bypass** | SF's composition pass for the agent VD + video/SurfaceView producer back-pressure | *(frameworks/native)* SurfaceFlinger backdoor code 1050 skips the agent VD's Output by layerStack | `service call SurfaceFlinger 1050 i32 <displayId> i32 <1/0>` |

Measured (2-round avg, c3_dl_nofg_compose vs bgui_nofg baseline): target-app GPU
compute **−94.6%**, SF composition GPU **−95.1%**, rendered frames ≈ **−99%**
(agent VD renders single-digit frames vs thousands). CPU is roughly flat per unit
time (DL export + agent scheduling replace the rasterization CPU). WebView pages
(Firefox: GPU in a separate `:gpu` process) are an intrinsic boundary the render-
pipeline bypass cannot reach.

## License

Same as AOSP — Apache 2.0.

## C2 DisplayList Export 修改说明

本分支在 Android `frameworks/base` 中加入了 C2 使用的 DisplayList 导出能力。该修改的目标是从 Android RenderThread / HWUI 渲染流程中导出当前窗口的绘制指令，并将其转换为结构化 JSON，供上层 agent 使用。

### 修改目标

原始 Android framework 只能通过截图或 ViewTree 获取界面信息。为了让 agent 能够直接访问 UI 的底层绘制结构，本修改在 HWUI 中加入了 DisplayList 导出逻辑，使系统可以输出如下信息：

- 当前窗口的绘制操作，例如 `drawText`、`drawPath`、`drawImage`、`drawRect` 等；
- 文本内容、绘制 bounds、颜色、图片采样信息；
- 图标和 path 的几何形状；
- 元素在全局屏幕坐标系中的位置；
- 当前窗口的 surface origin 和 surface insets 信息。

这些信息会被后续 Python 侧脚本进一步过滤、简化，并转换为 agent observation。

### 核心修改

本次修改涉及 Java framework、JNI、RenderProxy、CanvasContext 和 HWUI Skia pipeline。主要修改文件如下：

```text
core/java/android/view/ThreadedRenderer.java
core/java/android/view/WindowManagerGlobal.java
graphics/java/android/graphics/HardwareRenderer.java
libs/hwui/jni/android_graphics_HardwareRenderer.cpp
libs/hwui/pipeline/skia/FlatExportOpsCanvas.h
libs/hwui/renderthread/CanvasContext.cpp
libs/hwui/renderthread/CanvasContext.h
libs/hwui/renderthread/RenderProxy.cpp
libs/hwui/renderthread/RenderProxy.h
```

### FlatExportOpsCanvas

`FlatExportOpsCanvas` 是本修改的核心组件。它继承自 `SkCanvas`，在 DisplayList replay 过程中拦截绘制操作，并导出 JSON 格式的绘制记录。

目前支持导出的主要绘制操作包括：

- `drawTextBlob`
- `drawPath`
- `drawImage`
- `drawRect`
- `drawOval`
- `drawRRect`
- `drawCircle`
- `clipRect`

对于不同操作，会导出对应的结构化信息，例如：

- `op`
- `bounds`
- `color`
- `text`
- `path`
- `src`
- `dst`
- `imageId`
- `visualStatus`
- `sampleWidth`
- `sampleHeight`
- `argbRle`

其中 `drawImage` 会尝试导出小尺寸 ARGB 采样，并使用 run-length encoding 表示图片像素信息，供后续脚本生成图标语义描述。
