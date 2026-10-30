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

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <unordered_map>
#include <vector>

#include <SkCanvas.h>
#include <SkCanvasVirtualEnforcer.h>
#include <SkData.h>
#include <SkColor.h>
#include <SkImage.h>
#include <SkRRect.h>
#include <SkTextBlob.h>
#include <SkFont.h>
#include <SkPath.h>
#include <SkPixmap.h>
#include <SkRegion.h>
#include <SkPicture.h>
#include <SkString.h>
#include <SkTypeface.h>
#include <SkShader.h>
#include <SkMesh.h>
#include <SkVertices.h>
#include <src/core/SkVerticesPriv.h>
#include <src/core/SkDrawShadowInfo.h>
#include <src/text/GlyphRun.h>
#include <include/private/chromium/Slug.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * FlatExportOpsCanvas exports recorded drawing commands as hierarchical JSON.
 * Use RenderNodeDrawable::forceDrawForObservation to expand cached RenderLayers.
 * External pixels, shaders and effects are identified explicitly; this stream
 * is not a lossless screenshot or a proof of final pixel visibility.
 *
 * All bounds and points emitted are in global logical SCREEN space:
 * every onDrawXxx callback maps its local-space inputs through the current
 * local-to-device matrix maintained by SkCanvas (which is updated by the
 * save()/concat() calls RenderNodeDrawable issues as it walks the tree), then
 * offsets the replayed coordinates into global logical-screen space. Root
 * DisplayLists are recorded with the Surface inset translated into the canvas;
 * CanvasContext removes that replay inset from the Window Surface origin before
 * constructing this canvas.
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
class FlatExportOpsCanvas : public SkCanvasVirtualEnforcer<SkCanvas> {
public:
    FlatExportOpsCanvas(std::ostream& output, int width, int height,
                        int screenOffsetX = 0, int screenOffsetY = 0)
            : SkCanvasVirtualEnforcer<SkCanvas>(width, height)
            , mOutput(output)
            , mLevel(0)
            , mFirstStack({true})
            , mScreenOffsetX(screenOffsetX)
            , mScreenOffsetY(screenOffsetY) {}

protected:
    // ── State ─────────────────────────────────────────────────────────
    void willSave() override { stateOp("save"); }
    void willRestore() override { stateOp("restore"); }

    SaveLayerStrategy getSaveLayerStrategy(const SaveLayerRec& rec) override {
        sep();
        mOutput << "{\"op\":\"saveLayer\"";
        if (rec.fBounds) mOutput << ",\"bounds\":" << rectJsonDevice(*rec.fBounds);
        if (rec.fPaint) writePaint(*rec.fPaint);
        mOutput << ",\"hasBackdropFilter\":" << (rec.fBackdrop ? "true" : "false") << "}";
        return kNoLayer_SaveLayerStrategy;  // Record state, never allocate/rasterize a layer.
    }

    bool onDoSaveBehind(const SkRect* bounds) override {
        sep();
        mOutput << "{\"op\":\"saveBehind\"";
        if (bounds) mOutput << ",\"bounds\":" << rectJsonDevice(*bounds);
        mOutput << ",\"contentStatus\":\"backdrop_pixels_required\"}";
        return false;
    }

