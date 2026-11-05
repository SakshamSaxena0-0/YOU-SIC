// Metal theme: covers etched into the brushed aluminium (settings.metalCover 'etched'). Asks the worker
// (js/etch.worker.js) for each cover once and keeps the last few as blob URLs, so swiping back and
// forth, and the neighbouring covers waiting beside the current one, never redo the work.
const KEEP = 12;
const cache = new Map(); // key -> { p: Promise<string>, url: string | null }
let worker = null, seq = 0;
const waiting = new Map();

function getWorker() {
  if (worker) return worker;
  worker = new Worker(new URL('./etch.worker.js', import.meta.url));
  worker.onmessage = (e) => {
    const { id, blob, error } = e.data;
    const w = waiting.get(id);
    if (!w) return;
    waiting.delete(id);
    if (error || !blob) w.bad(new Error(error || 'etch failed'));
    else w.ok(URL.createObjectURL(blob));
  };
  return worker;
}

// the etched cover's blob URL if it is ready now, else null
export function etchedNow(url, size = 1000) {
  const hit = cache.get(url + '|' + size);
  return hit && hit.url;
}

export function etch(url, size = 1000) {
  const key = url + '|' + size;
  const hit = cache.get(key);
  if (hit) { cache.delete(key); cache.set(key, hit); return hit.p; } // most recent last
  const entry = { url: null, p: null };
  entry.p = new Promise((ok, bad) => {
    const id = ++seq;
    waiting.set(id, { ok, bad });
    try { getWorker().postMessage({ id, url: new URL(url, location.href).href, size }); } catch (e) { waiting.delete(id); bad(e); }
  }).then((u) => (entry.url = u));
  entry.p.catch(() => cache.delete(key));
  cache.set(key, entry);
  while (cache.size > KEEP) {
    const [k, old] = cache.entries().next().value;
    cache.delete(k);
    if (old.url) setTimeout(() => URL.revokeObjectURL(old.url), 5000); // let anything still showing it move on
  }
  return entry.p;
}
