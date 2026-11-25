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
#include "RenderNode.h"
#include "SkiaDisplayList.h"

#include <SkRRect.h>
#include <SkTextBlob.h>
#include <SkFont.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * ExportOpsCanvas replays a SkiaDisplayList and outputs structured JSON
 * containing full op parameters (bounds, colors, text, etc.).
 * Used to export the DisplayList for agent UI understanding.
 */
class ExportOpsCanvas : public SkCanvas {
public:
    ExportOpsCanvas(std::ostream& output, int level, const SkiaDisplayList& displayList)
            : mOutput(output)
            , mLevel(level)
            , mDisplayList(displayList)
            , mFirst(true) {}

protected:
    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRect\","
                << "\"bounds\":" << rectJson(rect) << ","
                << "\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRRect\","
                << "\"bounds\":" << rectJson(rrect.rect()) << ","
                << "\"rx\":" << rrect.getSimpleRadii().x() << ","
                << "\"ry\":" << rrect.getSimpleRadii().y() << ","
                << "\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint) << "}";
    }

    void onDrawDRRect(const SkRRect& outer, const SkRRect& inner,
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawDRRect\","
                << "\"outer\":" << rectJson(outer.rect()) << ","
                << "\"inner\":" << rectJson(inner.rect()) << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawOval\","
                << "\"bounds\":" << rectJson(rect) << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle,
                   bool useCenter, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawArc\","
                << "\"bounds\":" << rectJson(rect) << ","
                << "\"start\":" << startAngle << ",\"sweep\":" << sweepAngle << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPath\","
                << "\"bounds\":" << rectJson(path.getBounds()) << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPaint(const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPaint\","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawText\","
                << "\"x\":" << x << ",\"y\":" << y << ","
                << "\"bounds\":" << rectJson(blob->bounds().makeOffset(x, y)) << ","
                << "\"text\":\"" << escapeJson(extractText(blob)) << "\","
                << "\"size\":" << (blob->bounds().height()) << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawImageRect2(const SkImage* image, const SkRect& src, const SkRect& dst,
                          const SkSamplingOptions&, const SkPaint*,
                          SrcRectConstraint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImage\","
                << "\"dst\":" << rectJson(dst);
        if (image) {
            mOutput << ",\"w\":" << image->width() << ",\"h\":" << image->height();
        }
        mOutput << "}";
    }

    void onDrawImageLattice2(const SkImage* image, const Lattice&, const SkRect& dst,
                             SkFilterMode, const SkPaint*) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImageLattice\","
                << "\"dst\":" << rectJson(dst) << "}";
    }

    void onDrawPoints(SkCanvas::PointMode mode, size_t count, const SkPoint pts[],
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPoints\","
                << "\"count\":" << count << ","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawRegion(const SkRegion&, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRegion\","
                << "\"color\":\"" << colorHex(paint) << "\"}";
    }

    void onDrawPicture(const SkPicture*, const SkMatrix*, const SkPaint*) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPicture\"}";
    }

    void onClipRect(const SkRect& rect, SkClipOp op, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRect\","
                << "\"bounds\":" << rectJson(rect) << "}";
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRRect\","
                << "\"bounds\":" << rectJson(rrect.rect()) << "}";
    }

    void onClipPath(const SkPath& path, SkClipOp, ClipEdgeStyle) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipPath\","
                << "\"bounds\":" << rectJson(path.getBounds()) << "}";
    }

    void onClipRegion(const SkRegion&, SkClipOp) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRegion\"}";
    }

    void onResetClip() override {
        sep();
        mOutput << indent() << "{\"op\":\"resetClip\"}";
    }

    void onDrawDrawable(SkDrawable* drawable, const SkMatrix*) override {
        auto* rnd = getRenderNodeDrawable(drawable);
        if (rnd) {
            sep();
            auto* node = rnd->getRenderNode();
            mOutput << indent() << "{\"op\":\"drawRenderNode\","
                    << "\"name\":\"" << escapeJson(node->getName()) << "\"";

            // Output RenderNode properties (bounds from RenderProperties)
            const auto& props = node->properties();
            mOutput << ",\"left\":" << props.getLeft()
                    << ",\"top\":" << props.getTop()
                    << ",\"right\":" << props.getRight()
                    << ",\"bottom\":" << props.getBottom();
            if (props.getAlpha() < 1.0f) {
                mOutput << ",\"alpha\":" << props.getAlpha();
            }
            if (props.getTranslationX() != 0 || props.getTranslationY() != 0) {
                mOutput << ",\"tx\":" << props.getTranslationX()
                        << ",\"ty\":" << props.getTranslationY();
            }

            // Recurse into children
            mOutput << ",\"children\":[";
            node->exportDisplayList(mOutput, mLevel + 1);
            mOutput << "]";
            mOutput << "}";
            return;
        }

        auto* functor = getFunctorDrawable(drawable);
        if (functor) {
            sep();
            mOutput << indent() << "{\"op\":\"drawFunctor\"}";
            return;
        }

        sep();
        mOutput << indent() << "{\"op\":\"drawDrawable\"}";
    }

private:
    void sep() {
        if (!mFirst) mOutput << ",\n";
        mFirst = false;
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

    // NOTE: Text content is not extracted here because Skia's SkFont API
    // (in this version) only supports unicharsToGlyphs (one-way). Glyph IDs
    // alone don't reliably map back to unicode characters with OpenType features.
    // Text extraction requires a higher-level Canvas hook (e.g., RecordingCanvas)
    // before glyph conversion. For now, only bounds and styling are emitted.
    static std::string extractText(const SkTextBlob* blob) {
        return "";
    }

    // Inline UTF-8 encoder. Kept for future use when text extraction is wired
    // through a higher-level hook that has access to the original text strings.
    static void appendUtf8(std::string& out, SkUnichar uni) {
        if (uni < 0 || uni > 0x10FFFF) return;
        uint32_t u = static_cast<uint32_t>(uni);
        if (u < 0x80) {
            out += static_cast<char>(u);
        } else if (u < 0x800) {
            out += static_cast<char>(0xC0 | (u >> 6));
            out += static_cast<char>(0x80 | (u & 0x3F));
        } else if (u < 0x10000) {
            out += static_cast<char>(0xE0 | (u >> 12));
            out += static_cast<char>(0x80 | ((u >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (u & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (u >> 18));
            out += static_cast<char>(0x80 | ((u >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((u >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (u & 0x3F));
        }
    }

    static std::string escapeJson(const std::string& s) {
        std::string out;
        out.reserve(s.size());
        for (char c : s) {
            switch (c) {
                case '"': out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default: out += c;
            }
        }
        return out;
    }

    const RenderNodeDrawable* getRenderNodeDrawable(SkDrawable* drawable) {
        for (auto& child : mDisplayList.mChildNodes) {
            if (drawable == &child) return &child;
        }
        return nullptr;
    }

    FunctorDrawable* getFunctorDrawable(SkDrawable* drawable) {
        for (auto& child : mDisplayList.mChildFunctors) {
            if (drawable == child) return child;
        }
        return nullptr;
    }

    std::ostream& mOutput;
    int mLevel;
    const SkiaDisplayList& mDisplayList;
    bool mFirst;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
