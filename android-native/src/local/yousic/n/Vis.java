package local.yousic.n;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * The music's spectrum for the visualizer: Android's Visualizer on the player's audio session (Android asks for
 * the microphone permission for this, though only the app's own output is read). The FFT arrives ~20 times a
 * second; {@link #bands} is read by the view each frame, which eases between captures.
 */
public final class Vis {
    public static final int N = 28;
    /** the latest band levels, 0..1 (written on the capture thread) */
    public final float[] target = new float[N];
    private Visualizer v;
    private final int session;

    Vis(int session) { this.session = session; }

    public boolean running() { return v != null; }

    public boolean start() {
        if (v != null) return true;
        try {
            v = new Visualizer(session);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(range[1], 1024));
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer vis, byte[] w, int rate) { }
                @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) { bands(fft, rate); }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            return true;
        } catch (Exception e) {
            Log.w(App.TAG, "visualizer", e);
            stop();
            return false;
        }
    }

    public void stop() {
        if (v != null) { try { v.setEnabled(false); v.release(); } catch (Exception ignored) { } v = null; }
        java.util.Arrays.fill(target, 0);
    }

    /** log-spaced bands from 40 Hz to 16 kHz, in dB scaled to 0..1 */
    private void bands(byte[] fft, int rateMilliHz) {
        int n = fft.length / 2;
        float sr = 44100f;
        try { sr = v.getSamplingRate() / 1000f; } catch (Exception ignored) { }
        float binHz = sr / 2 / n;
        for (int b = 0; b < N; b++) {
            float f0 = (float) (40 * Math.pow(16000 / 40.0, b / (double) N)), f1 = (float) (40 * Math.pow(16000 / 40.0, (b + 1) / (double) N));
            int i0 = Math.max(1, (int) (f0 / binHz)), i1 = Math.max(i0 + 1, Math.min(n, (int) (f1 / binHz)));
            float sum = 0;
            for (int i = i0; i < i1; i++) {
                float re = fft[2 * i], im = fft[2 * i + 1];
                sum = Math.max(sum, re * re + im * im);
            }
            float db = (float) (10 * Math.log10(sum + 1e-6));
            target[b] = Math.max(0, Math.min(1, (db - 10) / 34f));
        }
    }
}
