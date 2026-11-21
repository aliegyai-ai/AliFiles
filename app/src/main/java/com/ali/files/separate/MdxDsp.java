package com.ali.files.separate;

/**
 * The signal maths around the MDX-Net model (same as UVR / python-audio-separator):
 * stereo audio chunk → STFT (n_fft 7680, hop 1024, periodic Hann, centred with reflect padding)
 * → model input [1, 4, 3072, 256] = (L re, L im, R re, R im) × frequency × time,
 * and back: model output → inverse STFT (overlap-add) → stereo audio.
 */
public final class MdxDsp {
    public static final int N_FFT = 7680;
    public static final int HOP = 1024;
    public static final int DIM_F = 3072;
    public static final int DIM_T = 256;
    public static final int BINS = N_FFT / 2 + 1;           // 3841
    public static final int CHUNK = HOP * (DIM_T - 1);      // 261120 samples per model run
    public static final int TRIM = N_FFT / 2;               // 3840 samples of context on each side
    public static final int GEN = CHUNK - 2 * TRIM;         // 253440 new samples per run
    public static final int PLANE = DIM_F * DIM_T;          // floats per channel plane

    private final float[] window = new float[N_FFT];
    private final Fft fft = new Fft(N_FFT);
    private final double[] re = new double[N_FFT];
    private final double[] im = new double[N_FFT];
    private final float[] padded = new float[CHUNK + N_FFT];
    private final float[] wsum = new float[CHUNK + N_FFT];

    public MdxDsp() {
        for (int i = 0; i < N_FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N_FFT));
        // squared-window sum for overlap-add normalisation (same for every chunk)
        for (int t = 0; t < DIM_T; t++)
            for (int i = 0; i < N_FFT; i++) wsum[t * HOP + i] += window[i] * window[i];
    }

    /** chunkL/chunkR hold CHUNK samples each. Fills input (length 4*PLANE). */
    public void stft(float[] chunkL, float[] chunkR, float[] input) {
        stftChannel(chunkL, input, 0);
        stftChannel(chunkR, input, 2 * PLANE);
    }

    private void stftChannel(float[] x, float[] out, int base) {
        int pad = N_FFT / 2;
        // reflect padding (torch.stft center=True)
        for (int i = 0; i < CHUNK + 2 * pad; i++) {
            int j = i - pad;
            if (j < 0) j = -j;
            else if (j >= CHUNK) j = 2 * (CHUNK - 1) - j;
            padded[i] = x[j];
        }
        for (int t = 0; t < DIM_T; t++) {
            int off = t * HOP;
            for (int i = 0; i < N_FFT; i++) { re[i] = padded[off + i] * window[i]; im[i] = 0; }
            fft.transform(re, im, false);
            for (int f = 0; f < DIM_F; f++) {
                out[base + f * DIM_T + t] = (float) re[f];
                out[base + PLANE + f * DIM_T + t] = (float) im[f];
            }
        }
    }

    /** output: model result (4*PLANE). Writes CHUNK samples per channel into outL/outR. */
    public void istft(float[] output, float[] outL, float[] outR) {
        istftChannel(output, 0, outL);
        istftChannel(output, 2 * PLANE, outR);
    }

    private void istftChannel(float[] spec, int base, float[] out) {
        int pad = N_FFT / 2;
        java.util.Arrays.fill(padded, 0f);
        for (int t = 0; t < DIM_T; t++) {
            // full Hermitian spectrum: bins >= DIM_F are zero (the model doesn't predict them)
            java.util.Arrays.fill(re, 0); java.util.Arrays.fill(im, 0);
            for (int f = 0; f < DIM_F; f++) {
                re[f] = spec[base + f * DIM_T + t];
                im[f] = spec[base + PLANE + f * DIM_T + t];
            }
            im[0] = 0;
            for (int f = 1; f < BINS - 1; f++) { re[N_FFT - f] = re[f]; im[N_FFT - f] = -im[f]; }
            fft.transform(re, im, true);
            int off = t * HOP;
            for (int i = 0; i < N_FFT; i++) padded[off + i] += (float) (re[i] / N_FFT) * window[i];
        }
        for (int i = 0; i < CHUNK; i++) {
            float w = wsum[i + pad];
            out[i] = w > 1e-8f ? padded[i + pad] / w : 0f;
        }
    }

    /** Mixed-radix (2, 3, 5) complex FFT for a fixed size. */
    static final class Fft {
        final int n;
        final double[] cos, sin;
        final double[] tr, ti;

        Fft(int n) {
            this.n = n;
            cos = new double[n]; sin = new double[n];
            for (int i = 0; i < n; i++) { cos[i] = Math.cos(2 * Math.PI * i / n); sin[i] = Math.sin(2 * Math.PI * i / n); }
            tr = new double[n]; ti = new double[n];
        }

        /** In place. inverse = true gives the unnormalised inverse transform. */
        void transform(double[] re, double[] im, boolean inverse) {
            rec(re, im, 0, 1, n, tr, ti, 0, inverse);
            System.arraycopy(tr, 0, re, 0, n);
            System.arraycopy(ti, 0, im, 0, n);
        }

        // out[outOff .. outOff+len) = DFT of in[inOff], in[inOff+stride], ...
        private void rec(double[] inR, double[] inI, int inOff, int stride, int len, double[] outR, double[] outI, int outOff, boolean inv) {
            if (len == 1) { outR[outOff] = inR[inOff]; outI[outOff] = inI[inOff]; return; }
            int p = (len % 2 == 0) ? 2 : (len % 3 == 0) ? 3 : (len % 5 == 0) ? 5 : len;
            int m = len / p;
            for (int q = 0; q < p; q++) rec(inR, inI, inOff + q * stride, stride * p, m, outR, outI, outOff + q * m, inv);
            // combine p sub-transforms of length m
            int step = n / len; // twiddle step for this level
            for (int k = 0; k < m; k++) {
                for (int q = 0; q < p; q++) {
                    int idx = outOff + q * m + k;
                    int tw = (q * k * step) % n;
                    double c = cos[tw], s = inv ? sin[tw] : -sin[tw];
                    double xr = outR[idx], xi = outI[idx];
                    sr[q] = xr * c - xi * s;
                    si[q] = xr * s + xi * c;
                }
                for (int r = 0; r < p; r++) {
                    double accR = 0, accI = 0;
                    for (int q = 0; q < p; q++) {
                        int tw = ((q * r * m) % len) * step;
                        double c = cos[tw], s = inv ? sin[tw] : -sin[tw];
                        accR += sr[q] * c - si[q] * s;
                        accI += sr[q] * s + si[q] * c;
                    }
                    tmpR[r] = accR; tmpI[r] = accI;
                }
                for (int r = 0; r < p; r++) { outR[outOff + r * m + k] = tmpR[r]; outI[outOff + r * m + k] = tmpI[r]; }
            }
        }
        private final double[] tmpR = new double[5], tmpI = new double[5], sr = new double[5], si = new double[5];
    }
}
