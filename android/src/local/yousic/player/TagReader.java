package local.yousic.player;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads what Android's media library doesn't give us from a song's tags: embedded lyrics (ID3v2 USLT /
 * SYLT / TXXX:LYRICS in MP3, Vorbis comments LYRICS in FLAC and Ogg/Opus, ©lyr in MP4/M4A) and the bit depth
 * of lossless files (FLAC STREAMINFO, WAV fmt). The Windows app gets the same from music-metadata.
 */
final class TagReader {
    private TagReader() { }

    // ---------- lyrics ----------

    /** {text} or {synced: [{t, text}]}, or null if the file has no lyrics in its tags. */
    static JSONObject lyrics(FileChannel ch) throws IOException, JSONException {
        long pos = 0;
        byte[] head = read(ch, 0, 12);
        if (is(head, 0, "ID3")) {
            JSONObject r = id3(ch);
            if (r != null) return r;
            pos = 10 + synchsafe(head, 6) + ((head[5] & 0x10) != 0 ? 10 : 0);
            head = read(ch, pos, 12); // a FLAC file can carry an ID3 tag in front
        }
        if (is(head, 0, "fLaC")) return flacComments(ch, pos + 4);
        if (is(head, 0, "OggS")) return ogg(ch);
        if (head.length >= 8 && is(head, 4, "ftyp")) return mp4(ch);
        return null;
    }

    private static JSONObject id3(FileChannel ch) throws IOException, JSONException {
        byte[] h = read(ch, 0, 10);
        int ver = h[3], flags = h[5] & 0xff, size = synchsafe(h, 6);
        if (ver < 3 || size <= 0) return null; // ID3v2.2 (three-letter frames) is rare
        byte[] tag = read(ch, 10, size);
        if ((flags & 0x80) != 0 && ver == 3) tag = unsync(tag);
        int p = 0;
        if ((flags & 0x40) != 0 && tag.length >= 4) p = ver == 4 ? synchsafe(tag, 0) : be32(tag, 0) + 4;
        String text = null;
        JSONArray synced = null;
        while (p + 10 <= tag.length) {
            if (tag[p] == 0) break; // padding
            String id = new String(tag, p, 4, StandardCharsets.ISO_8859_1);
            int fsize = ver == 4 ? synchsafe(tag, p + 4) : be32(tag, p + 4);
            int fflags = ((tag[p + 8] & 0xff) << 8) | (tag[p + 9] & 0xff);
            int start = p + 10;
            if (fsize <= 0 || start + fsize > tag.length) break;
            p = start + fsize;
            if (!id.equals("USLT") && !id.equals("SYLT") && !id.equals("TXXX")) continue;
            byte[] d = Arrays.copyOfRange(tag, start, start + fsize);
            if (ver == 4) {
                if ((fflags & 0x000C) != 0) continue; // compressed or encrypted
                if ((fflags & 0x0040) != 0 && d.length > 1) d = Arrays.copyOfRange(d, 1, d.length); // grouping id
                if ((fflags & 0x0001) != 0 && d.length >= 4) d = Arrays.copyOfRange(d, 4, d.length); // data length
                if ((fflags & 0x0002) != 0) d = unsync(d);
            } else if ((fflags & 0x00C0) != 0) continue;
            if (d.length < 2) continue;
            switch (id) {
                case "USLT":
                    if (text == null && d.length > 4) {
                        int enc = d[0], t = term(d, 4, enc);
                        text = str(d, t + termLen(enc), d.length, enc);
                    }
                    break;
                case "SYLT":
                    if (synced == null) synced = sylt(d);
                    break;
                case "TXXX": {
                    int enc = d[0], t = term(d, 1, enc);
                    String desc = str(d, 1, t, enc);
                    if (text == null && (desc.equalsIgnoreCase("LYRICS") || desc.equalsIgnoreCase("UNSYNCEDLYRICS")))
                        text = str(d, Math.min(d.length, t + termLen(enc)), d.length, enc);
                    break;
                }
            }
        }
        return result(synced, text);
    }

    /** Synchronised lyrics: text with millisecond timestamps (MPEG-frame timestamps are not supported). */
    private static JSONArray sylt(byte[] d) throws JSONException {
        if (d.length < 7) return null;
        int enc = d[0], fmt = d[4];
        if (fmt != 2) return null;
        int p = term(d, 6, enc) + termLen(enc);
        JSONArray out = new JSONArray();
        while (p < d.length) {
            int t = term(d, p, enc);
            String line = str(d, p, t, enc);
            p = t + termLen(enc);
            if (p + 4 > d.length) break;
            long ms = be32(d, p) & 0xffffffffL;
            p += 4;
            out.put(new JSONObject().put("t", ms / 1000.0).put("text", line.replace("\n", " ").trim()));
        }
        return out.length() > 0 ? out : null;
    }

