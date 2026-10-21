package pa;

// Fixed-point Q8.8 + BAM (Binary Angle Measurement, 0..255).
public class Fx {
    public static final int ONE = 256;       // 1.0
    public static final int FQ  = 64 * ONE;  // focal length, 64 logical px (FOV ~90 deg)

    private static final short[] SIN = new short[64]; // 0..90 deg, Q8.8
    static {
        for (int i = 0; i < 64; i++) {
            SIN[i] = (short)(Math.sin(i * Math.PI / 128.0) * ONE + 0.5);
        }
    }

    // BAM sine: 4 quadrants, one 64-entry LUT. 256 units = full circle.
    public static int sin(int a) {
        a &= 255;
        int q = a >> 6;
        int i = a & 63;
        int v = (q == 1 || q == 3) ? SIN[63 - i] : SIN[i];
        return q >= 2 ? -v : v;
    }

    public static int cos(int a) { return sin(a + 64); } // cos(x) = sin(x + pi/2)

    public static int mul(int a, int b) { return (a * b) >> 8; }
}
