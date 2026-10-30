package local.yousic.n;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's lyrics, as the Windows app finds them (main.js getLyrics): a .lrc (or .txt) file with the same
 * name next to the song, else lyrics embedded in its tags. Android only lets the app read .lrc files once it
 * has "All files access"; embedded lyrics always work.
 * Answers {synced: [{t, text}], source} or {text, source}, or null.
 */
final class Lyrics {
    private Lyrics() { }

    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern LRC_HINT = Pattern.compile("\\[\\d+:\\d+");

    static JSONObject get(Context ctx, Library lib, String path) throws Exception {
        if (!lib.has(path)) return null;
        int dot = path.lastIndexOf('.');
        String base = dot > path.lastIndexOf('/') ? path.substring(0, dot) : path;
        for (String ext : new String[]{".lrc", ".LRC", ".txt"}) {
            File f = new File(base + ext);
            if (!f.canRead()) continue;
            String txt = Files.read(f);
            if (txt == null) continue;
            if (txt.startsWith("﻿")) txt = txt.substring(1);
            JSONArray synced = parseLrc(txt);
            return synced.length() > 0 ? new JSONObject().put("synced", synced).put("source", "lrc")
                    : new JSONObject().put("text", txt).put("source", "txt");
        }
        try (ParcelFileDescriptor pfd = lib.open(path); FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            JSONObject l = TagReader.lyrics(in.getChannel());
            if (l == null) return null;
            String text = l.optString("text", null);
            if (text != null && LRC_HINT.matcher(text).find()) {
                JSONArray synced = parseLrc(text);
                if (synced.length() > 0) return new JSONObject().put("synced", synced).put("source", "tag");
            }
            return l.put("source", "tag");
        }
    }

    /** [mm:ss.xx] lines (several stamps per line allowed) and an optional [offset: ms]. */
    static JSONArray parseLrc(String text) throws JSONException {
        List<Object[]> lines = new ArrayList<>();
        double offset = 0;
        for (String raw : text.split("\\r?\\n")) {
            Matcher off = OFFSET.matcher(raw);
            if (off.find()) { offset = Double.parseDouble(off.group(1)) / 1000.0; continue; }
            Matcher m = TIME.matcher(raw);
            List<Double> stamps = new ArrayList<>();
            while (m.find()) stamps.add(Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2)) - offset);
            if (stamps.isEmpty()) continue;
            String content = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
            for (double t : stamps) lines.add(new Object[]{t, content});
        }
        Collections.sort(lines, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        JSONArray out = new JSONArray();
        for (Object[] l : lines) out.put(new JSONObject().put("t", l[0]).put("text", l[1]));
        return out;
    }
}