    private static JSONObject flacComments(FileChannel ch, long pos) throws IOException, JSONException {
        for (int i = 0; i < 128 && pos + 4 <= ch.size(); i++) {
            byte[] bh = read(ch, pos, 4);
            int type = bh[0] & 0x7f, len = ((bh[1] & 0xff) << 16) | ((bh[2] & 0xff) << 8) | (bh[3] & 0xff);
            if (type == 4) return result(null, vorbisLyrics(read(ch, pos + 4, len), 0));
            pos += 4 + len;
            if ((bh[0] & 0x80) != 0) break;
        }
        return null;
    }

    /** Ogg Vorbis / Opus: the comment header is the stream's second packet (it may span pages). */
    private static JSONObject ogg(FileChannel ch) throws IOException, JSONException {
        long pos = 0, size = ch.size();
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        int packet = 0;
        for (int pages = 0; pages < 2048 && pos + 27 <= size; pages++) {
            byte[] h = read(ch, pos, 27);
            if (!is(h, 0, "OggS")) break;
            int nseg = h[26] & 0xff;
            byte[] segs = read(ch, pos + 27, nseg);
            int total = 0;
            for (byte s : segs) total += s & 0xff;
            byte[] body = read(ch, pos + 27 + nseg, total);
            int bp = 0;
            for (byte s : segs) {
                int l = s & 0xff;
                if (bp + l > body.length) break;
                pkt.write(body, bp, l);
                bp += l;
                if (l < 255) { // the packet ends here
                    if (packet == 1) {
                        byte[] p = pkt.toByteArray();
                        if (p.length > 7 && p[0] == 3 && is(p, 1, "vorbis")) return result(null, vorbisLyrics(p, 7));
                        if (p.length > 8 && is(p, 0, "OpusTags")) return result(null, vorbisLyrics(p, 8));
                        return null;
                    }
                    packet++;
                    pkt.reset();
                }
            }
            if (pkt.size() > 32 << 20) break;
            pos += 27 + nseg + total;
        }
        return null;
    }

