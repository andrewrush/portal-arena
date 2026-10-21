package pa;

// Sector-based map with portals (Build-engine style).
// All coordinates are Q8.8 (meters * 256). All sectors are convex.
//
// Layout (meters):
//   S0 main hall (0,0)-(24,24) --- door --- S1 corridor (-8,8)-(0,16) --- door --- S2 chamber (-28,0)-(-8,24)
//   S2 west wall: small door -> S3 "the Box" (40,40)-(42,42)  [non-euclidean!]
//   S3 south wall: TARDIS door -> S4 great hall (40,44)-(56,60), 16x16m
//
// Wall row: {x1, y1, x2, y2, color, portalSector, portalWall, delta, nx, ny, tan}
//   nx,ny = unit normal into the wall's own sector (filled in static init)
//   tan   = wall tangent angle in BAM (0=E, 64=S, 128=W, 192=N)
public class Map {
    public static final int[][] WALLS = {
        // ---- S0: main hall, ceil 6m ----
        {0, 0, 24576, 0,          0x808080, -1, -1, 0, 0, 0, 0}, // 0 N
        {24576, 0, 24576, 24576, 0x708090, -1, -1, 0, 0, 0, 0}, // 1 E
        {24576, 24576, 0, 24576, 0x808080, -1, -1, 0, 0, 0, 0}, // 2 S
        {0, 0, 0, 2048,          0x607080, -1, -1, 0, 0, 0, 0}, // 3 W up
        {0, 2048, 0, 4096,      0x506060, 1, 7, 0, 0, 0, 0},   // 4 portal -> S1
        {0, 4096, 0, 24576,     0x607080, -1, -1, 0, 0, 0, 0}, // 5 W low
        // ---- S1: corridor, ceil 4m ----
        {-2048, 2048, 0, 2048,     0x506050, -1, -1, 0, 0, 0, 0}, // 6
        {0, 2048, 0, 4096,         0x506060, 0, 4, 0, 0, 0, 0},   // 7 portal -> S0
        {-2048, 4096, 0, 4096,     0x506050, -1, -1, 0, 0, 0, 0}, // 8
        {-2048, 2048, -2048, 4096, 0x506060, 2, 12, 0, 0, 0, 0},  // 9 portal -> S2
        // ---- S2: chamber, ceil 8m ----
        {-7168, 0, -2048, 0,          0x605080, -1, -1, 0, 0, 0, 0}, // 10 N
        {-2048, 0, -2048, 2048,      0x605080, -1, -1, 0, 0, 0, 0}, // 11
        {-2048, 2048, -2048, 4096,   0x506060, 1, 9, 0, 0, 0, 0},   // 12 portal -> S1
        {-2048, 4096, -2048, 24576,  0x605080, -1, -1, 0, 0, 0, 0}, // 13
        {-7168, 24576, -2048, 24576, 0x705898, -1, -1, 0, 0, 0, 0}, // 14 S
        {-7168, 0, -7168, 2048,      0x705898, -1, -1, 0, 0, 0, 0}, // 15 W up
        {-7168, 2048, -7168, 2560,   0xC08040, 3, 19, 0, 0, 0, 0},  // 16 portal -> S3 (the Box door)
        {-7168, 2560, -7168, 24576,  0x705898, -1, -1, 0, 0, 0, 0}, // 17 W low
        // ---- S3: the Box, ceil 3m ----
        {10240, 10240, 10752, 10240, 0xB07020, -1, -1, 0, 0, 0, 0}, // 18
        {10752, 10240, 10752, 10752, 0xC08040, 2, 16, 0, 0, 0, 0},  // 19 portal -> S2
        {10240, 10752, 10752, 10752, 0xD0A050, 4, 23, 0, 0, 0, 0},  // 20 portal -> S4 (TARDIS)
        {10240, 10240, 10240, 10752, 0xB07020, -1, -1, 0, 0, 0, 0}, // 21
        // ---- S4: great hall, ceil 12m ----
        {10240, 11264, 11264, 11264, 0x804040, -1, -1, 0, 0, 0, 0}, // 22 N left
        {11264, 11264, 11776, 11264, 0xD0A050, 3, 20, 0, 0, 0, 0},  // 23 portal -> S3 (return)
        {11776, 11264, 14336, 11264, 0x804040, -1, -1, 0, 0, 0, 0}, // 24 N right
        {14336, 11264, 14336, 15360, 0x904838, -1, -1, 0, 0, 0, 0}, // 25 E
        {14336, 15360, 10240, 15360, 0x804040, -1, -1, 0, 0, 0, 0}, // 26 S
        {10240, 15360, 10240, 11264, 0x904838, -1, -1, 0, 0, 0, 0}, // 27 W
    };

    // {floorZ, ceilZ, wallStart, wallCount, ceilCol, floorCol, minX, minY, maxX, maxY}
    public static final int[][] SECTORS = {
        {0, 1536, 0, 6,  0x2A3A55, 0x5A4632, 0, 0, 24576, 24576},          // S0
        {0, 1024, 6, 4,  0x334033, 0x4A4234, -2048, 2048, 0, 4096},        // S1
        {0, 2048, 10, 8, 0x28203A, 0x3E3648, -7168, 0, -2048, 24576},      // S2
        {0, 768,  18, 4, 0x402808, 0x503418, 10240, 10240, 10752, 10752},  // S3
        {0, 3072, 22, 6, 0x180C0C, 0x382020, 10240, 11264, 14336, 15360},  // S4
    };

    static final int[] TMP = new int[3];

