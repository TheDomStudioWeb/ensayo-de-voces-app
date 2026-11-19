package com.thedom.ensayo;

/**
 * Motor BS-RoFormer (voz masculina / voz femenina). El modelo ONNX recibe la STFT
 * [1, 801, 4100] en orden t, (frecuencia, canal, re/im) y devuelve dos máscaras complejas
 * [1, 2, 801, 4100]. La STFT, la aplicación de la máscara y la iSTFT se hacen acá.
 * Bloques de 8 s; entre bloque y bloque se superponen 2 s con fundido lineal.
 */
public final class RoformerEngine {
    public static final int N_FFT = 2048, HOP = 441, CHUNK = 352800, OVERLAP = 88200;
    public static final int FRAMES = 1 + CHUNK / HOP;           // 801
    public static final int BINS = N_FFT / 2 + 1;               // 1025
    public static final int FEAT = BINS * 4;                    // 4100

    public static void separate(Engine.Model model, float[] l, float[] r, int n,
                                Engine.WavWriter stem0, Engine.WavWriter stem1, Engine.Progress pg) throws Exception {
        Dsp dsp = new Dsp(N_FFT, HOP, FRAMES);
        final int T = FRAMES, S = CHUNK - OVERLAP;
        int count = 0;
        for (int p = 0; p < Math.max(n - OVERLAP, 1); p += S) count++;

        float[] cl = new float[CHUNK], cr = new float[CHUNK];
        float[] spec = new float[4 * BINS * T];          // orden de Dsp: ((s*2+c)*BINS + f)*T + t
        float[] x = new float[T * FEAT];                 // orden del modelo: t*FEAT + (f*2+s)*2 + c
        float[] mask = new float[2 * T * FEAT];
        float[] est = new float[spec.length];
        float[] ol = new float[CHUNK], or = new float[CHUNK];
        float[][] tail = new float[4][OVERLAP];          // stem*2 + canal: fundido de salida del bloque anterior
        float[] fade = new float[OVERLAP];
        for (int i = 0; i < OVERLAP; i++) fade[i] = OVERLAP == 1 ? 1f : i / (float) (OVERLAP - 1);
        short[] buf0 = new short[CHUNK * 2], buf1 = new short[CHUNK * 2];

        int k = 0;
        for (int p = 0; p < Math.max(n - OVERLAP, 1); p += S, k++) {
            if (pg != null && pg.cancelled()) throw new InterruptedException("cancelado");
            boolean first = k == 0, last = k == count - 1;
            int len = Math.min(CHUNK, n - p);
            for (int i = 0; i < CHUNK; i++) {
                cl[i] = i < len ? l[p + i] : 0f;
                cr[i] = i < len ? r[p + i] : 0f;
            }
            dsp.stft(cl, cr, 0, spec, BINS);
            for (int s = 0; s < 2; s++) for (int c = 0; c < 2; c++) for (int f = 0; f < BINS; f++) {
                int src = ((s * 2 + c) * BINS + f) * T, dst = (f * 2 + s) * 2 + c;
                for (int t = 0; t < T; t++) x[t * FEAT + dst] = spec[src + t];
            }
            model.run(x, mask);
            int write = last ? len : S;                  // muestras que quedan definitivas en este bloque
            for (int st = 0; st < 2; st++) {
                int mo = st * T * FEAT;
                for (int s = 0; s < 2; s++) for (int f = 0; f < BINS; f++) {
                    int re = ((s * 2) * BINS + f) * T, im = ((s * 2 + 1) * BINS + f) * T;
                    int mi = (f * 2 + s) * 2;
                    for (int t = 0; t < T; t++) {
                        float xr = spec[re + t], xi = spec[im + t];
                        float mr = mask[mo + t * FEAT + mi], mm = mask[mo + t * FEAT + mi + 1];
                        est[re + t] = xr * mr - xi * mm;
                        est[im + t] = xr * mm + xi * mr;
                    }
                }
                dsp.istft(est, BINS, ol, or);
                short[] buf = st == 0 ? buf0 : buf1;
                float[] tl = tail[st * 2], tr = tail[st * 2 + 1];
                for (int i = 0; i < write; i++) {
                    float w = 1f;
                    if (!first && i < OVERLAP) w = fade[i];
                    float a = ol[i] * w, b = or[i] * w;
                    if (!first && i < OVERLAP) { a += tl[i]; b += tr[i]; }
                    buf[2 * i] = Engine.toShort(a);
                    buf[2 * i + 1] = Engine.toShort(b);
                }
                if (!last) {
                    for (int i = 0; i < OVERLAP; i++) {
                        float w = 1f - fade[i];
                        tl[i] = ol[S + i] * w;
                        tr[i] = or[S + i] * w;
                    }
                }
                (st == 0 ? stem0 : stem1).write(buf, write * 2);
            }
            if (pg != null) pg.on((k + 1) / (float) count);
        }
    }
}
