/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <cstdint>

#include <SkCanvas.h>
#include <SkTextBlob.h>
#include <SkRRect.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * SemanticHashCanvas computes a cheap 64-bit fingerprint of the
 * agent-consumable semantic content of a frame, for Layer-3 UI stability
 * detection (C3). It walks the RenderNode tree exactly like
 * FlatExportOpsCanvas, but instead of emitting JSON it folds only the fields
 * an agent's decision depends on into an FNV-1a hash:
 *
 *   - drawText: each run's glyph IDs (the text content) + the device-space
 *     bounds (where the text sits, tap-ready) + integer baseline.
 *   - interactive/structural geometry: device-space bounds of rect/rrect ops
 *     (button/row backgrounds) folded at low weight so layout changes register.
 *
 * Deliberately EXCLUDED (so the hash is stable under cosmetic churn):
 *   - colors, gradients, shaders, alpha
 *   - image pixel content (only nothing — images excluded entirely)
 *   - sub-pixel jitter (bounds are quantized to integer device pixels)
 *   - paint style / stroke width
 *
 * Rationale: two consecutive on-demand passes with the same text + layout
 * produce the same hash even while a spinner animates or a gradient pulses
 * (those don't change glyphs or integer bounds), so the detector reports
 * "agent-stable". When real content arrives (new text / moved rows), the hash
 * changes and the detector keeps settling. Cost is O(ops) with no allocation
 * and no string building — strictly cheaper than the JSON export path.
 */
class SemanticHashCanvas : public SkCanvas {
public:
    SemanticHashCanvas(int width, int height)
            : SkCanvas(width, height), mHash(kFnvOffset) {}

    uint64_t hash() const { return mHash; }

protected:
    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint&) override {
        if (!blob) return;
        // Device-space baseline + bounds (quantized).
        SkScalar bx = x, by = y;
        pointToDevice(&bx, &by);
        foldInt((int32_t)bx);
        foldInt((int32_t)by);
        foldDeviceRect(blob->bounds().makeOffset(x, y));
        // Glyph IDs per run = the text content.
        SkTextBlob::Iter iter(*blob);
        SkTextBlob::Iter::Run run;
        while (iter.next(&run)) {
            for (int i = 0; i < run.fGlyphCount; ++i) {
                foldInt((int32_t)(uint16_t)run.fGlyphIndices[i]);
            }
        }
    }

    // Structural geometry: fold device bounds so row/button layout changes are
    // detected, but at coarse granularity (integer device px) to ignore jitter.
    void onDrawRect(const SkRect& rect, const SkPaint&) override {
        foldTag(0x52); // 'R'
        foldDeviceRect(rect);
    }
    void onDrawRRect(const SkRRect& rrect, const SkPaint&) override {
        foldTag(0x72); // 'r'
        foldDeviceRect(rrect.rect());
    }

    // Recurse into nested RenderNodes (HardwareLayers / child nodes), matching
    // FlatExportOpsCanvas so the hash covers the whole tree.
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        if (!drawable) return;
        if (matrix) {
            this->save();
            this->concat(*matrix);
            drawable->draw(this);
            this->restore();
        } else {
            drawable->draw(this);
        }
    }

    // Everything else (paint, image, path, points, color ops) is intentionally
    // ignored — not part of the agent-stable signal.

private:
    static constexpr uint64_t kFnvOffset = 1469598103934665603ULL;
    static constexpr uint64_t kFnvPrime = 1099511628211ULL;

    void foldByte(uint8_t b) {
        mHash ^= b;
        mHash *= kFnvPrime;
    }
    void foldInt(int32_t v) {
        foldByte((uint8_t)(v & 0xFF));
        foldByte((uint8_t)((v >> 8) & 0xFF));
        foldByte((uint8_t)((v >> 16) & 0xFF));
        foldByte((uint8_t)((v >> 24) & 0xFF));
    }
    void foldTag(uint8_t t) { foldByte(t); }

    void foldDeviceRect(const SkRect& localRect) {
        SkRect d;
        this->getLocalToDeviceAs3x3().mapRect(&d, localRect);
        foldInt((int32_t)d.fLeft);
        foldInt((int32_t)d.fTop);
        foldInt((int32_t)d.fRight);
        foldInt((int32_t)d.fBottom);
    }

    void pointToDevice(SkScalar* x, SkScalar* y) {
        SkPoint p = {*x, *y};
        this->getLocalToDeviceAs3x3().mapPoints(&p, &p, 1);
        *x = p.fX;
        *y = p.fY;
    }

    uint64_t mHash;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
