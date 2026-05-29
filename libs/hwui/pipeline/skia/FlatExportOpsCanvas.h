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
 * Output format:
 *   {"op":"clipRect","bounds":[...]},
 *   {"op":"drawText","x":..,"y":..,...},
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
        mOutput << indent() << "{\"op\":\"clipRect\",\"bounds\":" << rectJson(rect) << "}";
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRRect\",\"bounds\":" << rectJson(rrect.rect())
                << ",\"rx\":" << rrect.getSimpleRadii().x()
                << ",\"ry\":" << rrect.getSimpleRadii().y() << "}";
    }

    void onClipPath(const SkPath& path, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipPath\",\"bounds\":" << rectJson(path.getBounds())
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
        mOutput << indent() << "{\"op\":\"drawRect\",\"bounds\":" << rectJson(rect)
                << ",\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRRect\",\"bounds\":" << rectJson(rrect.rect())
                << ",\"rx\":" << rrect.getSimpleRadii().x()
                << ",\"ry\":" << rrect.getSimpleRadii().y()
                << ",\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawDRRect(const SkRRect& outer, const SkRRect& inner,
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawDRRect\",\"outer\":" << rectJson(outer.rect())
                << ",\"inner\":" << rectJson(inner.rect())
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawOval\",\"bounds\":" << rectJson(rect)
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle,
                   bool, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawArc\",\"bounds\":" << rectJson(rect)
                << ",\"start\":" << startAngle << ",\"sweep\":" << sweepAngle
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPath\",\"bounds\":" << rectJson(path.getBounds())
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint& paint) override {
        sep();
        SkRect bounds = blob ? blob->bounds().makeOffset(x, y) : SkRect::MakeEmpty();
        mOutput << indent() << "{\"op\":\"drawText\",\"x\":" << x << ",\"y\":" << y
                << ",\"bounds\":" << rectJson(bounds)
                << ",\"size\":" << bounds.height()
                << ",\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawImageRect2(const SkImage* image, const SkRect& src, const SkRect& dst,
                          const SkSamplingOptions&, const SkPaint*,
                          SrcRectConstraint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImage\",\"dst\":" << rectJson(dst);
        if (image) {
            mOutput << ",\"w\":" << image->width() << ",\"h\":" << image->height();
        }
        mOutput << "}";
    }

    void onDrawImageLattice2(const SkImage*, const Lattice&, const SkRect& dst,
                             SkFilterMode, const SkPaint*) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImageLattice\",\"dst\":" << rectJson(dst) << "}";
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
     */
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        if (!drawable) return;
        sep();
        // Use bounds for the drawable as a rough hint to the agent
        SkRect b = drawable->getBounds();
        mOutput << indent() << "{\"op\":\"drawDrawable\",\"bounds\":" << rectJson(b)
                << ",\"ops\":[";
        // Push new nesting level
        mLevel++;
        mFirstStack.push_back(true);
        // Inline the drawable's content. drawable->draw(this) calls our
        // onDrawXXX/onDrawDrawable overrides recursively. For RenderNodeDrawable,
        // this traverses the entire child RenderNode subtree.
        if (matrix) {
            this->save();
            this->concat(*matrix);
            drawable->draw(this);
            this->restore();
        } else {
            drawable->draw(this);
        }
        // Pop nesting level
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

    std::ostream& mOutput;
    int mLevel;
    std::vector<bool> mFirstStack;  // tracks "first item in current level"
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
