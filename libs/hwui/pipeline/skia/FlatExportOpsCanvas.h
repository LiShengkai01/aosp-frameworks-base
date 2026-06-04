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

#include <sstream>
#include <iomanip>
#include <vector>
#include <string>

#include <SkCanvas.h>
#include <SkRRect.h>
#include <SkTextBlob.h>
#include <SkFont.h>
#include <SkPath.h>
#include <SkRegion.h>
#include <SkPicture.h>
#include <SkString.h>
#include <SkTypeface.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * FlatExportOpsCanvas captures a fully-rendered frame as a hierarchical JSON
 * stream. It's designed to be the target SkCanvas for RenderNodeDrawable.draw(),
 * which traverses the entire RenderNode tree (including nested HardwareLayers)
 * just like GPU rendering does. By default, SkCanvas's onDrawDrawable() inlines
 * a drawable's content via drawable->draw(this), so nested RenderNodes are
 * captured recursively.
 *
 * All bounds and points emitted are in DEVICE (screen) space:
 * every onDrawXxx callback maps its local-space inputs through the current
 * local-to-device matrix maintained by SkCanvas (which is updated by the
 * save()/concat() calls RenderNodeDrawable issues as it walks the tree).
 *
 * For drawText, in addition to the device-space bounds/baseline, each run of
 * the SkTextBlob is exported with its typeface family name and raw glyph IDs:
 * an external Python script using fontTools can reverse-map glyph IDs to UTF-8
 * by walking the font's cmap. The reverse cmap is intentionally NOT done in
 * the framework — it is a pure data transformation that does not benefit from
 * being inside HWUI.
 *
 * Output format:
 *   {"op":"clipRect","bounds":[...]},
 *   {"op":"drawText","x":..,"y":..,"bounds":[...],"size":..,"color":"..",
 *     "runs":[{"font":"<family>","glyphs":[g1,g2,...]}, ...]},
 *   {"op":"drawDrawable","ops":[                  <- nested RenderNode
 *     {"op":"drawRect",...},
 *     {"op":"drawDrawable","ops":[...]},
 *     ...
 *   ]},
 *   ...
 */
class FlatExportOpsCanvas : public SkCanvas {
public:
    FlatExportOpsCanvas(std::ostream& output, int width, int height)
            : SkCanvas(width, height), mOutput(output), mLevel(0), mFirstStack({true}) {}

protected:
    // ── State ─────────────────────────────────────────────────────────
    void onClipRect(const SkRect& rect, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRect\",\"bounds\":" << rectJsonDevice(rect) << "}";
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRRect\",\"bounds\":" << rectJsonDevice(rrect.rect())
                << ",\"rx\":" << rrect.getSimpleRadii().x()
                << ",\"ry\":" << rrect.getSimpleRadii().y() << "}";
    }

    void onClipPath(const SkPath& path, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipPath\",\"bounds\":" << rectJsonDevice(path.getBounds())
                << "}";
    }

    void onClipRegion(const SkRegion&, SkClipOp) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRegion\"}";
    }

    void onResetClip() override {
        sep();
        mOutput << indent() << "{\"op\":\"resetClip\"}";
    }

    // ── Draws ─────────────────────────────────────────────────────────
    void onDrawPaint(const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPaint\",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRect\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRRect\",\"bounds\":" << rectJsonDevice(rrect.rect())
                << ",\"rx\":" << rrect.getSimpleRadii().x()
                << ",\"ry\":" << rrect.getSimpleRadii().y()
                << ",\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawDRRect(const SkRRect& outer, const SkRRect& inner,
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawDRRect\",\"outer\":" << rectJsonDevice(outer.rect())
                << ",\"inner\":" << rectJsonDevice(inner.rect())
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawOval\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle,
                   bool, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawArc\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"start\":" << startAngle << ",\"sweep\":" << sweepAngle
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPath\",\"bounds\":" << rectJsonDevice(path.getBounds())
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint& paint) override {
        sep();
        // Bounds and baseline in DEVICE (screen) space.
        SkRect localBounds = blob ? blob->bounds().makeOffset(x, y) : SkRect::MakeEmpty();
        SkScalar bx = x, by = y;
        pointToDevice(&bx, &by);
        mOutput << indent() << "{\"op\":\"drawText\""
                << ",\"x\":" << bx << ",\"y\":" << by
                << ",\"bounds\":" << rectJsonDevice(localBounds)
                << ",\"size\":" << localBounds.height()
                << ",\"color\":\"" << colorHex(paint) << "\"";

        // Emit raw glyph IDs per run for external Python reverse-cmap.
        // Skia's SkTextBlob::Iter exposes typeface + glyph array per run; the
        // run does not carry UTF-8 once shaped, hence the side-channel reverse
        // map. Family name + glyph IDs is the minimal set required.
        if (blob) {
            mOutput << ",\"runs\":[";
            bool firstRun = true;
            SkTextBlob::Iter iter(*blob);
            SkTextBlob::Iter::Run run;
            while (iter.next(&run)) {
                if (!firstRun) mOutput << ",";
                firstRun = false;
                SkString family;
                if (run.fTypeface) {
                    run.fTypeface->getFamilyName(&family);
                }
                mOutput << "{\"font\":\"";
                writeEscaped(family.c_str());
                mOutput << "\",\"glyphs\":[";
                for (int i = 0; i < run.fGlyphCount; ++i) {
                    if (i) mOutput << ",";
                    mOutput << (unsigned int)run.fGlyphIndices[i];
                }
                mOutput << "]}";
            }
            mOutput << "]";
        }
        mOutput << "}";
    }

    void onDrawImageRect2(const SkImage* image, const SkRect& src, const SkRect& dst,
                          const SkSamplingOptions&, const SkPaint*,
                          SrcRectConstraint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImage\",\"dst\":" << rectJsonDevice(dst);
        if (image) {
            mOutput << ",\"w\":" << image->width() << ",\"h\":" << image->height();
        }
        mOutput << "}";
    }

    void onDrawImageLattice2(const SkImage*, const Lattice&, const SkRect& dst,
                             SkFilterMode, const SkPaint*) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImageLattice\",\"dst\":" << rectJsonDevice(dst) << "}";
    }

    void onDrawPoints(SkCanvas::PointMode, size_t count, const SkPoint[],
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPoints\",\"count\":" << count
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawRegion(const SkRegion&, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRegion\",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPicture(const SkPicture*, const SkMatrix*, const SkPaint*) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPicture\"}";
    }

    /**
     * Override onDrawDrawable to wrap the inlined content with a "drawDrawable"
     * marker, preserving RenderNode hierarchy in the output. The default Skia
     * behavior (drawable->draw(this)) inlines the drawable's content through
     * our other onDrawXXX overrides, recursively handling nested RenderNodes.
     *
     * The drawable's own bounds are emitted in device space using the CURRENT
     * matrix (i.e. the outer RenderNode's transform), captured BEFORE we
     * concat the inner matrix and recurse.
     */
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        if (!drawable) return;
        sep();
        SkRect b = drawable->getBounds();
        mOutput << indent() << "{\"op\":\"drawDrawable\",\"bounds\":" << rectJsonDevice(b)
                << ",\"ops\":[";
        mLevel++;
        mFirstStack.push_back(true);
        if (matrix) {
            this->save();
            this->concat(*matrix);
            drawable->draw(this);
            this->restore();
        } else {
            drawable->draw(this);
        }
        mFirstStack.pop_back();
        mLevel--;
        mOutput << "]}";
    }

