package local.yousic.n;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

/**
 * The equalizer and tone: Android's DynamicsProcessing on the player's audio session (every song joins it):
 * a 10-band graphic EQ (31 Hz to 16 kHz), a preamp, bass and treble folded into the low and high bands, and a
 * limiter so boosts never clip. Settings are kept between runs.
 */
public final class Effects {
    public static final int BANDS = 10;
    public static final float[] CENTRES = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
    public static final String[] LABELS = { "31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K" };
    /** each band reaches up to the point halfway (in octaves) to the next */
    private static final float[] CUTOFF = { 44, 88, 177, 354, 707, 1414, 2828, 5657, 11314, 20000 };
    public static final float MAX_DB = 12;

    public static final String[] PRESETS = { "Flat", "Bass boost", "Bass & treble", "Treble boost", "Vocal", "Rock", "Pop", "Electronic", "Jazz", "Classical", "Loudness" };
    private static final float[][] PRESET_DB = {
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 6, 5, 4, 2, 0, 0, 0, 0, 0, 0 },
            { 5, 4, 2, 0, -1, -1, 0, 2, 4, 5 },
            { 0, 0, 0, 0, 0, 0, 2, 4, 5, 6 },
            { -2, -2, -1, 1, 3, 4, 3, 1, 0, -1 },
            { 4, 3, 1, -1, -2, -1, 1, 3, 4, 4 },
            { -1, 0, 2, 3, 4, 3, 1, 0, -1, -1 },
            { 5, 4, 1, 0, -2, 0, 1, 2, 4, 5 },
            { 3, 2, 1, 2, -1, -1, 0, 1, 2, 3 },
            { 4, 3, 2, 1, -1, -1, 0, 2, 3, 4 },
            { 6, 4, 1, 0, -1, 0, 0, 1, 4, 5 },
    };

    private final SharedPreferences prefs;
    private final int session;
    private DynamicsProcessing dp;
    public boolean eqOn, toneOn, limiterOn;
    public final float[] bands = new float[BANDS];
    public float preamp, bass, treble;
    public int preset = -1;

    Effects(Context c, int session) {
        this.session = session;
        prefs = c.getSharedPreferences("effects", Context.MODE_PRIVATE);
        eqOn = prefs.getBoolean("eq", false);
        toneOn = prefs.getBoolean("tone", false);
        limiterOn = prefs.getBoolean("limiter", true);
        for (int i = 0; i < BANDS; i++) bands[i] = prefs.getFloat("b" + i, 0);
        preamp = prefs.getFloat("preamp", 0);
        bass = prefs.getFloat("bass", 0);
        treble = prefs.getFloat("treble", 0);
        preset = prefs.getInt("preset", 0);
        apply();
    }

    public static float[] preset(int i) { return PRESET_DB[i]; }

    public void usePreset(int i) {
        System.arraycopy(PRESET_DB[i], 0, bands, 0, BANDS);
        preset = i;
        eqOn = true;
        apply();
    }

    /** Pushes the settings to the audio session (and saves them). */
    public void apply() {
        SharedPreferences.Editor e = prefs.edit().putBoolean("eq", eqOn).putBoolean("tone", toneOn).putBoolean("limiter", limiterOn)
                .putFloat("preamp", preamp).putFloat("bass", bass).putFloat("treble", treble).putInt("preset", preset);
        for (int i = 0; i < BANDS; i++) e.putFloat("b" + i, bands[i]);
        e.apply();
        // nothing switched on: no processing at all (the limiter only matters with boosts)
        boolean any = eqOn || toneOn;
        try {
            if (!any) { if (dp != null) dp.setEnabled(false); return; }
            if (dp == null) {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, true, BANDS, false, 0, false, 0, true).setPreferredFrameDuration(10).build();
                dp = new DynamicsProcessing(0, session, cfg);
            }
            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, eqOn || toneOn, BANDS);
            for (int i = 0; i < BANDS; i++) eq.setBand(i, new DynamicsProcessing.EqBand(true, CUTOFF[i], gain(i)));
            dp.setPreEqAllChannelsTo(eq);
            dp.setInputGainAllChannelsTo(eqOn ? preamp : 0);
            dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, limiterOn, 0, 1, 60, 10, -1, 0));
            dp.setEnabled(true);
        } catch (Exception ex) {
            Log.w(App.TAG, "effects", ex);
        }
    }

    /** a band's gain: the EQ curve plus the tone shelves */
    public float gain(int i) {
        float g = eqOn ? bands[i] : 0;
        if (toneOn) {
            float[] lo = { 1f, 0.8f, 0.45f, 0.15f }, hi = { 0.15f, 0.45f, 0.8f, 1f };
            if (i < 4) g += bass * lo[i];
            if (i >= 6) g += treble * hi[i - 6];
        }
        return g;
    }
}