    void onClipRect(const SkRect& rect, SkClipOp op, ClipEdgeStyle edge) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRect\",\"bounds\":" << rectJsonDevice(rect);
        mOutput << ",\"axisAligned\":" << (currentMatrix().rectStaysRect() ? "true" : "false");
        writeClip(op, edge);
        mOutput << "}";
        SkCanvas::onClipRect(rect, op, edge);
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp op, ClipEdgeStyle edge) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRRect\",\"bounds\":" << rectJsonDevice(rrect.rect())
                << ",\"rx\":" << rrect.getSimpleRadii().x()
                << ",\"ry\":" << rrect.getSimpleRadii().y();
        writeClip(op, edge);
        mOutput << "}";
        SkCanvas::onClipRRect(rrect, op, edge);
    }

    void onClipPath(const SkPath& path, SkClipOp op, ClipEdgeStyle edge) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipPath\",\"bounds\":" << rectJsonDevice(path.getBounds());
        writeClip(op, edge);
        SkPath mapped;
        path.transform(currentMatrix(), &mapped);
        mapped.offset(mScreenOffsetX, mScreenOffsetY);
        writeNormalizedPath(mapped);
        mOutput << "}";
        SkCanvas::onClipPath(path, op, edge);
    }

    void onClipRegion(const SkRegion& region, SkClipOp op) override {
        sep();
        mOutput << indent() << "{\"op\":\"clipRegion\",\"clipOp\":" << (int)op;
        writeRegion(region, false); // SkRegion clipping is already in device coordinates.
        mOutput << "}";
        SkCanvas::onClipRegion(region, op);
    }

    void onClipShader(sk_sp<SkShader> shader, SkClipOp op) override {
        sep();
        mOutput << "{\"op\":\"clipShader\",\"clipOp\":" << (int)op
                << ",\"contentStatus\":\"shader_pixels_required\"}";
        SkCanvas::onClipShader(std::move(shader), op);
    }

    void onResetClip() override {
        sep();
        mOutput << indent() << "{\"op\":\"resetClip\"}";
        SkCanvas::onResetClip();
    }

    void onDiscard() override { stateOp("discard"); }

    // ── Draws ─────────────────────────────────────────────────────────
    void onDrawPaint(const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPaint\",\"color\":\"" << colorHex(paint) << "\"";
        writePaint(paint);
        mOutput << "}";
    }

    void onDrawBehind(const SkPaint& paint) override {
        sep();
        mOutput << "{\"op\":\"drawBehind\",\"contentStatus\":\"backdrop_pixels_required\"";
        writePaint(paint);
        mOutput << "}";
    }

    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawRect\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"axisAligned\":" << (currentMatrix().rectStaysRect() ? "true" : "false")
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
                << ",\"color\":\"" << colorHex(paint) << "\"" << paintStyle(paint) << "}";
    }

    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawOval\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"color\":\"" << colorHex(paint) << "\"" << paintStyle(paint) << "}";
    }

    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle,
                   bool useCenter, const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawArc\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"start\":" << startAngle << ",\"sweep\":" << sweepAngle
                << ",\"useCenter\":" << (useCenter ? "true" : "false")
                << ",\"color\":\"" << colorHex(paint) << "\"" << paintStyle(paint) << "}";
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        SkPath devicePath;
        path.transform(currentMatrix(), &devicePath);
        devicePath.offset(mScreenOffsetX, mScreenOffsetY);
        sep();
        mOutput << indent() << "{\"op\":\"drawPath\",\"bounds\":"
                << rectJson(devicePath.getBounds())
                << ",\"color\":\"" << colorHex(paint) << "\""
                << paintStyle(paint);
        writeNormalizedPath(devicePath);
        mOutput << "}";
    }

    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint& paint) override {
        // Route blobs and direct drawGlyphs/drawSimpleText through one exporter.
        if (blob) SkCanvas::onDrawTextBlob(blob, x, y, paint);
    }

    void onDrawGlyphRunList(const sktext::GlyphRunList& list, const SkPaint& paint) override {
        if (list.empty()) return;
        sep();
        const SkRect bounds = list.sourceBoundsWithOrigin();
        SkScalar x = list.origin().x(), y = list.origin().y();
        pointToDevice(&x, &y);
        mOutput << "{\"op\":\"drawText\",\"x\":" << x << ",\"y\":" << y
                << ",\"bounds\":" << rectJsonDevice(bounds)
                << ",\"size\":" << bounds.height()
                << ",\"color\":\"" << colorHex(paint) << "\"";
        writePaint(paint);
        mOutput << ",\"runs\":[";
        bool first = true;
        for (const auto& run : list) {
            if (!first) mOutput << ',';
            first = false;
            const SkFont& font = run.font();
            const SkTypeface* face = font.getTypeface();
            SkString family;
            if (face) face->getFamilyName(&family);
            mOutput << "{\"font\":\"";
            writeEscaped(family.c_str());
            mOutput << "\",\"fontSize\":" << font.getSize()
                    << ",\"fontScaleX\":" << font.getScaleX()
                    << ",\"fontSkewX\":" << font.getSkewX();
            if (face) {
                mOutput << ",\"fontFingerprint\":\"" << fontFingerprint(face) << "\""
                        << ",\"fontWeight\":" << face->fontStyle().weight()
                        << ",\"fontWidth\":" << face->fontStyle().width()
                        << ",\"fontSlant\":" << (int)face->fontStyle().slant();
            }
            mOutput << ",\"glyphs\":[";
            for (size_t i = 0; i < run.runSize(); ++i) {
                if (i) mOutput << ',';
                mOutput << (unsigned int)run.glyphsIDs()[i];
            }
            mOutput << "],\"positions\":[";
            for (size_t i = 0; i < run.runSize(); ++i) {
                if (i) mOutput << ',';
                SkScalar px = run.positions()[i].x() + list.origin().x();
                SkScalar py = run.positions()[i].y() + list.origin().y();
                pointToDevice(&px, &py);
                mOutput << '[' << px << ',' << py << ']';
            }
            mOutput << ']';
            if (!run.text().empty()) {
                mOutput << ",\"textUtf8\":\"";
                writeEscaped(mOutput, run.text().data(), run.text().size());
                mOutput << "\",\"clusters\":[";
                writeNumbers(run.clusters().data(), run.clusters().size());
                mOutput << ']';
            }
            if (!run.scaledRotations().empty()) {
                mOutput << ",\"scaledRotations\":[";
                for (size_t i = 0; i < run.scaledRotations().size(); ++i) {
                    if (i) mOutput << ',';
                    mOutput << '[' << run.scaledRotations()[i].x() << ','
                            << run.scaledRotations()[i].y() << ']';
                }
                mOutput << ']';
            }
            mOutput << '}';
        }
        mOutput << "]}";
    }

    void onDrawImage2(const SkImage* image, SkScalar x, SkScalar y,
                      const SkSamplingOptions& sampling, const SkPaint* paint) override {
        if (!image) return;
        onDrawImageRect2(image, SkRect::MakeIWH(image->width(), image->height()),
                        SkRect::MakeXYWH(x, y, image->width(), image->height()),
                        sampling, paint, kFast_SrcRectConstraint);
    }

    void onDrawImageRect2(const SkImage* image, const SkRect& src, const SkRect& dst,
                          const SkSamplingOptions& sampling, const SkPaint* paint,
                          SrcRectConstraint constraint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawImage\",\"src\":" << rectJson(src)
                << ",\"dst\":" << rectJsonDevice(dst)
                << ",\"filter\":" << (int)sampling.filter
                << ",\"srcConstraint\":" << (int)constraint;
        if (paint) writePaint(*paint);
        if (image) {
            mOutput << ",\"w\":" << image->width() << ",\"h\":" << image->height()
                    << ",\"imageId\":" << image->uniqueID();
            writeImageVisual(image, src);
        } else {
            mOutput << ",\"visualStatus\":\"no_image\"";
        }
        mOutput << "}";
    }

    void onDrawImageLattice2(const SkImage* image, const Lattice& lattice, const SkRect& dst,
                             SkFilterMode filter, const SkPaint* paint) override {
        sep();
        mOutput << "{\"op\":\"drawImageLattice\",\"dst\":" << rectJsonDevice(dst)
                << ",\"filter\":" << (int)filter << ",\"xDivs\":[";
        writeNumbers(lattice.fXDivs, lattice.fXCount);
        mOutput << "],\"yDivs\":[";
        writeNumbers(lattice.fYDivs, lattice.fYCount);
        mOutput << ']';
        if (lattice.fBounds) mOutput << ",\"src\":" << rectJson(SkRect::Make(*lattice.fBounds));
        if (lattice.fRectTypes) {
            mOutput << ",\"rectTypes\":[";
            const int cells = (lattice.fXCount + 1) * (lattice.fYCount + 1);
            for (int i = 0; i < cells; ++i) {
                if (i) mOutput << ',';
                mOutput << (int)lattice.fRectTypes[i];
            }
            mOutput << ']';
            if (lattice.fColors) {
                mOutput << ",\"colors\":[";
                writeNumbers(lattice.fColors, cells);
                mOutput << ']';
            }
        }
        if (paint) writePaint(*paint);
        if (image) {
            mOutput << ",\"imageId\":" << image->uniqueID()
                    << ",\"w\":" << image->width() << ",\"h\":" << image->height();
            writeImageVisual(image, SkRect::MakeIWH(image->width(), image->height()));
        }
        mOutput << '}';
    }

    void onDrawPoints(SkCanvas::PointMode mode, size_t count, const SkPoint points[],
                      const SkPaint& paint) override {
        sep();
        mOutput << indent() << "{\"op\":\"drawPoints\",\"count\":" << count
                << ",\"color\":\"" << colorHex(paint) << "\""
                << ",\"mode\":" << (int)mode << ",\"points\":";
        writePoints(points, count);
        writePaint(paint);
        mOutput << '}';
    }

    void onDrawRegion(const SkRegion& region, const SkPaint& paint) override {
        sep();
        mOutput << "{\"op\":\"drawRegion\",\"color\":\"" << colorHex(paint) << "\"";
        writeRegion(region, true);
        writePaint(paint);
        mOutput << '}';
    }

    void onDrawPicture(const SkPicture* picture, const SkMatrix* matrix,
                       const SkPaint* paint) override {
        if (!picture) return;
        sep();
        mOutput << "{\"op\":\"drawPicture\",\"ops\":[";
        mFirstStack.push_back(true);
        ++mLevel;
        // The base implementation preserves the optional matrix/saveLayer paint
        // and plays the picture back through this canvas's virtual draw hooks.
        SkCanvas::onDrawPicture(picture, matrix, paint);
        --mLevel;
        mFirstStack.pop_back();
        mOutput << "]}";
    }

    void onDrawPatch(const SkPoint cubics[12], const SkColor colors[4],
                     const SkPoint texCoords[4], SkBlendMode mode, const SkPaint& paint) override {
        sep();
        mOutput << "{\"op\":\"drawPatch\",\"patchBlendMode\":" << (int)mode << ",\"cubics\":";
        writePoints(cubics, 12);
        if (colors) { mOutput << ",\"colors\":["; writeNumbers(colors, 4); mOutput << ']'; }
        if (texCoords) { mOutput << ",\"texCoords\":"; writePoints(texCoords, 4, false); }
        writePaint(paint);
        mOutput << '}';
    }

    void onDrawVerticesObject(const SkVertices* vertices, SkBlendMode mode,
                              const SkPaint& paint) override {
        if (!vertices) return;
        const auto data = vertices->priv();
        sep();
        mOutput << "{\"op\":\"drawVertices\",\"bounds\":" << rectJsonDevice(vertices->bounds())
                << ",\"vertexMode\":" << (int)data.mode() << ",\"vertexBlendMode\":" << (int)mode
                << ",\"positions\":";
        writePoints(data.positions(), data.vertexCount());
        if (data.texCoords()) {
            mOutput << ",\"texCoords\":"; writePoints(data.texCoords(), data.vertexCount(), false);
        }
        if (data.colors()) {
            mOutput << ",\"colors\":["; writeNumbers(data.colors(), data.vertexCount()); mOutput << ']';
        }
        mOutput << ",\"indices\":[";
        writeNumbers(data.indices(), data.indexCount());
        mOutput << ']';
        writePaint(paint);
        mOutput << '}';
    }

    void onDrawMesh(const SkMesh& mesh, sk_sp<SkBlender>, const SkPaint& paint) override {
        sep();
        mOutput << "{\"op\":\"drawMesh\",\"bounds\":" << rectJsonDevice(mesh.bounds())
                << ",\"contentStatus\":\"mesh_shader_required\"";
        writePaint(paint);
        mOutput << '}';
    }

    void onDrawAtlas2(const SkImage* atlas, const SkRSXform transforms[], const SkRect src[],
                      const SkColor colors[], int count, SkBlendMode mode,
                      const SkSamplingOptions& sampling, const SkRect*, const SkPaint* paint) override {
        sep();
        mOutput << "{\"op\":\"drawAtlas\",\"atlasBlendMode\":" << (int)mode;
        if (colors) { mOutput << ",\"colors\":["; writeNumbers(colors, count); mOutput << ']'; }
        mOutput << ",\"ops\":[";
        mFirstStack.push_back(true);
        ++mLevel;
        for (int i = 0; i < count; ++i) {
            SkAutoCanvasRestore restore(this, true);
            SkMatrix transform;
            transform.setRSXform(transforms[i]);
            concat(transform);
            onDrawImageRect2(atlas, src[i], SkRect::MakeWH(src[i].width(), src[i].height()),
                             sampling, paint, kStrict_SrcRectConstraint);
        }
        --mLevel;
        mFirstStack.pop_back();
        mOutput << "]}";
    }

    void onDrawEdgeAAImageSet2(const ImageSetEntry entries[], int count, const SkPoint clips[],
                               const SkMatrix matrices[], const SkSamplingOptions& sampling,
                               const SkPaint* paint, SrcRectConstraint constraint) override {
        int clipIndex = 0;
        for (int i = 0; i < count; ++i) {
            SkAutoCanvasRestore restore(this, true);
            if (entries[i].fMatrixIndex >= 0) concat(matrices[entries[i].fMatrixIndex]);
            if (entries[i].fHasClip) {
                SkPath clip;
                clip.addPoly(clips + clipIndex, 4, true);
                clipPath(clip);
                clipIndex += 4;
            }
            SkPaint p = paint ? *paint : SkPaint();
            p.setAlphaf(p.getAlphaf() * entries[i].fAlpha);
            onDrawImageRect2(entries[i].fImage.get(), entries[i].fSrcRect, entries[i].fDstRect,
                             sampling, &p, constraint);
        }
    }

    void onDrawEdgeAAQuad(const SkRect& rect, const SkPoint clip[4], QuadAAFlags flags,
                          const SkColor4f& color, SkBlendMode mode) override {
        sep();
        mOutput << "{\"op\":\"drawEdgeAAQuad\",\"bounds\":" << rectJsonDevice(rect)
                << ",\"aaFlags\":" << (int)flags << ",\"blendMode\":" << (int)mode
                << ",\"color\":\"" << colorHex(color.toSkColor()) << "\"";
        if (clip) { mOutput << ",\"clipPoints\":"; writePoints(clip, 4); }
        mOutput << '}';
    }

    void onDrawAnnotation(const SkRect& rect, const char key[], SkData* value) override {
        sep();
        mOutput << "{\"op\":\"annotation\",\"bounds\":" << rectJsonDevice(rect);
        if (key && !strcmp(key, "hwui.mesh.shader_required")) {
            mOutput << ",\"contentStatus\":\"mesh_shader_required\"";
        }
        mOutput << ",\"key\":\"";
        writeEscaped(key);
        mOutput << "\",\"dataHex\":\"";
        static constexpr char hex[] = "0123456789abcdef";
        if (value) {
            const auto* bytes = static_cast<const uint8_t*>(value->data());
            for (size_t i = 0; i < value->size(); ++i) mOutput << hex[bytes[i] >> 4] << hex[bytes[i] & 15];
        }
        mOutput << "\"}";
    }

    void onDrawShadowRec(const SkPath& path, const SkDrawShadowRec& rec) override {
        sep();
        mOutput << "{\"op\":\"drawShadow\",\"bounds\":" << rectJsonDevice(path.getBounds())
                << ",\"contentStatus\":\"shadow_effect\",\"flags\":" << rec.fFlags
                << ",\"ambientColor\":\"" << colorHex(rec.fAmbientColor)
                << "\",\"spotColor\":\"" << colorHex(rec.fSpotColor) << "\"}";
    }

    sk_sp<sktext::gpu::Slug> onConvertGlyphRunListToSlug(
            const sktext::GlyphRunList&, const SkPaint&) override {
        return nullptr;  // An observation canvas cannot allocate a GPU text cache.
    }

    void onDrawSlug(const sktext::gpu::Slug* slug, const SkPaint&) override {
        if (!slug) return;
        sep();
        mOutput << "{\"op\":\"drawSlug\",\"bounds\":" << rectJsonDevice(slug->sourceBoundsWithOrigin())
                << ",\"contentStatus\":\"gpu_text_cache_unavailable\"}";
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
        if (matrix) matrix->mapRect(&b);
        const char* type = drawable->getTypeName();
        mOutput << indent() << "{\"op\":\"drawDrawable\",\"bounds\":" << rectJsonDevice(b)
                << ",\"drawableType\":\"";
        writeEscaped(type);
        mOutput << "\"";
        if (type && (!strcmp(type, "FunctorDrawable") || !strcmp(type, "LayerDrawable") ||
                     !strcmp(type, "BackdropFilterDrawable"))) {
            // WebView/TextureView/backdrop pixels are external to this command
            // stream. Do not replace them with misleading dummy rectangles or
            // invoke a GPU functor during observation.
            mOutput << ",\"contentStatus\":\"external_pixels_required\",\"ops\":[]}";
            return;
        }
        mOutput << ",\"ops\":[";
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
        const bool noCommands = mFirstStack.back();
        mFirstStack.pop_back();
        mLevel--;
        mOutput << ']';
        if (noCommands) mOutput << ",\"contentStatus\":\"no_replayed_commands\"";
        mOutput << '}';
    }

