package com.thedom.ensayo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gráfico tonal: sigue la altura (nota) de una voz ya separada con el método YIN.
 * Devuelve una "línea" cada 40 ms (nota MIDI con decimales, o null si no canta)
 * y las "notas" ya agrupadas [inicio, fin, nota MIDI entera].
 */
public final class Pitch {
    public static final double STEP = 0.04;           // segundos entre puntos
    private static final int DEC = 4;                 // 44,1 kHz / 4 = 11 025 Hz
    private static final double SR = Engine.SR / (double) DEC;
    private static final int W = 512;                 // ventana de integración (~46 ms)
    private static final int TAU_MIN = (int) (SR / 1000), TAU_MAX = (int) (SR / 70);   // 70 Hz a 1 kHz
    private static final double THRESH = 0.15;

    public static final class Result {
        public final float[] line;                   // NaN = no canta
        public final List<float[]> notes = new ArrayList<>();
        Result(float[] line) { this.line = line; }
    }

    public interface Progress { void on(float p); }

    public static Result analyze(float[] l, float[] r, int n, Progress pg) {
        // mono + diezmado con promedio (filtro pasa bajos simple)
        int m = n / DEC;
        float[] x = new float[m];
        double sum2 = 0;
        for (int i = 0; i < m; i++) {
            float s = 0;
            for (int k = 0; k < DEC; k++) { int j = i * DEC + k; s += l[j] + r[j]; }
            x[i] = s / (2 * DEC);
            sum2 += x[i] * x[i];
        }
        double rms = Math.sqrt(sum2 / Math.max(1, m));
        double gate = Math.max(0.006, rms * 0.25);   // por debajo: silencio

        int hop = (int) Math.round(STEP * SR);
        int frames = Math.max(0, (m - W - TAU_MAX - 2) / hop + 1);
        float[] line = new float[(int) Math.ceil(n / (double) Engine.SR / STEP)];
        Arrays.fill(line, Float.NaN);
        double[] d = new double[TAU_MAX + 2];
        for (int f = 0; f < frames; f++) {
            int s0 = f * hop;
            double e = 0;
            for (int i = 0; i < W; i++) e += x[s0 + i] * x[s0 + i];
            if (Math.sqrt(e / W) < gate) continue;
            // función diferencia y normalización acumulada (YIN)
            d[0] = 1;
            double run = 0;
            int best = -1;
            for (int tau = 1; tau <= TAU_MAX + 1; tau++) {
                double acc = 0;
                for (int i = 0; i < W; i++) { double v = x[s0 + i] - x[s0 + i + tau]; acc += v * v; }
                run += acc;
                d[tau] = run > 0 ? acc * tau / run : 1;
            }
            for (int tau = TAU_MIN; tau <= TAU_MAX; tau++) {
                if (d[tau] < THRESH) {
                    while (tau + 1 <= TAU_MAX && d[tau + 1] < d[tau]) tau++;
                    best = tau; break;
                }
            }
            if (best < 0) continue;
            // interpolación parabólica para afinar
            double a = d[best - 1], b = d[best], c = d[best + 1];
            double den = a - 2 * b + c;
            double t = best + (Math.abs(den) > 1e-12 ? 0.5 * (a - c) / den : 0);
            double hz = SR / t;
            double midi = 69 + 12 * Math.log(hz / 440.0) / Math.log(2);
            int idx = (int) Math.round((s0 + W / 2.0) / SR / STEP);
            if (idx < line.length) line[idx] = (float) midi;
            if (pg != null && f % 200 == 0) pg.on(f / (float) frames);
        }
        Result res = new Result(clean(line));
        segment(res);
        return res;
    }

    /** Quita puntos sueltos y saltos de octava aislados (mediana de 5). */
    private static float[] clean(float[] a) {
        float[] o = a.clone();
        float[] w = new float[5];
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i])) continue;
            int k = 0;
            for (int j = i - 2; j <= i + 2; j++) if (j >= 0 && j < a.length && !Float.isNaN(a[j])) w[k++] = a[j];
            if (k < 3) { o[i] = Float.NaN; continue; }   // punto aislado
            float[] s = Arrays.copyOf(w, k); Arrays.sort(s);
            o[i] = s[k / 2];
        }
        return o;
    }

    /** Agrupa la línea en notas: tramos estables de al menos 0,12 s. */
    private static void segment(Result r) {
        float[] a = r.line;
        int i = 0;
        while (i < a.length) {
            if (Float.isNaN(a[i])) { i++; continue; }
            int j = i; double mean = a[i]; int cnt = 1;
            while (j + 1 < a.length && !Float.isNaN(a[j + 1]) && Math.abs(a[j + 1] - mean) < 0.7) {
                j++; cnt++; mean += (a[j] - mean) / cnt;
            }
            if ((j - i + 1) * STEP >= 0.12) {
                float[] seg = Arrays.copyOfRange(a, i, j + 1); Arrays.sort(seg);
                r.notes.add(new float[]{(float) (i * STEP), (float) ((j + 1) * STEP), Math.round(seg[seg.length / 2])});
            }
            i = j + 1;
        }
    }

    public static JSONObject toJson(String file, String label, Result r) throws JSONException {
        JSONArray line = new JSONArray();
        for (float v : r.line) line.put(Float.isNaN(v) ? JSONObject.NULL : (Object) (Math.round(v * 100) / 100.0));
        JSONArray notes = new JSONArray();
        for (float[] nt : r.notes)
            notes.put(new JSONArray().put(Math.round(nt[0] * 100) / 100.0).put(Math.round(nt[1] * 100) / 100.0).put((int) nt[2]));
        return new JSONObject().put("file", file).put("label", label).put("linea", line).put("notas", notes);
    }
}
