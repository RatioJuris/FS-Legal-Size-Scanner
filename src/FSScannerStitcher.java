import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Shared, no-resampling Foolscap reconstruction used by the test preview and processor. */
final class FSScannerStitcher {
    private FSScannerStitcher() { }


    /**
     * Append the exact retained FST and FSB crop pixels vertically. FSB must already
     * be rotated into reading orientation and cropped in that same coordinate frame.
     * This is the canonical crop-and-merge operation used by the test preview and
     * production processor. It deliberately does not force the result to page size;
     * page fitting/scaling belongs to the later PDF conversion stage.
     */
    static BufferedImage appendCrops(BufferedImage top, BufferedImage bottomRot) throws IOException {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(bottomRot, "bottomRot");
        if (top.getWidth() < 1 || bottomRot.getWidth() < 1
                || top.getHeight() < 1 || bottomRot.getHeight() < 1) {
            throw new IOException("FST and FSB crops must have positive dimensions.");
        }
        long totalHeight = (long) top.getHeight() + bottomRot.getHeight();
        if (totalHeight > Integer.MAX_VALUE) {
            throw new IOException("Combined FST/FSB crop is too tall to represent as an image.");
        }
        int width = Math.max(top.getWidth(), bottomRot.getWidth());
        int height = (int) totalHeight;
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            // No source rectangle is rescaled: source pixels are copied 1:1.
            g.drawImage(top, 0, 0, null);
            g.drawImage(bottomRot, 0, top.getHeight(), null);
        } finally {
            g.dispose();
        }
        return output;
    }

    /**
     * Build an 8.5 x 14 inch page from FST and an already-rotated 180-degree FSB.
     * seamY is the output-page row where FSB begins. Images are never stretched.
     * Pixels outside a source image's width remain white on the output canvas.
     */
    static BufferedImage stitch(BufferedImage top, BufferedImage bottomRot,
                                int dpi, double pageWIn, double pageHIn, int seamY) throws IOException {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(bottomRot, "bottomRot");
        if (dpi < 50 || dpi > 1200) throw new IOException("DPI must be between 50 and 1200.");
        if (!Double.isFinite(pageWIn) || !Double.isFinite(pageHIn) || pageWIn <= 0 || pageHIn <= 0)
            throw new IOException("Page dimensions must be positive finite inch values.");

        int width = Math.max(1, (int) Math.round(pageWIn * dpi));
        int height = Math.max(1, (int) Math.round(pageHIn * dpi));
        int minSeam = Math.max(0, height - bottomRot.getHeight());
        int maxSeam = Math.min(height, top.getHeight());
        if (minSeam > maxSeam) {
            throw new IOException("The captures are too short to cover the target page without stretching: "
                    + "target height=" + height + ", FST=" + top.getHeight() + ", FSB=" + bottomRot.getHeight());
        }
        if (seamY < minSeam || seamY > maxSeam) {
            throw new IOException("Seam row " + seamY + " is outside the valid range " + minSeam + ".." + maxSeam + ".");
        }
        int bottomHeight = height - seamY;
        int copyWidth = Math.min(width, Math.min(top.getWidth(), bottomRot.getWidth()));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            if (seamY > 0 && copyWidth > 0)
                g.drawImage(top, 0, 0, copyWidth, seamY, 0, 0, copyWidth, seamY, null);
            if (bottomHeight > 0 && copyWidth > 0) {
                int bottomStart = bottomRot.getHeight() - bottomHeight;
                g.drawImage(bottomRot, 0, seamY, copyWidth, height,
                        0, bottomStart, copyWidth, bottomRot.getHeight(), null);
            }
        } finally {
            g.dispose();
        }
        return output;
    }

    // ======================================================================
    //  Overlap-measured stitching (replaces trusting nominal 14.00 in / crop edges)
    // ======================================================================

    /** Largest vertical / horizontal correction searched for, in inches. */
    static final double MAX_SHIFT_Y_IN = 1.5;
    static final double MAX_SHIFT_X_IN = 0.5;
    /** Height of the texture band that is matched between the two captures. */
    static final double BAND_HEIGHT_IN = 1.0;
    /** Minimum normalised cross-correlation (0..1) to trust a measured alignment. */
    static final double MIN_SCORE = 0.60;
    /** A band flatter than this (grey-level std-dev) cannot be matched (blank paper). */
    static final double MIN_TEXTURE_STD = 10.0;
    /** Relative rotation between the two scans is corrected only inside this window. */
    static final double MIN_SKEW_DEG = 0.08;
    static final double MAX_SKEW_DEG = 3.0;

    /** Result of measuring where the (already 180-degree rotated) bottom capture sits. */
    static final class Alignment {
        /** Bottom capture, possibly de-rotated by {@link #angleDeg} (same pixel size). */
        final BufferedImage bottom;
        /** page column = bottom column - dx. */
        final int dx;
        /** page row = bottom row + (nominalPageHeight - bottomHeight) - dy. */
        final int dy;
        /** Rotation that was removed from the bottom capture, degrees (clockwise positive). */
        final double angleDeg;
        /** Normalised cross-correlation of the final match, 0..1 (negative = no match). */
        final double score;
        /** False when no trustworthy match was found; nominal geometry is then used. */
        final boolean confident;
        /** Number of page rows present in both captures. */
        final int overlapRows;
        Alignment(BufferedImage bottom, int dx, int dy, double angleDeg, double score,
                  boolean confident, int overlapRows) {
            this.bottom = bottom; this.dx = dx; this.dy = dy; this.angleDeg = angleDeg;
            this.score = score; this.confident = confident; this.overlapRows = overlapRows;
        }
        String describe() {
            return String.format(java.util.Locale.ROOT, "dx=%d dy=%d angle=%.2fdeg score=%.3f overlapRows=%d",
                    dx, dy, angleDeg, score, overlapRows);
        }
    }

    /**
     * Measure how the rotated bottom capture lines up with the top capture using the
     * content both scans contain (about 9 in of overlap for an A4 platen and a 14 in page).
     * Never throws for "no match": returns a non-confident nominal alignment instead.
     */
    static Alignment align(BufferedImage top, BufferedImage bottomRot, int dpi, double pageHIn) {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(bottomRot, "bottomRot");
        int H = (int) Math.round(pageHIn * dpi);
        int Ht = top.getHeight(), Hb = bottomRot.getHeight();
        int off0 = H - Hb;                       // nominal page row of bottom row 0
        int overlap = Math.max(0, Ht - Math.max(0, off0));
        Alignment fallback = new Alignment(bottomRot, 0, 0, 0.0, -1.0, false, overlap);

        int ry = (int) Math.round(MAX_SHIFT_Y_IN * dpi);
        int rx = (int) Math.round(MAX_SHIFT_X_IN * dpi);
        int bandH = (int) Math.round(BAND_HEIGHT_IN * dpi);
        int wMin = Math.min(top.getWidth(), bottomRot.getWidth());
        int tx = (int) Math.round(wMin * 0.05), tw = (int) Math.round(wMin * 0.90);
        if (tw < 64 || bandH < 16) return fallback;

        int lo, hi;
        while (true) {
            lo = Math.max(0, off0 + ry);
            hi = Math.min(Ht, H - ry);
            if (hi - lo >= bandH || ry <= 16) break;
            ry /= 2;
        }
        if (hi - lo < bandH) return fallback;

        int ty = chooseBand(top, lo, hi, bandH, tx, tw);
        if (ty < 0) return fallback;

        int ex = tx, ey = ty - off0;             // expected location in bottom if nominal were exact
        double[] m1 = matchBand(top, bottomRot, tx, ty, tw, bandH, ex, ey, rx, ry);
        if (m1 == null || m1[2] < MIN_SCORE) return fallback;

        BufferedImage bottom = bottomRot;
        int bx = (int) m1[0], by = (int) m1[1];
        double score = m1[2], angle = 0.0;

        // Relative skew: match the left and right halves of the same band separately.
        int gap = tw / 10, half = (tw - gap) / 2;
        int txL = tx, txR = tx + tw - half;
        double[] mL = matchBand(top, bottomRot, txL, ty, half, bandH, bx + (txL - tx), by, 24, 24);
        double[] mR = matchBand(top, bottomRot, txR, ty, half, bandH, bx + (txR - tx), by, 24, 24);
        if (mL != null && mR != null && mL[2] >= MIN_SCORE && mR[2] >= MIN_SCORE) {
            double phi = Math.toDegrees(Math.atan2(mR[1] - mL[1], mR[0] - mL[0]));
            if (Math.abs(phi) >= MIN_SKEW_DEG && Math.abs(phi) <= MAX_SKEW_DEG) {
                BufferedImage rotated = rotateKeepSize(bottomRot, -phi);
                double[] m2 = matchBand(top, rotated, tx, ty, tw, bandH, bx, by, 32, 32);
                if (m2 != null && m2[2] >= Math.max(MIN_SCORE, m1[2] - 0.10)) {
                    bottom = rotated; bx = (int) m2[0]; by = (int) m2[1];
                    score = m2[2]; angle = -phi;
                }
            }
        }
        int dx = bx - tx;
        int dy = by - ey;
        int bottomTop = off0 - dy;
        return new Alignment(bottom, dx, dy, angle, score, true, Math.max(0, Ht - Math.max(0, bottomTop)));
    }

    /** align + stitch with an automatically chosen seam; the form used by preview and production. */
    static BufferedImage stitchAuto(BufferedImage top, BufferedImage bottomRot,
                                    int dpi, double pageWIn, double pageHIn) throws IOException {
        return stitchAuto(top, bottomRot, dpi, pageWIn, pageHIn, 0.0);
    }

    /** As above, additionally moving the whole page left by shiftLeftIn inches (negative = right). */
    static BufferedImage stitchAuto(BufferedImage top, BufferedImage bottomRot,
                                    int dpi, double pageWIn, double pageHIn,
                                    double shiftLeftIn) throws IOException {
        int shiftPx = (int) Math.round(shiftLeftIn * dpi);
        return stitchAligned(top, align(top, bottomRot, dpi, pageHIn), dpi, pageWIn, pageHIn, -1, shiftPx);
    }

    /**
     * Build the page from the measured geometry. The output height is the MEASURED page
     * height (nominal - dy), so paper/scan-scale tolerance no longer shows up as a gap or
     * a repeated strip. seamRow is in output rows; pass -1 to choose a quiet (low-ink) row
     * near the middle of the overlap.
     */
    static BufferedImage stitchAligned(BufferedImage top, Alignment a, int dpi,
                                       double pageWIn, double pageHIn, int seamRow) throws IOException {
        return stitchAligned(top, a, dpi, pageWIn, pageHIn, seamRow, 0);
    }

    /**
     * shiftLeftPx moves the finished page left by that many pixels (negative = right). It is applied
     * by reading both captures from a different source column, so columns that exist beside the
     * canvas in the captures are brought in rather than the page being slid over a white strip.
     */
    static BufferedImage stitchAligned(BufferedImage top, Alignment a, int dpi,
                                       double pageWIn, double pageHIn, int seamRow,
                                       int shiftLeftPx) throws IOException {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(a, "alignment");
        if (dpi < 50 || dpi > 1200) throw new IOException("DPI must be between 50 and 1200.");
        BufferedImage bottom = a.bottom;
        int W = Math.max(1, (int) Math.round(pageWIn * dpi));
        int Hnom = Math.max(1, (int) Math.round(pageHIn * dpi));
        int Ht = top.getHeight(), Hb = bottom.getHeight();
        int bottomTop = (Hnom - Hb) - a.dy;      // page row of bottom row 0
        int Hout = bottomTop + Hb;               // measured page height (= Hnom - dy)
        if (bottomTop < 0) throw new IOException("Bottom capture starts above the page top (bottomTop=" + bottomTop + ").");
        if (bottomTop > Ht) throw new IOException("The captures do not overlap or touch: FST rows=" + Ht
                + ", FSB starts at page row " + bottomTop + ". Re-scan with more of the page on the glass.");

        int blend = Math.max(0, Math.round(dpi / 25f));            // ~8 rows at 200 dpi
        int lo = bottomTop + blend / 2, hi = Ht - blend / 2;
        if (lo > hi) { blend = 0; lo = bottomTop; hi = Ht; }
        int seam;
        if (seamRow >= 0) seam = Math.max(lo, Math.min(hi, seamRow));
        else seam = quietSeam(top, lo, hi, (bottomTop + Ht) / 2, Math.max(4, dpi / 25), (int) Math.round(1.25 * dpi));
        if (seam - blend / 2 < bottomTop || seam + blend / 2 > Ht) blend = 0;

        float gain = 1.0f;
        if (a.confident && Ht > bottomTop) {
            double pt = paperWhite(top, bottomTop, Ht);
            double pb = paperWhite(bottom, 0, Math.min(Hb, Ht - bottomTop));
            if (pt > 120 && pb > 120) {
                double g = pt / pb;
                if (Math.abs(g - 1.0) > 0.01) gain = (float) Math.max(0.85, Math.min(1.15, g));
            }
        }

        BufferedImage out = new BufferedImage(W, Hout, BufferedImage.TYPE_INT_RGB);
        int[] rowT = new int[W], rowB = new int[W], rowO = new int[W];
        // output column x shows top column x + shift and bottom column x + shift + dx
        int tOut0 = Math.max(0, -shiftLeftPx), tSrc0 = tOut0 + shiftLeftPx;
        int tLen = Math.min(W - tOut0, top.getWidth() - tSrc0);
        int dxEff = a.dx + shiftLeftPx;
        int xo0 = Math.max(0, -dxEff), c0 = xo0 + dxEff;
        int bLen = Math.min(W - xo0, bottom.getWidth() - c0);
        for (int y = 0; y < Hout; y++) {
            double wB;
            if (blend > 0) wB = Math.max(0.0, Math.min(1.0, (y - (seam - blend / 2.0) + 0.5) / blend));
            else wB = y >= seam ? 1.0 : 0.0;
            boolean useT = wB < 1.0 && y < Ht, useB = wB > 0.0 && y >= bottomTop && y - bottomTop < Hb;
            if (!useT && !useB) { Arrays.fill(rowO, 0xFFFFFF); out.setRGB(0, y, W, 1, rowO, 0, W); continue; }
            if (useT) { Arrays.fill(rowT, 0xFFFFFF); if (tLen > 0) top.getRGB(tSrc0, y, tLen, 1, rowT, tOut0, W); }
            if (useB) {
                Arrays.fill(rowB, 0xFFFFFF);
                if (bLen > 0) bottom.getRGB(c0, y - bottomTop, bLen, 1, rowB, xo0, W);
                if (gain != 1.0f) {
                    for (int x = xo0; x < xo0 + Math.max(0, bLen); x++) rowB[x] = scale(rowB[x], gain);
                }
            }
            if (useT && useB) {
                for (int x = 0; x < W; x++) rowO[x] = mix(rowT[x], rowB[x], wB);
            } else if (useT) {
                System.arraycopy(rowT, 0, rowO, 0, W);
            } else {
                System.arraycopy(rowB, 0, rowO, 0, W);
            }
            out.setRGB(0, y, W, 1, rowO, 0, W);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static int scale(int rgb, float g) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 255) * g));
        int gr = Math.min(255, Math.round(((rgb >> 8) & 255) * g));
        int b = Math.min(255, Math.round((rgb & 255) * g));
        return (r << 16) | (gr << 8) | b;
    }

    private static int mix(int t, int b, double wB) {
        double wT = 1.0 - wB;
        int r = (int) Math.round(((t >> 16) & 255) * wT + ((b >> 16) & 255) * wB);
        int g = (int) Math.round(((t >> 8) & 255) * wT + ((b >> 8) & 255) * wB);
        int bl = (int) Math.round((t & 255) * wT + (b & 255) * wB);
        return (r << 16) | (g << 8) | bl;
    }

    /** 95th-percentile luminance of rows [y0,y1): the "paper white" level of that capture. */
    private static double paperWhite(BufferedImage img, int y0, int y1) {
        y0 = Math.max(0, y0); y1 = Math.min(img.getHeight(), y1);
        if (y1 - y0 < 4) return 0;
        int w = img.getWidth(), xs = (int) (w * 0.10), xe = (int) (w * 0.90);
        if (xe - xs < 8) return 0;
        int[] hist = new int[256], buf = new int[xe - xs];
        long n = 0;
        for (int y = y0; y < y1; y += 3) {
            img.getRGB(xs, y, xe - xs, 1, buf, 0, xe - xs);
            for (int i = 0; i < buf.length; i += 3) {
                int p = buf[i];
                hist[((p >> 16 & 255) * 30 + (p >> 8 & 255) * 59 + (p & 255) * 11) / 100]++;
                n++;
            }
        }
        long target = (long) (n * 0.95), acc = 0;
        for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc >= target) return v; }
        return 255;
    }

    /** Row in [lo,hi] near centre whose neighbourhood has the least ink, so the join cuts blank paper. */
    private static int quietSeam(BufferedImage top, int lo, int hi, int center, int win, int range) {
        int a = Math.max(lo, center - range), b = Math.min(hi, center + range);
        b = Math.min(b, top.getHeight() - win);
        a = Math.max(a, win);
        if (b < a) return Math.max(lo, Math.min(hi, center));
        int r0 = a - win, r1 = b + win;
        int w = top.getWidth();
        double[] prefix = new double[r1 - r0 + 1];
        int[] buf = new int[w];
        for (int r = r0; r < r1; r++) {
            top.getRGB(0, r, w, 1, buf, 0, w);
            double ink = 0;
            for (int x = 0; x < w; x += 2) {
                int p = buf[x];
                ink += 255 - ((p >> 16 & 255) * 30 + (p >> 8 & 255) * 59 + (p & 255) * 11) / 100;
            }
            prefix[r - r0 + 1] = prefix[r - r0] + ink;
        }
        int best = Math.max(a, Math.min(b, center));
        double bestCost = Double.MAX_VALUE;
        for (int s = a; s <= b; s++) {
            double cost = prefix[s + win - r0] - prefix[s - win - r0] + Math.abs(s - center) * 1e-3;
            if (cost < bestCost) { bestCost = cost; best = s; }
        }
        return best;
    }

    private static BufferedImage rotateKeepSize(BufferedImage src, double degrees) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.rotate(Math.toRadians(degrees), src.getWidth() / 2.0, src.getHeight() / 2.0);
            g.drawImage(src, 0, 0, null);
        } finally { g.dispose(); }
        return out;
    }

    /** Pick the most textured band (largest std-dev) of height bandH with top row in [lo, hi-bandH]. */
    private static int chooseBand(BufferedImage top, int lo, int hi, int bandH, int x0, int w) {
        int best = -1; double bestStd = MIN_TEXTURE_STD;
        int step = Math.max(8, bandH / 2);
        for (int t0 = lo; t0 + bandH <= hi; t0 += step) {
            Gray g = grayRegion(top, x0, t0, w, bandH, 8);
            if (g == null) continue;
            double mean = 0; for (int v : g.v) mean += v; mean /= g.v.length;
            double var = 0; for (int v : g.v) var += (v - mean) * (v - mean);
            double std = Math.sqrt(var / g.v.length);
            if (std > bestStd) { bestStd = std; best = t0; }
        }
        return best;
    }

    /** Small luminance raster; (ox,oy) is its origin in full-resolution pixels of the source image. */
    private static final class Gray {
        final int w, h; final int[] v; int ox, oy;
        Gray(int w, int h) { this.w = w; this.h = h; this.v = new int[w * h]; }
    }

    /** Box-averaged luminance of a region, clipped to the image; null if nothing remains. */
    private static Gray grayRegion(BufferedImage img, int x, int y, int w, int h, int k) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + w), y1 = Math.min(img.getHeight(), y + h);
        int cw = (x1 - x0) / k, ch = (y1 - y0) / k;
        if (cw < 1 || ch < 1) return null;
        Gray g = new Gray(cw, ch); g.ox = x0; g.oy = y0;
        int span = cw * k;
        int[] buf = new int[span], acc = new int[cw];
        for (int oy = 0; oy < ch; oy++) {
            Arrays.fill(acc, 0);
            for (int ky = 0; ky < k; ky++) {
                img.getRGB(x0, y0 + oy * k + ky, span, 1, buf, 0, span);
                for (int i = 0; i < span; i++) {
                    int p = buf[i];
                    acc[i / k] += ((p >> 16 & 255) * 30 + (p >> 8 & 255) * 59 + (p & 255) * 11) / 100;
                }
            }
            for (int i = 0; i < cw; i++) g.v[oy * cw + i] = acc[i] / (k * k);
        }
        return g;
    }

    /** Normalised cross-correlation of template t over every position in s: {i, j, score} or null if t is flat. */
    private static double[] ncc(Gray t, Gray s) {
        if (s.w < t.w || s.h < t.h) return null;
        int n = t.w * t.h;
        double mean = 0; for (int v : t.v) mean += v; mean /= n;
        double[] tz = new double[n]; double tn = 0;
        for (int i = 0; i < n; i++) { double d = t.v[i] - mean; tz[i] = d; tn += d * d; }
        if (Math.sqrt(tn / n) < MIN_TEXTURE_STD) return null;
        tn = Math.sqrt(tn);
        double best = -2; int bi = 0, bj = 0;
        for (int j = 0; j + t.h <= s.h; j++) {
            for (int i = 0; i + t.w <= s.w; i++) {
                double sw = 0, sww = 0, dot = 0;
                for (int r = 0; r < t.h; r++) {
                    int so = (j + r) * s.w + i, to = r * t.w;
                    for (int c = 0; c < t.w; c++) {
                        double w = s.v[so + c];
                        sw += w; sww += w * w; dot += tz[to + c] * w;
                    }
                }
                double var = sww - sw * sw / n;
                if (var < 1e-6) continue;
                double sc = dot / (tn * Math.sqrt(var));
                if (sc > best) { best = sc; bi = i; bj = j; }
            }
        }
        return best < -1 ? null : new double[]{bi, bj, best};
    }

    /**
     * Find the band (tx,ty,tw,th) of the top image inside the bottom image near (ex,ey)
     * within +-rx/+-ry pixels. Coarse (1/4 size) search then full-resolution refinement.
     * Returns {bottomX, bottomY, score} in full-resolution bottom pixels, or null.
     */
    private static double[] matchBand(BufferedImage top, BufferedImage bot, int tx, int ty, int tw, int th,
                                      int ex, int ey, int rx, int ry) {
        int cx = ex, cy = ey;
        if (Math.max(rx, ry) > 10) {
            int k = 4;
            Gray t = grayRegion(top, tx, ty, tw, th, k);
            Gray s = grayRegion(bot, ex - rx, ey - ry, tw + 2 * rx, th + 2 * ry, k);
            if (t == null || s == null) return null;
            double[] r = ncc(t, s);
            if (r == null) return null;
            cx = s.ox + (int) r[0] * k; cy = s.oy + (int) r[1] * k;
            rx = ry = k + 1;
        }
        Gray t = grayRegion(top, tx, ty, tw, th, 1);
        Gray s = grayRegion(bot, cx - rx, cy - ry, tw + 2 * rx, th + 2 * ry, 1);
        if (t == null || s == null) return null;
        double[] r = ncc(t, s);
        if (r == null) return null;
        return new double[]{s.ox + r[0], s.oy + r[1], r[2]};
    }
}
