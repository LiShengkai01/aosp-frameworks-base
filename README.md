# MobileAgentService — AOSP Framework Modifications

Platform-layer mechanisms for Android that enable GUI agents to operate applications
efficiently and concurrently with users—without occupying the screen, competing for
GPU resources, or relying on the screenshot-to-pixel pipeline.

**Branch**: `exp_displaylist_export` (based on AOSP `25Q3-release`, Android 16)

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
| `core/java/android/view/ViewRootImpl.java` | Freeze, dual-phase forceTraversalForAgent, stability detection (mAgentDirtyTick), profiling counters |
| `core/java/android/view/Choreographer.java` | `doFrameForAgent()` synthetic VSYNC pump |
| `core/java/android/view/ThreadedRenderer.java` | `syncForAgent()` record+sync without GPU draw |
| `core/java/android/view/WindowManagerGlobal.java` | Parse fresh/nodraw/vsyncN/freeze/unfreeze/reset subparams; inject agent stats to DL output |
| `graphics/java/android/graphics/HardwareRenderer.java` | `setSyncOnlyNextFrame()` public hidden API |
| `libs/hwui/jni/android_graphics_HardwareRenderer.cpp` | JNI bridge for setSyncOnlyNextFrame |
| `libs/hwui/renderthread/DrawFrameTask.{h,cpp}` | `mSyncOnlyFrame` skips GPU draw after syncFrameState |
| `libs/hwui/renderthread/RenderProxy.{h,cpp}` | setSyncOnlyNextFrame passthrough |
| `services/core/.../wm/ActivityStarter.java` | C1: detect agent display, override to LAUNCH_MULTIPLE |
| `services/core/.../wm/ActivityTaskManagerService.java` | `isAgentDisplay()` / mAgentDisplayIds registry |

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

## Usage Examples

### Basic: Get fresh DL with GPU bypassed
```bash
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw"
```

### With stability detection (wait up to 2s = 120 frames @60Hz)
```bash
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw vsync120"
```

### Full workflow: C1 dual-instance + C3 on-demand perception
```bash
# 1. Start user instance on display 0
adb shell am start --display 0 -n com.example.app/.MainActivity

# 2. Create agent Virtual Display and launch agent instance
app_process -cp /data/local/tmp/vd_agent.dex / \
  com.agent.VirtualDisplayAgent offdump com.example.app/.MainActivity 600 iso

# 3. Get agent display ID from log
DID=$(cat /data/local/tmp/vd_off.log | grep -oE "READY displayId=[0-9]+" | grep -oE "[0-9]+")

# 4. Freeze agent window (zero GPU at rest)
adb shell "dumpsys gfxinfo com.example.app displaylist freeze"

# 5. Inject action on agent display
adb shell input -d $DID tap 540 800

# 6. Get post-action DL (on-demand, GPU bypassed, stability detection)
adb shell "dumpsys gfxinfo com.example.app displaylist fresh nodraw vsync120"

# 7. Reset profiling counters for next measurement
adb shell "dumpsys gfxinfo com.example.app displaylist reset"
```

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

## Experiment Notes

1. **Never `adb reboot`** — overlayfs is tmpfs, hard reboot loses all pushed changes.
   Always use `adb shell stop && start` for soft reboot.

2. **Never `m framework`** — that builds the class jar, not the DEX jar.
   Always use `m framework-minus-apex`.

3. **C1 verification**: check logcat for `AgentDisplay: Overriding to LAUNCH_MULTIPLE`
   after launching on agent display.

4. **GPU isolation verification**: after freeze, `Total frames rendered` should not
   increase for the agent window. Small increments (1-4) may come from system UI on
   display 0, not from the agent window.

5. **`uiautomator dump --display N`** is unreliable in multi-display setups — always
   use `dumpsys gfxinfo ... displaylist` + `screencap` for cross-validation.

6. **Display ID is dynamic** — each `vd_agent` launch gets a new ID. Always read it
   from the vd_agent log, never hardcode.

7. **vsync budget selection**:
   - Static UI (settings, forms): `vsync8` sufficient (settles in 1-3 frames)
   - Animated content (feed, transitions): `vsync120` (2s budget)
   - `settled=false` with perpetual animations is expected and correct

## Commit History

```
cc6eead  Agent Rendering: on-demand DL, GPU bypass, stability detection, profiling
bbb8b05  DisplayList Export: dump RenderNode tree as JSON for agent UI perception
fc63e2b  Agent Display: approach B - override launchMode in setInitialState
c9fe912  Agent Display: approach A - exempt singleTask reuse in resolveReusableTask
```

## License

Same as AOSP — Apache 2.0.