    static {
        for (int i = 0; i < WALLS.length; i++) {
            int[] w = WALLS[i];
            int ex = w[2] - w[0], ey = w[3] - w[1];
            int len = isqrt((long) ex * ex + (long) ey * ey);
            if (len == 0) { w[8] = 256; w[9] = 0; w[10] = 0; continue; }
            int nx = (-ey * 256) / len;
            int ny = (ex * 256) / len;
            int[] b = SECTORS[sectorOfWall(i)];
            long mx = (w[0] + w[2]) >> 1, my = (w[1] + w[3]) >> 1;
            long sx = ((long) (b[6] + b[8]) >> 1) - mx;
            long sy = ((long) (b[7] + b[9]) >> 1) - my;
            if ((long) nx * sx + (long) ny * sy < 0) { nx = -nx; ny = -ny; }
            w[8] = nx; w[9] = ny;
            int ax = ex < 0 ? -ex : ex, ay = ey < 0 ? -ey : ey;
            if (ax >= ay) w[10] = ex >= 0 ? 0 : 128;
            else w[10] = ey >= 0 ? 64 : 192;
        }
    }

    static int sectorOfWall(int wi) {
        for (int s = 0; s < SECTORS.length; s++) {
            if (wi >= SECTORS[s][2] && wi < SECTORS[s][2] + SECTORS[s][3]) return s;
        }
        return 0;
    }

    public static int sectorAt(int x, int y) {
        for (int s = 0; s < SECTORS.length; s++) {
            int[] b = SECTORS[s];
            if (x >= b[6] && x <= b[8] && y >= b[7] && y <= b[9]) return s;
        }
        return 0;
    }

    public static int isqrt(long n) {
        if (n <= 0) return 0;
        int x = n < 65536 ? 256 : 4096;
        for (int i = 0; i < 12; i++) x = (int)((x + n / x) >> 1);
        while ((long) x * x > n) x--;
        while ((long)(x + 1) * (x + 1) <= n) x++;
        return x;
    }

    static long cross(long ax, long ay, long bx, long by) {
        return ax * by - ay * bx;
    }

    // Strict crossing of move segment (p->q) with wall w (t > 0 avoids wall-stick).
    static boolean crosses(int px, int py, int qx, int qy, int[] w) {
        int dx = qx - px, dy = qy - py;
        int ex = w[2] - w[0], ey = w[3] - w[1];
        long den = cross(dx, dy, ex, ey);
        if (den == 0) return false;
        long t = cross(w[0] - px, w[1] - py, ex, ey);
        long u = cross(w[0] - px, w[1] - py, dx, dy);
        if (den < 0) { den = -den; t = -t; u = -u; }
        return t > 0 && t < den && u >= 0 && u <= den;
    }

    // res[0..2] = newX, newY, newSector
    public static void portalCross(int[] w, int px, int py, int[] res) {
        int[] x = WALLS[w[6]];
        int ex = x[2] - x[0], ey = x[3] - x[1];
        long len2 = (long) ex * ex + (long) ey * ey;
        long t = 128;
        if (len2 > 0) {
            t = ((long)(px - x[0]) * ex + (long)(py - x[1]) * ey) * 256 / len2;
            if (t < 0) t = 0;
            if (t > 256) t = 256;
        }
        int qx = x[0] + (int)((ex * t) >> 8);
        int qy = x[1] + (int)((ey * t) >> 8);
        qx += (x[8] * 24) >> 8;
        qy += (x[9] * 24) >> 8;
        res[0] = qx; res[1] = qy; res[2] = w[5];
    }

    // 0 = blocked by solid wall, 1 = free move, 2 = teleported through portal (res filled)
    public static int tryMove(int px, int py, int sec, int nx, int ny, int[] res) {
        int[] sb = SECTORS[sec];
        for (int i = 0; i < sb[3]; i++) {
            int[] w = WALLS[sb[2] + i];
            if (crosses(px, py, nx, ny, w)) {
                if (w[5] >= 0) {
                    portalCross(w, nx, ny, res);
                    return 2;
                }
                return 0;
            }
        }
        return 1;
    }

    // Line of sight; rays pass through portals (you can shoot through them).
    public static boolean los(int ox, int oy, int osec, int tx, int ty) {
        int sec = osec;
        for (int hop = 0; hop < 8; hop++) {
            int dx = tx - ox, dy = ty - oy;
            if (dx == 0 && dy == 0) return true;
            int[] sb = SECTORS[sec];
            long bestN = -1, bestD = 1;
            int[] hit = null;
            for (int i = 0; i < sb[3]; i++) {
                int[] w = WALLS[sb[2] + i];
                long den = cross(dx, dy, w[2] - w[0], w[3] - w[1]);
                if (den == 0) continue;
                long tn = cross(w[0] - ox, w[1] - oy, w[2] - w[0], w[3] - w[1]);
                long un = cross(w[0] - ox, w[1] - oy, dx, dy);
                if (den < 0) { den = -den; tn = -tn; un = -un; }
                if (tn > 0 && un >= 0 && un <= den) {
                    if (bestN < 0 || tn * bestD < bestN * den) { bestN = tn; bestD = den; hit = w; }
                }
            }
            if (hit == null) return true;
            if (hit[5] < 0) return false;
            int hx = ox + (int)(((long) dx * bestN) / bestD);
            int hy = oy + (int)(((long) dy * bestN) / bestD);
            portalCross(hit, hx, hy, TMP);
            ox = TMP[0]; oy = TMP[1]; sec = TMP[2];
        }
        return false;
    }
}
