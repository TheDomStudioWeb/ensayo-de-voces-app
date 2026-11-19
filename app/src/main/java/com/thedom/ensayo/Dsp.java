package com.thedom.ensayo;

/**
 * STFT / iSTFT estéreo equivalentes a torch.stft(center=True, reflect, ventana hann periódica),
 * que es lo que esperan los modelos MDX-Net. Usa Bluestein para tamaños que no son potencia de 2
 * (7680, 5120) y procesa los dos canales juntos en una sola FFT compleja.
 */
public final class Dsp {
    public final int n, half, hop, frames, length;
    private final int m, logM;
    private final float[] win;
    private final double[] wRe, wIm;   // chirp w_k = exp(-i*pi*k^2/n)
    private final double[] bRe, bIm;   // FFT del chirp conjugado
    private final double[] cos, sin;   // tablas radix-2
    private final double[] aRe, aIm;   // trabajo
    private final double[] zRe, zIm;   // resultado DFT de largo n
    private final float[] winSq;       // suma de ventanas al cuadrado (para la iSTFT)

    /** length = muestras del bloque (hop * (frames - 1)). */
    public Dsp(int n, int hop, int frames) {
        this.n = n; this.half = n / 2 + 1; this.hop = hop; this.frames = frames;
        this.length = hop * (frames - 1);
        int mm = 1, lg = 0;
        while (mm < 2 * n - 1) { mm <<= 1; lg++; }
        m = mm; logM = lg;
        win = new float[n];
        for (int i = 0; i < n; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        wRe = new double[n]; wIm = new double[n];
        for (int k = 0; k < n; k++) {
            long kk = ((long) k * k) % (2L * n);
            double a = Math.PI * kk / n;
            wRe[k] = Math.cos(a); wIm[k] = -Math.sin(a);
        }
        cos = new double[m / 2]; sin = new double[m / 2];
        for (int i = 0; i < m / 2; i++) { cos[i] = Math.cos(2 * Math.PI * i / m); sin[i] = Math.sin(2 * Math.PI * i / m); }
        bRe = new double[m]; bIm = new double[m];
        bRe[0] = wRe[0]; bIm[0] = -wIm[0];
        for (int k = 1; k < n; k++) {
            bRe[k] = wRe[k]; bIm[k] = -wIm[k];
            bRe[m - k] = wRe[k]; bIm[m - k] = -wIm[k];
        }
        fft(bRe, bIm, false);
        aRe = new double[m]; aIm = new double[m];
        zRe = new double[n]; zIm = new double[n];
        // Normalización de la iSTFT (constante para todos los bloques del mismo tamaño)
        int total = n + hop * (frames - 1);
        float[] ws = new float[total];
        for (int t = 0; t < frames; t++) for (int i = 0; i < n; i++) ws[t * hop + i] += win[i] * win[i];
        winSq = ws;
    }

    // ---------- FFT radix-2 en el lugar ----------
    private void fft(double[] re, double[] im, boolean inverse) {
        for (int i = 1, j = 0; i < m; i++) {
            int bit = m >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= m; len <<= 1) {
            int step = m / len, h = len >> 1;
            for (int i = 0; i < m; i += len) {
                for (int k = 0; k < h; k++) {
                    double c = cos[k * step], s = inverse ? sin[k * step] : -sin[k * step];
                    int p = i + k, q = p + h;
                    double xr = re[q] * c - im[q] * s, xi = re[q] * s + im[q] * c;
                    re[q] = re[p] - xr; im[q] = im[p] - xi;
                    re[p] += xr; im[p] += xi;
                }
            }
        }
    }

    /** DFT directa (signo negativo, como numpy) de largo n sobre (inRe, inIm) -> (zRe, zIm). */
    private void dft(double[] inRe, double[] inIm) {
        java.util.Arrays.fill(aRe, 0); java.util.Arrays.fill(aIm, 0);
        for (int k = 0; k < n; k++) {
            aRe[k] = inRe[k] * wRe[k] - inIm[k] * wIm[k];
            aIm[k] = inRe[k] * wIm[k] + inIm[k] * wRe[k];
        }
        fft(aRe, aIm, false);
        for (int k = 0; k < m; k++) {
            double r = aRe[k] * bRe[k] - aIm[k] * bIm[k];
            double i = aRe[k] * bIm[k] + aIm[k] * bRe[k];
            aRe[k] = r; aIm[k] = i;
        }
        fft(aRe, aIm, true);
        for (int k = 0; k < n; k++) {
            double r = aRe[k] / m, i = aIm[k] / m;
            zRe[k] = r * wRe[k] - i * wIm[k];
            zIm[k] = r * wIm[k] + i * wRe[k];
        }
    }

    /**
     * STFT de un bloque estéreo (l, r desde off, largo `length`) hacia el tensor del modelo:
     * out[((c*2+ri)*dimF + f)*frames + t]. Solo se guardan las primeras dimF frecuencias.
     */
    public void stft(float[] l, float[] r, int off, float[] out, int dimF) {
        double[] xr = new double[n], xi = new double[n];
        int p = n / 2;
        for (int t = 0; t < frames; t++) {
            int start = t * hop - p; // índice en el bloque sin relleno
            for (int i = 0; i < n; i++) {
                int j = reflect(start + i);
                xr[i] = l[off + j] * win[i];
                xi[i] = r[off + j] * win[i];
            }
            dft(xr, xi);
            for (int f = 0; f < dimF; f++) {
                int g = (n - f) % n;
                // separa los dos canales reales de la FFT compleja
                double lr = (zRe[f] + zRe[g]) / 2, li = (zIm[f] - zIm[g]) / 2;
                double rr = (zIm[f] + zIm[g]) / 2, ri = (zRe[g] - zRe[f]) / 2;
                out[((0) * dimF + f) * frames + t] = (float) lr;
                out[((1) * dimF + f) * frames + t] = (float) li;
                out[((2) * dimF + f) * frames + t] = (float) rr;
                out[((3) * dimF + f) * frames + t] = (float) ri;
            }
        }
    }

    private int reflect(int j) {
        if (j < 0) return -j;
        if (j >= length) return 2 * (length - 1) - j;
        return j;
    }

    /**
     * iSTFT del tensor del modelo (mismo orden que stft) hacia ol / or_ (largo `length`).
     * Las frecuencias desde dimF hasta n/2 se toman como cero.
     */
    public void istft(float[] in, int dimF, float[] ol, float[] or_) {
        int total = n + hop * (frames - 1);
        float[] accL = new float[total], accR = new float[total];
        double[] sr = new double[n], si = new double[n];
        for (int t = 0; t < frames; t++) {
            java.util.Arrays.fill(sr, 0); java.util.Arrays.fill(si, 0);
            // espectro Z = L + i*R con simetría hermítica de cada canal
            for (int f = 0; f < dimF && f < half; f++) {
                double lr = in[((0) * dimF + f) * frames + t], li = in[((1) * dimF + f) * frames + t];
                double rr = in[((2) * dimF + f) * frames + t], ri = in[((3) * dimF + f) * frames + t];
                if (f == 0 || f == n / 2) { li = 0; ri = 0; }
                // Z_f = L_f + i R_f
                sr[f] = lr - ri; si[f] = li + rr;
                if (f != 0 && f != n - f) {
                    int g = n - f; // Z_g = conj(L_f) + i conj(R_f)
                    sr[g] = lr + ri; si[g] = -li + rr;
                }
            }
            // inversa: x = conj(DFT(conj(Z))) / n
            for (int k = 0; k < n; k++) si[k] = -si[k];
            dft(sr, si);
            int base = t * hop;
            for (int i = 0; i < n; i++) {
                accL[base + i] += (float) (zRe[i] / n) * win[i];
                accR[base + i] += (float) (-zIm[i] / n) * win[i];
            }
        }
        int p = n / 2;
        for (int i = 0; i < length; i++) {
            float w = Math.max(winSq[p + i], 1e-8f);
            ol[i] = accL[p + i] / w;
            or_[i] = accR[p + i] / w;
        }
    }
}
