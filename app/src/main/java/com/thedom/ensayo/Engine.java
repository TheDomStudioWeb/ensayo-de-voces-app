package com.thedom.ensayo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Motor de separación MDX-Net. Recorre la canción en bloques (~5,7 s), arma la STFT,
 * la pasa por el modelo y escribe dos pistas: la principal (lo que el modelo aísla)
 * y el resto (mezcla menos principal). Las pistas se escriben de a bloques para no
 * tener la canción entera duplicada en memoria.
 */
public final class Engine {
    public static final int SR = 44100, HOP = 1024, DIM_T = 256;

    public interface Model { void run(float[] in, float[] out) throws Exception; }
    public interface Progress { void on(float p); boolean cancelled(); }

    public static void separate(Model model, int nFft, int dimF, boolean hq,
                                float[] l, float[] r, int n,
                                WavWriter primary, WavWriter rest, Progress pg) throws Exception {
        Dsp dsp = new Dsp(nFft, HOP, DIM_T);
        int chunk = dsp.length, trim = nFft / 2, gen = chunk - 2 * trim;
        int pad = gen - n % gen;
        int total = (n + pad) / gen;
        float[] cl = new float[chunk], cr = new float[chunk];
        float[] spec = new float[4 * dimF * DIM_T], out = new float[spec.length], out2 = hq ? new float[spec.length] : null;
        float[] ol = new float[chunk], or = new float[chunk];
        short[] bufP = new short[gen * 2], bufR = new short[gen * 2];
        int done = 0;
        for (int i = 0; i < n + pad; i += gen) {
            if (pg != null && pg.cancelled()) throw new InterruptedException("cancelado");
            int src = i - trim; // posición en la canción del primer elemento del bloque
            for (int k = 0; k < chunk; k++) {
                int j = src + k;
                boolean in = j >= 0 && j < n;
                cl[k] = in ? l[j] : 0f;
                cr[k] = in ? r[j] : 0f;
            }
            dsp.stft(cl, cr, 0, spec, dimF);
            model.run(spec, out);
            if (hq) { // truco de UVR: segunda pasada con la señal invertida y promedio
                for (int k = 0; k < spec.length; k++) spec[k] = -spec[k];
                model.run(spec, out2);
                for (int k = 0; k < out.length; k++) out[k] = (out[k] - out2[k]) * 0.5f;
            }
            dsp.istft(out, dimF, ol, or);
            int count = Math.min(gen, n - i);
            if (count <= 0) break;
            for (int k = 0; k < count; k++) {
                float pl = ol[trim + k], pr = or[trim + k];
                bufP[2 * k] = toShort(pl); bufP[2 * k + 1] = toShort(pr);
                bufR[2 * k] = toShort(l[i + k] - pl); bufR[2 * k + 1] = toShort(r[i + k] - pr);
            }
            primary.write(bufP, count * 2);
            rest.write(bufR, count * 2);
            done++;
            if (pg != null) pg.on(Math.min(1f, done / (float) total));
        }
    }

    static short toShort(float v) {
        float x = v * 32767f;
        if (x > 32767f) x = 32767f; else if (x < -32768f) x = -32768f;
        return (short) Math.round(x);
    }

    /** WAV 16 bits estéreo 44,1 kHz, escrito de a partes. */
    public static final class WavWriter implements AutoCloseable {
        private final File file;
        private final BufferedOutputStream os;
        private long dataBytes = 0;
        private final byte[] tmp = new byte[1 << 16];

        public WavWriter(File f) throws IOException {
            file = f;
            os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
            os.write(new byte[44]);
        }

        public void write(short[] s, int len) throws IOException {
            int p = 0;
            while (p < len) {
                int m = Math.min(len - p, tmp.length / 2);
                for (int k = 0; k < m; k++) { short v = s[p + k]; tmp[2 * k] = (byte) v; tmp[2 * k + 1] = (byte) (v >> 8); }
                os.write(tmp, 0, m * 2);
                p += m;
            }
            dataBytes += len * 2L;
        }

        @Override public void close() throws IOException {
            os.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.write(header(dataBytes));
            }
        }

        static byte[] header(long data) {
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, 36 + data); put(h, 8, "WAVE"); put(h, 12, "fmt ");
            le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 2); le32(h, 24, SR); le32(h, 28, SR * 4L);
            le16(h, 32, 4); le16(h, 34, 16); put(h, 36, "data"); le32(h, 40, data);
            return h;
        }
        static void put(byte[] h, int o, String s) { for (int i = 0; i < 4; i++) h[o + i] = (byte) s.charAt(i); }
        static void le32(byte[] h, int o, long v) { for (int i = 0; i < 4; i++) h[o + i] = (byte) (v >> (8 * i)); }
        static void le16(byte[] h, int o, int v) { h[o] = (byte) v; h[o + 1] = (byte) (v >> 8); }
    }

    /** Lee un WAV propio (16 bits estéreo) a dos canales float. */
    public static float[][] readWav(File f) throws IOException {
        long frames = (f.length() - 44) / 4;
        float[] l = new float[(int) frames], r = new float[(int) frames];
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            long skip = 44; while (skip > 0) skip -= in.skip(skip);
            byte[] b = new byte[1 << 16];
            int idx = 0, carry = 0; byte[] c = new byte[4];
            int got;
            while ((got = in.read(b)) > 0) {
                for (int k = 0; k < got; k++) {
                    c[carry++] = b[k];
                    if (carry == 4) {
                        if (idx < frames) {
                            l[idx] = (short) ((c[0] & 0xff) | (c[1] << 8)) / 32768f;
                            r[idx] = (short) ((c[2] & 0xff) | (c[3] << 8)) / 32768f;
                        }
                        idx++; carry = 0;
                    }
                }
            }
        }
        return new float[][]{l, r};
    }
}