    private static String vorbisLyrics(byte[] d, int off) {
        try {
            int p = off + 4 + le32(d, off);
            int n = le32(d, p);
            p += 4;
            for (int i = 0; i < n && p + 4 <= d.length; i++) {
                int l = le32(d, p);
                p += 4;
                if (l < 0 || p + l > d.length) break;
                int eq = -1;
                for (int k = p; k < p + l && k < p + 32; k++) if (d[k] == '=') { eq = k; break; }
                if (eq > 0) {
                    String key = new String(d, p, eq - p, StandardCharsets.US_ASCII);
                    if (key.equalsIgnoreCase("LYRICS") || key.equalsIgnoreCase("UNSYNCEDLYRICS"))
                        return new String(d, eq + 1, p + l - eq - 1, StandardCharsets.UTF_8);
                }
                p += l;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static final byte[] LYR = {(byte) 0xA9, 'l', 'y', 'r'};

    /** MP4 / M4A: moov > udta > meta > ilst > ©lyr > data (or moov > meta > ...). */
    private static JSONObject mp4(FileChannel ch) throws IOException, JSONException {
        long[] moov = atom(ch, 0, ch.size(), "moov".getBytes(StandardCharsets.US_ASCII));
        if (moov == null) return null;
        long[] udta = atom(ch, moov[0], moov[1], "udta".getBytes(StandardCharsets.US_ASCII));
        long[] meta = udta != null ? atom(ch, udta[0], udta[1], "meta".getBytes(StandardCharsets.US_ASCII)) : null;
        if (meta == null) meta = atom(ch, moov[0], moov[1], "meta".getBytes(StandardCharsets.US_ASCII));
        if (meta == null) return null;
        long[] ilst = atom(ch, meta[0] + 4, meta[1], "ilst".getBytes(StandardCharsets.US_ASCII)); // meta is a full box
        if (ilst == null) return null;
        long[] lyr = atom(ch, ilst[0], ilst[1], LYR);
        if (lyr == null) return null;
        long[] data = atom(ch, lyr[0], lyr[1], "data".getBytes(StandardCharsets.US_ASCII));
        if (data == null || data[1] - data[0] <= 8 || data[1] - data[0] > (8 << 20)) return null;
        byte[] d = read(ch, data[0] + 8, (int) (data[1] - data[0] - 8));
        return result(null, new String(d, StandardCharsets.UTF_8));
    }

    /** The content [start, end) of the first child atom of this type within [from, to). */
    private static long[] atom(FileChannel ch, long from, long to, byte[] type) throws IOException {
        long pos = from;
        for (int i = 0; i < 4096 && pos + 8 <= to; i++) {
            byte[] h = read(ch, pos, 16);
            if (h.length < 8) return null;
            long size = be32(h, 0) & 0xffffffffL;
            int hdr = 8;
            if (size == 1 && h.length >= 16) { size = ((long) be32(h, 8) << 32) | (be32(h, 12) & 0xffffffffL); hdr = 16; }
            else if (size == 0) size = to - pos;
            if (size < hdr) return null;
            if (h[4] == type[0] && h[5] == type[1] && h[6] == type[2] && h[7] == type[3]) return new long[]{pos + hdr, Math.min(to, pos + size)};
            pos += size;
        }
        return null;
    }

    private static JSONObject result(JSONArray synced, String text) throws JSONException {
        if (synced != null && synced.length() > 0) return new JSONObject().put("synced", synced);
        if (text != null && !text.trim().isEmpty()) return new JSONObject().put("text", text.replace("\r\n", "\n").trim());
        return null;
    }

    // ---------- bit depth of lossless files ----------

    static int bitsPerSample(Context ctx, Uri uri) {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            byte[] head = read(ch, 0, 12);
            long pos = 0;
            if (is(head, 0, "ID3")) { pos = 10 + synchsafe(head, 6); head = read(ch, pos, 12); }
            if (is(head, 0, "fLaC")) {
                byte[] si = read(ch, pos + 8, 18); // STREAMINFO is always the first block
                if (si.length >= 14) return (((si[12] & 0x01) << 4) | ((si[13] & 0xF0) >> 4)) + 1;
            }
            if (is(head, 0, "RIFF") && is(head, 8, "WAVE")) {
                long p = 12;
                for (int i = 0; i < 64 && p + 8 <= ch.size(); i++) {
                    byte[] c = read(ch, p, 8);
                    int len = le32(c, 4);
                    if (is(c, 0, "fmt ")) { byte[] f = read(ch, p + 8, 16); return f.length >= 16 ? (f[14] & 0xff) | ((f[15] & 0xff) << 8) : 0; }
                    p += 8 + len + (len & 1);
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    // ---------- bytes ----------

    static byte[] read(FileChannel ch, long pos, int len) throws IOException {
        if (len <= 0 || pos >= ch.size()) return new byte[0];
        len = (int) Math.min(len, ch.size() - pos);
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r <= 0) break;
        }
        return b.position() == len ? b.array() : Arrays.copyOf(b.array(), b.position());
    }

    private static boolean is(byte[] d, int off, String s) {
        if (d.length < off + s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (d[off + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static int synchsafe(byte[] d, int o) {
        return ((d[o] & 0x7f) << 21) | ((d[o + 1] & 0x7f) << 14) | ((d[o + 2] & 0x7f) << 7) | (d[o + 3] & 0x7f);
    }

    private static int be32(byte[] d, int o) {
        return ((d[o] & 0xff) << 24) | ((d[o + 1] & 0xff) << 16) | ((d[o + 2] & 0xff) << 8) | (d[o + 3] & 0xff);
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    /** Undo ID3 unsynchronisation (0xFF 0x00 -> 0xFF). */
    private static byte[] unsync(byte[] d) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(d.length);
        for (int i = 0; i < d.length; i++) {
            o.write(d[i]);
            if ((d[i] & 0xff) == 0xff && i + 1 < d.length && d[i + 1] == 0) i++;
        }
        return o.toByteArray();
    }

    private static Charset charset(int enc) {
        switch (enc) {
            case 1: return StandardCharsets.UTF_16;
            case 2: return StandardCharsets.UTF_16BE;
            case 3: return StandardCharsets.UTF_8;
            default: return StandardCharsets.ISO_8859_1;
        }
    }

    private static int termLen(int enc) { return enc == 1 || enc == 2 ? 2 : 1; }

    /** Where the zero terminator of a string that starts at off is (or the end). */
    private static int term(byte[] d, int off, int enc) {
        if (enc == 1 || enc == 2) {
            for (int i = off; i + 1 < d.length; i += 2) if (d[i] == 0 && d[i + 1] == 0) return i;
        } else {
            for (int i = off; i < d.length; i++) if (d[i] == 0) return i;
        }
        return d.length;
    }

    private static String str(byte[] d, int from, int to, int enc) {
        if (from >= to || from >= d.length) return "";
        String s = new String(d, from, Math.min(to, d.length) - from, charset(enc));
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end);
    }
}
