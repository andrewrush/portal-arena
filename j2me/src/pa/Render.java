package pa;

import javax.microedition.lcdui.Graphics;

// Portal-based column renderer: task queue (breadth-first, no recursion),
// per-column clipTop/clipBottom occlusion, z-buffer for sprites.
// Logical frame 120x160, drawn as 240x320 (2x2 pixels per logical pixel).
public class Render {
    public static final int W = 120, H = 160, HZ = 80;
    static final int NEAR = 64;   // 0.25m
    static final int EYE = 410;   // 1.6m

    static final int[] clipT = new int[W];
    static final int[] clipB = new int[W];
    static final int[] zbuf = new int[W];

    static final int[] qSec = new int[24];
    static final int[] qMin = new int[24];
    static final int[] qMax = new int[24];
    static final int[] qX = new int[24];
    static final int[] qY = new int[24];
    static final int[] qA = new int[24];
    static final int[] qD = new int[24];
    static int qH, qT;

    static void add(int s, int mn, int mx, int x, int y, int a, int d) {
        if (qH < 24) {
            qSec[qH] = s; qMin[qH] = mn; qMax[qH] = mx;
            qX[qH] = x; qY[qH] = y; qA[qH] = a; qD[qH] = d;
            qH++;
        }
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static int lerp(int a, int b, int t) { return a + (((b - a) * t) >> 8); }

    static int shade(int col, int depth) {
        int f = depth < 1536 ? 100 : (depth < 3072 ? 74 : (depth < 6144 ? 54 : 38));
        int r = ((col >> 16) & 255) * f / 100;
        int g = ((col >> 8) & 255) * f / 100;
        int b = (col & 255) * f / 100;
        return (r << 16) | (g << 8) | b;
    }

    public static void render(Game G, Graphics g) {
        for (int x = 0; x < W; x++) { clipT[x] = 0; clipB[x] = H - 1; zbuf[x] = 32767; }
        qH = qT = 0;
        add(G.sector, 0, W - 1, G.px, G.py, G.pang, 0);
        int iter = 0;
        while (qT < qH && iter < 24) {
            int i = qT++;
            iter++;
            sector(G, g, qSec[i], qMin[i], qMax[i], qX[i], qY[i], qA[i], qD[i]);
        }
    }

    static void sector(Game G, Graphics g, int sec, int minx, int maxx,
                       int cx, int cy, int ca, int depth) {
        int[] sb = Map.SECTORS[sec];
        int cos = Fx.cos(ca), sin = Fx.sin(ca);
        int ceilZ = sb[1], floorZ = sb[0];
        int ceilCol = sb[4], floorCol = sb[5];

        for (int i = 0; i < sb[3]; i++) {
            int[] w = Map.WALLS[sb[2] + i];
            int d1x = w[0] - cx, d1y = w[1] - cy;
            int d2x = w[2] - cx, d2y = w[3] - cy;

            // to camera space: X = depth (forward), Y = lateral
            int t1x = (d1x * cos + d1y * sin) >> 8;
            int t1y = (d1y * cos - d1x * sin) >> 8;
            int t2x = (d2x * cos + d2y * sin) >> 8;
            int t2y = (d2y * cos - d2x * sin) >> 8;

            if (t1x <= NEAR && t2x <= NEAR) continue;

            if (t1x < NEAR) {
                int tt = ((NEAR - t1x) << 8) / (t2x - t1x);
                t1y = t1y + (((t2y - t1y) * tt) >> 8);
                t1x = NEAR;
            }
            if (t2x < NEAR) {
                int tt = ((NEAR - t2x) << 8) / (t1x - t2x);
                t2y = t2y + (((t1y - t2y) * tt) >> 8);
                t2x = NEAR;
            }

            int sx1 = 60 + Fx.mul(t1y, Fx.FQ) / t1x;
            int sx2 = 60 + Fx.mul(t2y, Fx.FQ) / t2x;
            if (sx1 >= sx2) continue;

            int c1 = sx1 < minx ? minx : sx1;
            int c2 = sx2 > maxx ? maxx : sx2;
            if (c1 > c2) continue;

            int ceil1 = HZ + Fx.mul(EYE - ceilZ, Fx.FQ) / t1x;
            int floor1 = HZ + Fx.mul(EYE - floorZ, Fx.FQ) / t1x;
            int ceil2 = HZ + Fx.mul(EYE - ceilZ, Fx.FQ) / t2x;
            int floor2 = HZ + Fx.mul(EYE - floorZ, Fx.FQ) / t2x;

            if (w[5] >= 0) {
                // Portal: virtual camera = double mirror (across portal line,
                // then across paired wall line). For parallel pairs heading
                // stays unchanged; all our portal pairs are parallel.
                int[] pw = Map.WALLS[w[6]];
                long dp = ((long)(cx - w[0]) * w[8] + (long)(cy - w[1]) * w[9]) >> 8;
                int mx = cx - (int)((2 * dp * w[8]) >> 8);
                int my = cy - (int)((2 * dp * w[9]) >> 8);
                long dq = ((long)(mx - pw[0]) * pw[8] + (long)(my - pw[1]) * pw[9]) >> 8;
                int vx = mx - (int)((2 * dq * pw[8]) >> 8);
                int vy = my - (int)((2 * dq * pw[9]) >> 8);
                int va = (ca + 2 * (pw[10] - w[10])) & 255;
                add(w[5], c1, c2, vx, vy, va, depth + 1);
                continue;
            }

            int col = w[4];
            for (int x = c1; x <= c2; x++) {
                int t = ((x - sx1) << 8) / (sx2 - sx1);
                int ceilY = lerp(ceil1, ceil2, t);
                int floorY = lerp(floor1, floor2, t);
                int ct = clipT[x], cb = clipB[x];

                if (ct < ceilY) {
                    int cc = ceilY < cb ? ceilY : cb;
                    if (cc > ct) {
                        g.setColor(ceilCol);
                        g.fillRect(x * 2, ct * 2, 2, (cc - ct) * 2);
                    }
                }
                int wt = ceilY > ct ? ceilY : ct;
                int wb = floorY < cb ? floorY : cb;
                int z = t1x + (((t2x - t1x) * t) >> 8);
                if (wt < wb) {
                    g.setColor(shade(col, z));
                    g.fillRect(x * 2, wt * 2, 2, (wb - wt) * 2);
                }
                if (floorY < cb) {
                    int ff = floorY > ct ? floorY : ct;
                    if (cb > ff) {
                        g.setColor(floorCol);
                        g.fillRect(x * 2, ff * 2, 2, (cb - ff) * 2);
                    }
                }
                clipT[x] = clamp(ceilY, ct, cb);
                clipB[x] = clamp(floorY, ct, cb);
                if (depth == 0 && z < zbuf[x]) zbuf[x] = z;
            }
        }
    }

    // Billboard sprite of the opponent with per-column z-test.
    public static void sprite(Game G, Graphics g, int wx, int wy, boolean flash) {
        int dx = wx - G.px, dy = wy - G.py;
        int cos = Fx.cos(G.pang), sin = Fx.sin(G.pang);
        int tx = (dx * cos + dy * sin) >> 8;
        int ty = (dy * cos - dx * sin) >> 8;
        if (tx <= NEAR) return;
        int sx = 60 + Fx.mul(ty, Fx.FQ) / tx;
        int w = Fx.mul(48, Fx.FQ) / tx;
        if (w < 1) w = 1;
        int top = HZ + Fx.mul(EYE - 440, Fx.FQ) / tx;
        int bot = HZ + Fx.mul(EYE, Fx.FQ) / tx;
        if (top < 0) top = 0;
        if (bot > H - 1) bot = H - 1;
        int x1 = sx - w, x2 = sx + w;
        if (x1 < 0) x1 = 0;
        if (x2 > W - 1) x2 = W - 1;
        for (int x = x1; x <= x2; x++) {
            if (tx >= zbuf[x]) continue;
            int head = top + ((bot - top) * 18 >> 8);
            int hips = top + ((bot - top) * 60 >> 8);
            g.setColor(flash ? 0xFFFFFF : 0xC03030);
            g.fillRect(x * 2, top * 2, 2, (head - top) * 2);
            g.setColor(flash ? 0xFFFFAA : 0x901818);
            g.fillRect(x * 2, head * 2, 2, (hips - head) * 2);
            g.setColor(0x303040);
            g.fillRect(x * 2, hips * 2, 2, (bot - hips) * 2);
        }
    }
}