private:
    void sep() {
        if (!mFirstStack.back()) {
            mOutput << ",\n";
        }
        mFirstStack.back() = false;
    }

    std::string indent() const {
        return std::string((mLevel + 1) * 2, ' ');
    }

    // ── Coordinate mapping helpers ────────────────────────────────────
    //
    // Every onDrawXxx callback runs with SkCanvas's local-to-device matrix
    // updated by all preceding save()/concat() calls (notably the ones issued
    // by RenderNodeDrawable while it walks the tree). We snapshot that matrix
    // here and use it to map local geometry to device (screen) space.
    //
    // For pure translate+scale transforms (the common UI case), SkMatrix::mapRect
    // is exact. For rotations/perspective, it returns the axis-aligned bounding
    // box of the transformed quad — acceptable for agent layout reasoning.

    SkMatrix currentMatrix() const {
        return this->getLocalToDeviceAs3x3();
    }

    std::string rectJsonDevice(const SkRect& localRect) {
        SkRect device;
        currentMatrix().mapRect(&device, localRect);
        return rectJson(device);
    }

    void pointToDevice(SkScalar* x, SkScalar* y) {
        SkPoint p = {*x, *y};
        currentMatrix().mapPoints(&p, &p, 1);
        *x = p.fX;
        *y = p.fY;
    }

    static std::string rectJson(const SkRect& r) {
        std::ostringstream s;
        s << "[" << (int)r.fLeft << "," << (int)r.fTop << ","
          << (int)r.fRight << "," << (int)r.fBottom << "]";
        return s.str();
    }

    static std::string colorHex(const SkPaint& paint) {
        SkColor c = paint.getColor();
        std::ostringstream s;
        s << "#" << std::hex << std::setfill('0') << std::setw(8) << c;
        return s.str();
    }

    static std::string paintStyle(const SkPaint& paint) {
        if (paint.getStyle() == SkPaint::kStroke_Style) {
            std::ostringstream s;
            s << ",\"style\":\"stroke\",\"strokeWidth\":" << paint.getStrokeWidth();
            return s.str();
        }
        return "";
    }

    // JSON-escape and write a C-string to mOutput. Font family names from
    // SkTypeface are usually plain ASCII (e.g. "Roboto", "sans-serif",
    // "NotoSerifCJK-Regular"), but guard against quotes/backslashes anyway.
    void writeEscaped(const char* s) {
        if (!s) return;
        for (; *s; ++s) {
            char c = *s;
            switch (c) {
                case '"':  mOutput << "\\\""; break;
                case '\\': mOutput << "\\\\"; break;
                case '\n': mOutput << "\\n";  break;
                case '\r': mOutput << "\\r";  break;
                case '\t': mOutput << "\\t";  break;
                default:
                    if ((unsigned char)c < 0x20) {
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned int)(unsigned char)c);
                        mOutput << buf;
                    } else {
                        mOutput << c;
                    }
            }
        }
    }

    std::ostream& mOutput;
    int mLevel;
    std::vector<bool> mFirstStack;  // tracks "first item in current level"
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