private:
    void stateOp(const char* op) {
        sep();
        mOutput << "{\"op\":\"" << op << "\"}";
    }

    void writeClip(SkClipOp op, ClipEdgeStyle edge) {
        mOutput << ",\"clipOp\":" << (int)op
                << ",\"antiAlias\":" << (edge == kSoft_ClipEdgeStyle ? "true" : "false");
    }

    void writePaint(const SkPaint& paint) {
        mOutput << ",\"paintColor\":\"" << colorHex(paint) << "\"" << paintStyle(paint);
    }

    template <typename T>
    void writeNumbers(const T* values, size_t count) {
        for (size_t i = 0; values && i < count; ++i) {
            if (i) mOutput << ',';
            mOutput << values[i];
        }
    }

    void writePoints(const SkPoint* points, size_t count, bool transform = true) {
        mOutput << '[';
        for (size_t i = 0; i < count; ++i) {
            if (i) mOutput << ',';
            SkScalar x = points[i].x(), y = points[i].y();
            if (transform) pointToDevice(&x, &y);
            mOutput << '[' << x << ',' << y << ']';
        }
        mOutput << ']';
    }

    void writeRegion(const SkRegion& region, bool transform) {
        mOutput << ",\"rects\":[";
        bool first = true;
        for (SkRegion::Iterator it(region); !it.done(); it.next()) {
            if (!first) mOutput << ',';
            first = false;
            SkRect rect = SkRect::Make(it.rect());
            if (transform) {
                mOutput << rectJsonDevice(rect);
            } else {
                rect.offset(mScreenOffsetX, mScreenOffsetY);
                mOutput << rectJson(rect);
            }
        }
        mOutput << ']';
    }

    // Identity of tables that determine glyph numbering, names and substitutions.
    // FNV-1a is an identity hint, not a cryptographic integrity claim. Host assets
    // retain SHA256 independently. Cache once per typeface, never once per run.
    static std::string fontFingerprint(const SkTypeface* typeface) {
        static std::mutex lock;
        static std::unordered_map<SkTypefaceID, std::string> cache;
        std::lock_guard<std::mutex> guard(lock);
        const auto id = typeface->uniqueID();
        auto found = cache.find(id);
        if (found != cache.end()) return found->second;
        uint64_t hash = UINT64_C(14695981039346656037);
        auto byte = [&hash](uint8_t b) { hash ^= b; hash *= UINT64_C(1099511628211); };
        constexpr SkFontTableTag tags[] = {0x636d6170, 0x6d617870, 0x706f7374, 0x47535542};
        for (const auto tag : tags) {
            auto data = typeface->copyTableData(tag);
            const uint32_t size = data ? static_cast<uint32_t>(data->size()) : 0;
            for (int shift = 24; shift >= 0; shift -= 8) byte((tag >> shift) & 0xff);
            for (int shift = 24; shift >= 0; shift -= 8) byte((size >> shift) & 0xff);
            if (data) {
                const auto* bytes = static_cast<const uint8_t*>(data->data());
                for (size_t i = 0; i < data->size(); ++i) byte(bytes[i]);
            }
        }
        std::ostringstream result;
        result << "fnv1a64:" << std::hex << std::setw(16) << std::setfill('0') << hash;
        if (cache.size() >= 512) cache.clear();
        return cache.emplace(id, result.str()).first->second;
    }
    static constexpr int kMaxImageSampleDimension = 64;
    static constexpr size_t kMaxImageColorRuns = 1024;

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
    // here and use it to map local geometry to Surface space, then add the
    // Window Surface origin to produce global logical-screen coordinates.
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
        device.offset(mScreenOffsetX, mScreenOffsetY);
        return rectJson(device);
    }

    void pointToDevice(SkScalar* x, SkScalar* y) {
        SkPoint p = {*x, *y};
        currentMatrix().mapPoints(&p, &p, 1);
        *x = p.fX + mScreenOffsetX;
        *y = p.fY + mScreenOffsetY;
    }

    static std::string rectJson(const SkRect& r) {
        std::ostringstream s;
        // RenderNode drawables can use SkRectMakeLargest(). Casting those
        // coordinates to int overflows; retain finite scalar coordinates.
        s << '[';
        const SkScalar values[] = {r.fLeft, r.fTop, r.fRight, r.fBottom};
        for (int i = 0; i < 4; ++i) {
            if (i) s << ',';
            if (std::isfinite(values[i])) s << values[i];
            else s << "null";
        }
        s << ']';
        return s.str();
    }

    static std::string colorHex(const SkPaint& paint) {
        SkColor c = paint.getColor();
        std::ostringstream s;
        s << "#" << std::hex << std::setfill('0') << std::setw(8) << c;
        return s.str();
    }

    static std::string paintStyle(const SkPaint& paint) {
        std::ostringstream s;
        s << ",\"alpha\":" << paint.getAlphaf()
          << ",\"hasShader\":" << (paint.getShader() ? "true" : "false")
          << ",\"hasImageFilter\":" << (paint.getImageFilter() ? "true" : "false")
          << ",\"hasColorFilter\":" << (paint.getColorFilter() ? "true" : "false")
          << ",\"hasMaskFilter\":" << (paint.getMaskFilter() ? "true" : "false");
        s << ",\"hasPathEffect\":" << (paint.getPathEffect() ? "true" : "false");
        const auto blend = paint.asBlendMode();
        if (blend) s << ",\"blendMode\":" << (int)*blend;
        else s << ",\"customBlender\":true";
        if (paint.getStyle() == SkPaint::kStroke_Style) {
            s << ",\"style\":\"stroke\",\"strokeWidth\":" << paint.getStrokeWidth();
        } else if (paint.getStyle() == SkPaint::kStrokeAndFill_Style) {
            s << ",\"style\":\"strokeAndFill\",\"strokeWidth\":" << paint.getStrokeWidth();
        }
        return s.str();
    }

    static std::string colorHex(SkColor color) {
        std::ostringstream s;
        s << "#" << std::hex << std::setfill('0') << std::setw(8) << color;
        return s.str();
    }

    /**
     * Export a bounded, nearest-neighbor ARGB sample of the source image.
     *
     * This is an intermediate visual representation, not the final icon mask.
     * Python may later crop, resize, threshold, or reject it using experiment
     * settings without rebuilding libhwui. Run-length encoding keeps flat UI
     * icons compact. Complex photographic images are rejected once their color
     * run count exceeds the safety limit.
     *
     * Texture-backed/lazy images for which peekPixels() is unavailable are
     * reported without forcing a synchronous GPU readback on RenderThread.
     */
    void writeImageVisual(const SkImage* image, const SkRect& requestedSrc) {
        SkPixmap pixmap;
        if (!image->peekPixels(&pixmap)) {
            mOutput << ",\"visualStatus\":\"pixels_unavailable\"";
            return;
        }

        SkRect src = requestedSrc;
        const SkRect imageBounds =
                SkRect::MakeWH(static_cast<SkScalar>(pixmap.width()),
                               static_cast<SkScalar>(pixmap.height()));
        if (!src.isFinite() || !src.intersect(imageBounds) ||
            src.isEmpty() || src.width() < 1.0f || src.height() < 1.0f) {
            mOutput << ",\"visualStatus\":\"invalid_src\"";
            return;
        }

        const float scale = std::min(
                1.0f,
                static_cast<float>(kMaxImageSampleDimension) /
                        std::max(src.width(), src.height()));
        const int sampleWidth = std::max(1, static_cast<int>(std::ceil(src.width() * scale)));
        const int sampleHeight = std::max(1, static_cast<int>(std::ceil(src.height() * scale)));

        struct ColorRun {
            int count;
            SkColor color;
        };
        std::vector<ColorRun> runs;
        runs.reserve(std::min<size_t>(
                static_cast<size_t>(sampleWidth) * sampleHeight,
                kMaxImageColorRuns));

        for (int y = 0; y < sampleHeight; ++y) {
            for (int x = 0; x < sampleWidth; ++x) {
                const float sourceX =
                        src.left() + (static_cast<float>(x) + 0.5f) *
                                src.width() / sampleWidth;
                const float sourceY =
                        src.top() + (static_cast<float>(y) + 0.5f) *
                                src.height() / sampleHeight;
                const int pixelX = std::clamp(
                        static_cast<int>(sourceX), 0, pixmap.width() - 1);
                const int pixelY = std::clamp(
                        static_cast<int>(sourceY), 0, pixmap.height() - 1);
                const SkColor color = pixmap.getColor(pixelX, pixelY);

                if (!runs.empty() && runs.back().color == color) {
                    ++runs.back().count;
                } else {
                    runs.push_back({1, color});
                    if (runs.size() > kMaxImageColorRuns) {
                        mOutput << ",\"visualStatus\":\"too_complex\""
                                << ",\"sampleWidth\":" << sampleWidth
                                << ",\"sampleHeight\":" << sampleHeight;
                        return;
                    }
                }
            }
        }

        mOutput << ",\"visualStatus\":\"available\""
                << ",\"sampleWidth\":" << sampleWidth
                << ",\"sampleHeight\":" << sampleHeight
                << ",\"argbRle\":[";
        for (size_t i = 0; i < runs.size(); ++i) {
            if (i) mOutput << ",";
            mOutput << "[" << runs[i].count << ",\"" << colorHex(runs[i].color) << "\"]";
        }
        mOutput << "]";
    }

    /**
     * Export scale- and translation-independent path geometry.
     *
     * Points are normalized into the path's bounds. A zero-width/height axis
     * uses divisor 1, retaining stroked horizontal/vertical paths. Export all
     * verbs; consumer-side icon previews may impose their own complexity cap.
     */
    void writeNormalizedPath(const SkPath& path) {
        const SkRect bounds = path.getBounds();
        if (!bounds.isFinite()) {
            mOutput << ",\"pathVisualStatus\":\"invalid_bounds\"";
            return;
        }

        struct PathCommand {
            const char* verb;
            std::vector<SkPoint> points;
            float conicWeight = 0.0f;
            bool hasConicWeight = false;
        };

        std::vector<PathCommand> commands;
        commands.reserve(32);
        SkPath::RawIter iter(path);
        SkPoint points[4];

        while (true) {
            const SkPath::Verb verb = iter.next(points);
            if (verb == SkPath::kDone_Verb) break;

            PathCommand command;
            int pointCount = 0;
            switch (verb) {
                case SkPath::kMove_Verb:
                    command.verb = "M";
                    pointCount = 1;
                    break;
                case SkPath::kLine_Verb:
                    command.verb = "L";
                    pointCount = 2;
                    break;
                case SkPath::kQuad_Verb:
                    command.verb = "Q";
                    pointCount = 3;
                    break;
                case SkPath::kConic_Verb:
                    command.verb = "O";
                    pointCount = 3;
                    command.conicWeight = iter.conicWeight();
                    command.hasConicWeight = true;
                    break;
                case SkPath::kCubic_Verb:
                    command.verb = "C";
                    pointCount = 4;
                    break;
                case SkPath::kClose_Verb:
                    command.verb = "Z";
                    break;
                case SkPath::kDone_Verb:
                    break;
            }

            command.points.reserve(pointCount);
            for (int i = 0; i < pointCount; ++i) {
                command.points.push_back(SkPoint::Make(
                        (points[i].x() - bounds.left()) /
                                (bounds.width() > 0 ? bounds.width() : 1),
                        (points[i].y() - bounds.top()) /
                                (bounds.height() > 0 ? bounds.height() : 1)));
            }
            commands.push_back(std::move(command));
        }

        mOutput << ",\"pathVisualStatus\":\"available\""
                << ",\"pathFillType\":" << static_cast<int>(path.getFillType())
                << ",\"pathCommands\":[";
        for (size_t i = 0; i < commands.size(); ++i) {
            if (i) mOutput << ",";
            const PathCommand& command = commands[i];
            mOutput << "{\"verb\":\"" << command.verb << "\",\"points\":[";
            for (size_t j = 0; j < command.points.size(); ++j) {
                if (j) mOutput << ",";
                mOutput << "[" << command.points[j].x() << ","
                        << command.points[j].y() << "]";
            }
            mOutput << "]";
            if (command.hasConicWeight) {
                mOutput << ",\"weight\":" << command.conicWeight;
            }
            mOutput << "}";
        }
        mOutput << "]";
    }

    // JSON-escape and write a C-string to a stream. Font family names from
    // SkTypeface are usually plain ASCII (e.g. "Roboto", "sans-serif",
    // "NotoSerifCJK-Regular"), but guard against quotes/backslashes anyway.
public:
    static void writeEscaped(std::ostream& output, const char* s,
                             size_t size = std::string::npos) {
        if (!s) return;
        if (size == std::string::npos) size = strlen(s);
        for (size_t i = 0; i < size; ++i) {
            char c = s[i];
            switch (c) {
                case '"':  output << "\\\""; break;
                case '\\': output << "\\\\"; break;
                case '\n': output << "\\n";  break;
                case '\r': output << "\\r";  break;
                case '\t': output << "\\t";  break;
                default:
                    if ((unsigned char)c < 0x20) {
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned int)(unsigned char)c);
                        output << buf;
                    } else {
                        output << c;
                    }
            }
        }
    }

private:
    void writeEscaped(const char* s) { writeEscaped(mOutput, s); }

    std::ostream& mOutput;
    int mLevel;
    std::vector<bool> mFirstStack;  // tracks "first item in current level"
    int mScreenOffsetX;
    int mScreenOffsetY;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
