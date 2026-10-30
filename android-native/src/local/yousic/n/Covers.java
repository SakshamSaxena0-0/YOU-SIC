package local.yousic.n;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Album covers, decoded off the main thread and kept in memory (big ones for the player, small for lists). */
public final class Covers {
    public interface Ready { void ready(Bitmap b); }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 6)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, List<Ready>> waiting = new HashMap<>();

    private Covers() {}

    /** The cover now if it is in memory (null otherwise: then `then` gets it once decoded). */
    public static Bitmap get(String key, boolean small, Ready then) {
        if (key == null) return null;
        final String k = key + (small ? "_s" : "");
        Bitmap b = cache.get(k);
        if (b != null || then == null) return b;
        List<Ready> w = waiting.get(k);
        if (w != null) { w.add(then); return null; }
        w = new ArrayList<>();
        w.add(then);
        waiting.put(k, w);
        pool.execute(() -> {
            File f = App.get().lib.coverFile(key, small);
            Bitmap bm = f.isFile() ? PlayerService.decode(f, small ? 220 : 1000) : null;
            if (bm != null) bm.prepareToDraw();
            main.post(() -> {
                if (bm != null) cache.put(k, bm);
                List<Ready> ws = waiting.remove(k);
                if (ws != null && bm != null) for (Ready r : ws) r.ready(bm);
            });
        });
        return null;
    }
}
