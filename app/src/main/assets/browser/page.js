// Injected at document start into every frame of pages opened in the built-in browser.
// It reports what the page's videos do (play, pause, seek, speed, time) and, only after the
// viewer starts AI subtitles, either passes on the sound the page's player has already loaded
// ahead (Media Source Extensions, see "Sound ahead"; for Smart Delay the waiting video can be
// moved forward and back so the site's player loads more, see "Scan ahead"), or taps the playing
// video's sound with the Web Audio API and hands it to the app as 16 kHz mono PCM. It never
// downloads media, never touches protected (DRM) video and never taps media that the page's
// origin may not read, because Chromium would return silence for it and the viewer would lose
// the sound as well.
(function () {
  'use strict';
  if (window.__subtransPage) return;
  window.__subtransPage = true;
  var bridge = window.subtransBridge;
  if (!bridge) return;

  var RATE = 16000;
  var tapOn = false;
  var current = null;
  var ctx = null;
  var proc = null;
  var tapped = typeof WeakSet === 'function' ? new WeakSet() : null;
  var pos = 0;
  var silentSince = 0;
  var silentReported = false;

  function post(o) {
    try { bridge.postMessage(JSON.stringify(o)); } catch (e) { /* frame is going away */ }
  }

  function info(v) {
    return {
      t: v.currentTime || 0,
      d: isFinite(v.duration) ? v.duration : -1,
      rate: v.playbackRate || 1,
      paused: !!v.paused,
      w: v.videoWidth || 0,
      h: v.videoHeight || 0,
      top: window === window.top,
      drm: !!v.mediaKeys,
      tracks: v.textTracks ? v.textTracks.length : 0
    };
  }

  function media(ev, v) {
    var o = info(v);
    o.type = 'media';
    o.ev = ev;
    post(o);
  }

  ['play', 'playing', 'pause', 'seeking', 'seeked', 'ratechange', 'ended', 'loadedmetadata', 'emptied'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      var v = e.target;
      if (!(v instanceof HTMLMediaElement)) return;
      if (type === 'play' || type === 'playing') current = v;
      if (type === 'loadedmetadata' || type === 'play') watchTracks(v);
      // Only the video being watched: a muted preview elsewhere on the page must not interfere.
      if (current && v !== current) return;
      if (scan && v === scan.v) { onScanEvent(type, v); return; }
      media(type, v);
      if (tapOn && type === 'playing') tap(v);
    }, true);
  });

  setInterval(function () {
    if (current && !current.paused) media('time', current);
  }, 1000);

  // Why the page's sound cannot be read, or null when it can.
  function blocked(v) {
    if (v.mediaKeys) return 'drm';
    var src = v.currentSrc || v.src || '';
    if (!src) return 'nosource';
    if (src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return null;
    try {
      if (new URL(src, location.href).origin === location.origin) return null;
    } catch (e) {
      return 'nosource';
    }
    // Requested with CORS: readable if the server agreed; silence is detected below if not.
    if (v.crossOrigin) return null;
    return 'crossorigin';
  }

  // Video waiting for the audio context to be allowed to start (a tap on the page does it).
  var pendingTap = null;
  var suspendTimer = 0;

  function connect(v) {
    if (tapped && tapped.has(v)) return;
    try {
      var src = ctx.createMediaElementSource(v);
      if (!proc) {
        proc = ctx.createScriptProcessor(4096, 1, 1);
        var mute = ctx.createGain();
        mute.gain.value = 0;
        proc.connect(mute);
        mute.connect(ctx.destination);
        proc.onaudioprocess = onAudio;
      }
      src.connect(ctx.destination);
      src.connect(proc);
      if (tapped) tapped.add(v);
      silentSince = 0;
      silentReported = false;
      post({ type: 'tap', ok: true, rate: ctx.sampleRate });
    } catch (e) {
      post({ type: 'tap', ok: false, why: 'error', msg: String(e) });
    }
  }

  function makeContext() {
    if (ctx) return true;
    try {
      ctx = new (window.AudioContext || window.webkitAudioContext)();
    } catch (e) {
      post({ type: 'tap', ok: false, why: 'error', msg: String(e) });
      return false;
    }
    ctx.onstatechange = function () {
      if (ctx.state === 'running' && pendingTap) {
        var v = pendingTap;
        pendingTap = null;
        if (suspendTimer) clearTimeout(suspendTimer);
        suspendTimer = 0;
        if (tapOn && v === current && !v.paused) connect(v);
      }
    };
    return true;
  }

  function tap(v) {
    if (tapped && tapped.has(v)) return;
    var why = blocked(v);
    if (why) {
      post({ type: 'tap', ok: false, why: why });
      return;
    }
    if (!makeContext()) return;
    if (ctx.state === 'running') {
      connect(v);
      return;
    }
    // Chromium starts audio only after the viewer touched the page. Until then the video is
    // left alone (connecting a stopped context would mute it); the app asks for one tap.
    pendingTap = v;
    ctx.resume().then(function () {}, function () {});
    if (!suspendTimer) {
      suspendTimer = setTimeout(function () {
        suspendTimer = 0;
        if (pendingTap && ctx.state !== 'running') post({ type: 'tap', ok: false, why: 'suspended' });
      }, 1500);
    }
  }

  // A touch or key on the page while subtitles are on lets the audio context start.
  function unlock() {
    if (!tapOn || !current) return;
    if (!makeContext()) return;
    if (ctx.state !== 'running') ctx.resume().then(function () {}, function () {});
  }
  ['pointerup', 'touchend', 'click', 'keydown'].forEach(function (t) {
    document.addEventListener(t, unlock, true);
  });

  function onAudio(e) {
    if (!tapOn || !current || current.paused) return;
    var inp = e.inputBuffer.getChannelData(0);
    var ratio = ctx.sampleRate / RATE;
    var out = new Int16Array(Math.ceil(inp.length / ratio) + 1);
    var k = 0;
    var loud = false;
    // Average each 1/16000 s step: a simple low-pass before keeping one sample.
    while (pos + ratio <= inp.length) {
      var a = Math.floor(pos);
      var b = Math.max(a + 1, Math.floor(pos + ratio));
      var s = 0;
      for (var i = a; i < b; i++) s += inp[i];
      var val = s / (b - a);
      if (val !== 0) loud = true;
      out[k++] = Math.max(-32768, Math.min(32767, Math.round(val * 32767)));
      pos += ratio;
    }
    pos = Math.max(0, pos - inp.length);
    var now = Date.now();
    if (loud) {
      silentSince = 0;
    } else if (!silentSince) {
      silentSince = now;
    } else if (!silentReported && now - silentSince > 4000) {
      // Exact zeros while the video plays: Chromium refused to hand over this media's sound.
      silentReported = true;
      post({ type: 'tap', ok: false, why: 'silent' });
    }
    var bytes = new Uint8Array(out.buffer, 0, k * 2);
    var bin = '';
    for (var j = 0; j < bytes.length; j += 0x8000) {
      bin += String.fromCharCode.apply(null, bytes.subarray(j, j + 0x8000));
    }
    post({ type: 'pcm', t: current.currentTime, pcm: btoa(bin) });
  }

  // ---- Sound ahead (Smart Delay for the video's sound) ----
  // Most video sites play through Media Source Extensions: their player fetches the sound some
  // seconds ahead of the picture and gives it to the browser with SourceBuffer.appendBuffer.
  // The sound-only part of what the player has already loaded is noted here (pictures are never
  // copied: when a site keeps sound and picture in one MP4 buffer, only the sound track's samples
  // are kept), and passed to the app only after the viewer starts AI subtitles, so speech can be
  // recognised before the picture reaches it. Nothing extra is fetched. Protected (DRM) media is
  // never passed on. What is kept stays in this page's memory: the last few seconds behind the
  // playhead and what the player loaded ahead, at most MSE_MAX_BYTES per sound buffer.
  var MSE_KEEP_BEHIND = 20;
  var MSE_MAX_BYTES = 12 * 1024 * 1024;
  var segOn = false;
  var segBuf = null;
  var bufs = [];
  var nextBufId = 1;
  var bufOf = typeof WeakMap === 'function' ? new WeakMap() : null;
  var msUrl = typeof WeakMap === 'function' ? new WeakMap() : null;
  var mseReported = '';
  var outQueue = [];
  var pumping = false;

  function isAudioCodec(c) { return /^(mp4a|opus|vorbis|flac|ac-3|ec-3|mp3|alac|ac-4|dts)/.test(c); }
  function isVideoCodec(c) { return /^(avc|hvc|hev|vp8|vp9|vp08|vp09|av01|dvh|dva|mp4v|theora)/.test(c); }

  // 'audio' (sound only), 'video' (picture only) or 'muxed' (both in one buffer: for MP4 the
  // sound track is taken out of it, see soundOnly).
  function kindOf(type) {
    var t = String(type).toLowerCase();
    var m = /codecs\s*=\s*"?([^";]*)/.exec(t);
    var list = m ? m[1].split(',') : [];
    var a = false;
    var vid = false;
    for (var i = 0; i < list.length; i++) {
      var c = list[i].trim();
      if (!c) continue;
      if (isAudioCodec(c)) a = true; else if (isVideoCodec(c)) vid = true;
    }
    if (!a && !vid) return t.indexOf('audio/') === 0 ? 'audio' : 'video';
    if (a && vid) return 'muxed';
    return a ? 'audio' : 'video';
  }

  function fourcc(u, p) { return String.fromCharCode(u[p], u[p + 1], u[p + 2], u[p + 3]); }

  // Top-level MP4 boxes of one appended piece (types only).
  function topBoxes(u) {
    var out = [];
    var p = 0;
    while (p + 8 <= u.length && out.length < 64) {
      var size = u[p] * 16777216 + (u[p + 1] << 16) + (u[p + 2] << 8) + u[p + 3];
      if (size === 1 && p + 16 <= u.length) size = (u[p + 12] * 16777216 + (u[p + 13] << 16) + (u[p + 14] << 8) + u[p + 15]) + 4294967296 * (u[p + 8] * 16777216 + (u[p + 9] << 16) + (u[p + 10] << 8) + u[p + 11]);
      else if (size === 0) size = u.length - p;
      if (size < 8) break;
      out.push(fourcc(u, p + 4));
      p += size;
    }
    return out;
  }

  function contains(u, tag) {
    var a = tag.charCodeAt(0), b = tag.charCodeAt(1), c = tag.charCodeAt(2), d = tag.charCodeAt(3);
    for (var i = 0; i + 3 < u.length; i++) {
      if (u[i] === a && u[i + 1] === b && u[i + 2] === c && u[i + 3] === d) return true;
    }
    return false;
  }

  // Encrypted sound (Common Encryption, or WebM ContentEncryption): never passed on.
  function encrypted(u, mp4) {
    if (mp4) return contains(u, 'enca') || contains(u, 'tenc') || contains(u, 'pssh') || contains(u, 'senc');
    // WebM header: ContentEncryption (0x5035) together with ContentEncAlgo (0x47E1).
    var enc = false, algo = false;
    for (var i = 0; i + 1 < u.length && i < 65536; i++) {
      if (u[i] === 0x50 && u[i + 1] === 0x35) enc = true;
      if (u[i] === 0x47 && u[i + 1] === 0xe1) algo = true;
    }
    return enc && algo;
  }

  // ---- The sound track out of a buffer that holds sound and picture (fragmented MP4) ----
  // The init piece keeps only the sound track's 'trak' (and its 'trex'); each moof+mdat keeps
  // only that track's 'traf' and its samples, with data offsets counted from the new moof.
  // Anything it does not understand gives null, and that buffer is then not read at all.
  function u32(u, p) { return u[p] * 16777216 + (u[p + 1] << 16) + (u[p + 2] << 8) + u[p + 3]; }

  function put32(u, p, v) {
    u[p] = (v >>> 24) & 255; u[p + 1] = (v >>> 16) & 255; u[p + 2] = (v >>> 8) & 255; u[p + 3] = v & 255;
  }

  // Boxes inside u[from, to): {type, p (start), size, hdr (header length)}.
  function kids(u, from, to) {
    var out = [];
    var p = from;
    while (p + 8 <= to) {
      var size = u32(u, p);
      var hdr = 8;
      if (size === 1) {
        if (p + 16 > to) break;
        size = u32(u, p + 12) + 4294967296 * u32(u, p + 8);
        hdr = 16;
      } else if (size === 0) size = to - p;
      if (size < hdr || p + size > to) break;
      out.push({ type: fourcc(u, p + 4), p: p, size: size, hdr: hdr });
      p += size;
    }
    return out;
  }

  function inside(u, b) { return kids(u, b.p + b.hdr, b.p + b.size); }
  function first(list, type) { for (var i = 0; i < list.length; i++) if (list[i].type === type) return list[i]; return null; }
  function whole(u, b) { return u.subarray(b.p, b.p + b.size); }

  function join(parts) {
    var n = 0;
    for (var i = 0; i < parts.length; i++) n += parts[i].length;
    var out = new Uint8Array(n);
    var p = 0;
    for (var j = 0; j < parts.length; j++) { out.set(parts[j], p); p += parts[j].length; }
    return out;
  }

  function box(type, parts) {
    var b = join([new Uint8Array(8)].concat(parts));
    put32(b, 0, b.length);
    for (var i = 0; i < 4; i++) b[4 + i] = type.charCodeAt(i);
    return b;
  }

  function flagsOf(u, b) { return (u[b + 1] << 16) | (u[b + 2] << 8) | u[b + 3]; }

  function soundMoov(u, moov, r) {
    var ch = inside(u, moov);
    var keep = [];
    var mvex = null;
    var mvexAt = -1;
    var id = -1;
    r.trex = {};
    for (var i = 0; i < ch.length; i++) {
      var c = ch[i];
      if (c.type === 'trak') {
        var tk = inside(u, c);
        var tkhd = first(tk, 'tkhd');
        var mdia = first(tk, 'mdia');
        var hdlr = mdia ? first(inside(u, mdia), 'hdlr') : null;
        if (!tkhd || !hdlr || id >= 0) continue;
        var hb = tkhd.p + tkhd.hdr;
        if (fourcc(u, hdlr.p + hdlr.hdr + 8) !== 'soun') continue;
        id = u32(u, hb + (u[hb] === 1 ? 20 : 12));
        keep.push(whole(u, c));
      } else if (c.type === 'mvex') {
        mvex = c;
        mvexAt = keep.length;
        keep.push(null);
      } else keep.push(whole(u, c));
    }
    if (id < 0 || !mvex) return null;
    var mv = inside(u, mvex);
    var parts = [];
    for (var j = 0; j < mv.length; j++) {
      var x = mv[j];
      if (x.type !== 'trex') { parts.push(whole(u, x)); continue; }
      var xb = x.p + x.hdr;
      var xt = u32(u, xb + 4);
      r.trex[xt] = u32(u, xb + 16);
      if (xt === id) parts.push(whole(u, x));
    }
    keep[mvexAt] = box('mvex', parts);
    r.soundTrack = id;
    return box('moov', keep);
  }

  // [moof, mdat] for the sound track of one moof (whose samples must be in u), or null.
  function soundMoof(u, moof, r) {
    var ch = inside(u, moof);
    var mfhd = first(ch, 'mfhd');
    if (!mfhd) return null;
    var prevEnd = moof.p;
    var firstTraf = true;
    var out = null;
    for (var i = 0; i < ch.length; i++) {
      if (ch[i].type !== 'traf') continue;
      var tc = inside(u, ch[i]);
      var tfhd = first(tc, 'tfhd');
      if (!tfhd) return null;
      var hb = tfhd.p + tfhd.hdr;
      var tfl = flagsOf(u, hb);
      var tid = u32(u, hb + 4);
      if (tfl & 1) return null; // absolute base offsets: not used by MSE players
      var q = hb + 8;
      if (tfl & 2) q += 4;
      if (tfl & 8) q += 4;
      var defSize = r.trex[tid] || 0;
      if (tfl & 0x10) defSize = u32(u, q);
      var base = (tfl & 0x20000) || firstTraf ? moof.p : prevEnd;
      firstTraf = false;
      var runs = [];
      var pos = base;
      for (var k = 0; k < tc.length; k++) {
        var x = tc[k];
        if (x.type !== 'trun') continue;
        var b = x.p + x.hdr;
        var fl = flagsOf(u, b);
        var n = u32(u, b + 4);
        var p2 = b + 8;
        var start = pos;
        if (fl & 1) {
          var off = u32(u, p2);
          if (off >= 2147483648) off -= 4294967296;
          start = base + off;
          p2 += 4;
        }
        if (fl & 4) p2 += 4;
        var per = ((fl & 0x100) ? 4 : 0) + ((fl & 0x200) ? 4 : 0) + ((fl & 0x400) ? 4 : 0) + ((fl & 0x800) ? 4 : 0);
        var sizeAt = (fl & 0x100) ? 4 : 0;
        if (p2 + n * per > x.p + x.size) return null;
        var len = 0;
        for (var s = 0; s < n; s++) len += (fl & 0x200) ? u32(u, p2 + s * per + sizeAt) : defSize;
        if (n > 0 && len === 0) return null;
        runs.push({ box: x, b: b, fl: fl, start: start, len: len });
        pos = start + len;
      }
      prevEnd = pos;
      if (tid !== r.soundTrack) continue;
      var vf = new Uint8Array(4);
      put32(vf, 0, u32(u, hb));
      vf[1] |= 0x02; // default-base-is-moof
      var newTfhd = box('tfhd', [vf, u.subarray(hb + 4, tfhd.p + tfhd.size)]);
      var tfdt = first(tc, 'tfdt');
      var head = [newTfhd];
      if (tfdt) head.push(whole(u, tfdt));
      var truns = [];
      var datas = [];
      for (var m = 0; m < runs.length; m++) {
        var rn = runs[m];
        if (rn.start < 0 || rn.start + rn.len > u.length) return null;
        datas.push(u.subarray(rn.start, rn.start + rn.len));
        if (rn.fl & 1) {
          truns.push(new Uint8Array(whole(u, rn.box)));
        } else {
          var tvf = new Uint8Array(8);
          put32(tvf, 0, u32(u, rn.b) | 1);
          put32(tvf, 4, u32(u, rn.b + 4));
          truns.push(box('trun', [tvf, new Uint8Array(4), u.subarray(rn.b + 8, rn.box.p + rn.box.size)]));
        }
      }
      var headLen = 0;
      for (var h = 0; h < head.length; h++) headLen += head[h].length;
      var trunsLen = 0;
      for (var t = 0; t < truns.length; t++) trunsLen += truns[t].length;
      var moofLen = 8 + mfhd.size + 8 + headLen + trunsLen;
      var dataAt = moofLen + 8;
      for (var w = 0; w < truns.length; w++) {
        // data_offset sits right after the version/flags and sample_count of each trun.
        var tr = truns[w];
        var offAt = (u32(tr, 0) === 1 ? 16 : 8) + 8;
        put32(tr, offAt, dataAt);
        dataAt += datas[w].length;
      }
      var newMoof = box('moof', [whole(u, mfhd), box('traf', head.concat(truns))]);
      out = [newMoof, box('mdat', datas)];
    }
    return out;
  }

  // The sound track's part of one appended piece of a sound+picture MP4 buffer, or null.
  function soundOnly(u, r) {
    var top = kids(u, 0, u.length);
    var parts = [];
    for (var i = 0; i < top.length; i++) {
      var b = top[i];
      if (b.type === 'ftyp' || b.type === 'styp') parts.push(whole(u, b));
      else if (b.type === 'moov') {
        var m = soundMoov(u, b, r);
        if (!m) return null;
        parts.push(m);
      } else if (b.type === 'moof') {
        if (r.soundTrack < 0) return null;
        var f = soundMoof(u, b, r);
        if (!f) return null;
        parts.push(f[0], f[1]);
      }
    }
    return parts.length ? join(parts) : null;
  }

  // Buffers whose sound can be passed on.
  function readable(r) { return r.kind === 'audio' || (r.kind === 'muxed' && !r.bad); }

  function copyBytes(data) {
    if (data instanceof ArrayBuffer) return new Uint8Array(data.slice(0));
    if (data && data.buffer instanceof ArrayBuffer) return new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
    return null;
  }

  function rangesOf(sb) {
    var out = [];
    try {
      var b = sb.buffered;
      for (var i = 0; i < b.length; i++) out.push([b.start(i), b.end(i)]);
    } catch (e) { /* removed from its MediaSource */ }
    return out;
  }

  // Time span an append added to the buffer, or [-1, -1] when it only replaced sound already there.
  function added(before, after) {
    var t0 = -1, t1 = -1;
    for (var i = 0; i < after.length; i++) {
      var s = after[i][0], e = after[i][1];
      for (var j = 0; j < before.length; j++) {
        var bs = before[j][0], be = before[j][1];
        if (be <= s || bs >= e) continue;
        if (bs <= s + 0.01 && be >= e - 0.01) { s = e; break; }
        if (bs <= s + 0.01) s = Math.max(s, be);
        else if (be >= e - 0.01) e = Math.min(e, bs);
      }
      if (e - s > 0.01) {
        if (t0 < 0 || s < t0) t0 = s;
        if (e > t1) t1 = e;
      }
    }
    return [t0, t1];
  }

  function reportMse() {
    var audio = false, muxed = false, enc = false;
    for (var i = 0; i < bufs.length; i++) {
      if (readable(bufs[i]) && bufs[i].appends > 0) { audio = true; if (bufs[i].enc) enc = true; }
      if (bufs[i].kind === 'muxed') muxed = true;
    }
    var key = audio + ',' + muxed + ',' + enc;
    if (key === mseReported) return;
    mseReported = key;
    post({ type: 'mse', audio: audio, muxed: muxed, drm: enc, top: window === window.top });
  }

  function watchBuffer(ms, sb, type) {
    var r = { id: nextBufId++, ms: ms, mime: String(type), kind: kindOf(type), init: null, mp4: true, segs: [], bytes: 0, pending: [], appends: 0, enc: false, last: 0, bad: false, soundTrack: -1, trex: {} };
    if (bufOf) bufOf.set(sb, r);
    bufs.push(r);
    // Buffers of finished players: forget them (and their sound).
    for (var i = bufs.length - 1; i >= 0; i--) {
      if (bufs[i] !== r && bufs[i].ms && bufs[i].ms.readyState === 'closed') bufs.splice(i, 1);
    }
    if (bufs.length > 16) bufs.splice(0, bufs.length - 16);
    sb.addEventListener('updateend', function () { finishAppend(r, sb); });
    sb.addEventListener('abort', function () { r.pending.length = 0; });
    reportMse();
  }

  function noteAppend(r, sb, data) {
    if (!readable(r)) { r.pending.push(null); return; }
    var u = copyBytes(data);
    if (!u || !u.length) { r.pending.push(null); return; }
    var webm = u.length >= 4 && u[0] === 0x1a && u[1] === 0x45 && u[2] === 0xdf && u[3] === 0xa3;
    var muxed = r.kind === 'muxed';
    if (muxed && webm) {
      // Sound and picture in one WebM buffer: not read.
      r.bad = true;
      r.pending.push(null);
      reportMse();
      return;
    }
    var boxes = webm ? [] : topBoxes(u);
    var isInit = webm || boxes.indexOf('moov') >= 0;
    if (webm) r.mp4 = false;
    if (isInit) {
      r.mp4 = !webm;
      if (encrypted(u, !webm)) r.enc = true;
    } else if (!r.enc && r.mp4 && boxes.indexOf('moof') >= 0) {
      if (muxed) {
        var top = kids(u, 0, u.length);
        for (var i = 0; i < top.length; i++) {
          if (top[i].type === 'moof' && contains(whole(u, top[i]), 'senc')) r.enc = true;
        }
      } else if (contains(u, 'senc')) r.enc = true;
    }
    if (muxed && !r.enc) {
      // Only the sound track's part is kept; the picture is dropped here.
      var s = soundOnly(u, r);
      if (!s) {
        if (isInit) { r.bad = true; reportMse(); }
        r.pending.push(null);
        return;
      }
      u = s;
    }
    if (isInit) r.init = u;
    var to = 0;
    var mode = '';
    try { to = sb.timestampOffset; mode = sb.mode; } catch (e) { /* removed */ }
    r.pending.push({ data: u, init: isInit, to: to, seq: mode === 'sequence', before: rangesOf(sb) });
  }

  function finishAppend(r, sb) {
    if (!r.pending.length) return;
    var p = r.pending.shift();
    if (!p) return;
    var span = added(p.before, rangesOf(sb));
    var seg = { data: p.data, init: p.init, to: p.to, seq: p.seq, t0: span[0], t1: span[1] };
    r.appends++;
    r.last = Date.now();
    if (!p.init || topBoxes(p.data).indexOf('moof') >= 0 || !r.mp4) {
      r.segs.push(seg);
      r.bytes += seg.data.length;
      trim(r);
    }
    reportMse();
    if (segOn) offer(r, seg);
  }

  // Sound already played (more than MSE_KEEP_BEHIND s ago) and anything over the size cap goes.
  function trim(r) {
    var v = current || capVideo;
    var now = v ? v.currentTime : 0;
    for (var i = r.segs.length - 1; i >= 0; i--) {
      var s = r.segs[i];
      if (s.t1 >= 0 && s.t1 < now - MSE_KEEP_BEHIND) { r.bytes -= s.data.length; r.segs.splice(i, 1); }
    }
    while (r.bytes > MSE_MAX_BYTES && r.segs.length > 1) r.bytes -= r.segs.shift().data.length;
  }

  // The sound buffer of the video being watched: its MediaSource is the video's source, or else
  // the sound buffer appended to last.
  function soundBuffer() {
    var v = current || capVideo;
    var src = v ? (v.currentSrc || v.src || '') : '';
    var best = null;
    for (var i = 0; i < bufs.length; i++) {
      var r = bufs[i];
      if (!readable(r) || !r.appends) continue;
      if (src && msUrl && msUrl.get(r.ms) === src) return r;
      if (!best || r.last > best.last) best = r;
    }
    return best;
  }

  function b64(u) {
    var bin = '';
    for (var j = 0; j < u.length; j += 0x8000) bin += String.fromCharCode.apply(null, u.subarray(j, j + 0x8000));
    return btoa(bin);
  }

  function segMessage(r, s) {
    return { type: 'seg', sb: r.id, mime: r.mime, init: !!s.init, to: s.to, seq: !!s.seq, t0: s.t0, t1: s.t1, data: s.data };
  }

  function pump() {
    if (!segOn) outQueue.length = 0;
    if (!outQueue.length) { pumping = false; return; }
    pumping = true;
    var m = outQueue.shift();
    m.data = b64(m.data);
    post(m);
    setTimeout(pump, 0);
  }

  function send(m) {
    outQueue.push(m);
    if (!pumping) pump();
  }

  // A new piece of sound while subtitles are on: passed on if it belongs to the watched video.
  function offer(r, s) {
    if (soundBuffer() !== r) return;
    if (segBuf !== r) { startSending(r); return; }
    if (r.enc) return;
    send(segMessage(r, s));
  }

  function startSending(r) {
    segBuf = r;
    var v = current || capVideo;
    if (r.enc || (v && v.mediaKeys)) {
      post({ type: 'seg-error', why: 'drm' });
      return;
    }
    if (!r.init) {
      post({ type: 'seg-error', why: 'noinit' });
      return;
    }
    send({ type: 'seg', sb: r.id, mime: r.mime, init: true, to: 0, seq: false, t0: -1, t1: -1, data: r.init });
    for (var i = 0; i < r.segs.length; i++) send(segMessage(r, r.segs[i]));
  }

  function segsOn(on) {
    segOn = on;
    segBuf = null;
    outQueue.length = 0;
    if (!on) return;
    var r = soundBuffer();
    if (r) startSending(r);
    else post({ type: 'seg-error', why: 'none' });
  }

  (function hookMse() {
    var MS = window.MediaSource;
    if (!MS || !MS.prototype || !window.SourceBuffer || !bufOf) return;
    var origUrl = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      var u = origUrl.apply(this, arguments);
      try { if (o instanceof MS && msUrl) msUrl.set(o, u); } catch (e) { /* keep the page working */ }
      return u;
    };
    var origAdd = MS.prototype.addSourceBuffer;
    MS.prototype.addSourceBuffer = function (type) {
      var sb = origAdd.apply(this, arguments);
      try { watchBuffer(this, sb, type); } catch (e) { /* keep the page working */ }
      return sb;
    };
    var origAppend = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function (data) {
      var r = bufOf.get(this);
      var noted = false;
      if (r) { try { noteAppend(r, this, data); noted = true; } catch (e) { /* keep the page working */ } }
      try {
        return origAppend.apply(this, arguments);
      } catch (err) {
        if (noted) r.pending.pop();
        throw err;
      }
    };
    if (SourceBuffer.prototype.changeType) {
      var origChange = SourceBuffer.prototype.changeType;
      SourceBuffer.prototype.changeType = function (type) {
        var r = bufOf.get(this);
        if (r) { r.kind = kindOf(type); r.mime = String(type); r.init = null; r.bad = false; r.soundTrack = -1; }
        return origChange.apply(this, arguments);
      };
    }
  })();

  // ---- Scan ahead (Smart Delay) ----
  // A site's player loads the sound only so far ahead of the playhead (often about 30 s). While
  // the video waits for Smart Delay, the app can ask for more: the same, visible video is moved
  // forward (muted, its picture hidden) so that the site's own player loads from there, and it
  // is moved back to where it waits once enough is loaded. This script fetches nothing itself.
  // The viewer's own Play ends it at once. The video's events during it are not reported.
  var scan = null;

  function scanTo(to) {
    var v = scan ? scan.v : (current || capVideo);
    if (!v || v.mediaKeys || !isFinite(v.duration)) { post({ type: 'scan', ok: false, why: 'novideo' }); return; }
    if (!scan) {
      if (!v.paused) v.pause();
      scan = { v: v, home: v.currentTime, muted: v.muted, opacity: v.style.opacity, back: false };
      v.muted = true;
      v.style.opacity = '0';
    }
    try {
      v.currentTime = Math.min(Math.max(0, to), Math.max(0, v.duration - 1));
    } catch (e) {
      scanEnd(false);
    }
  }

  function onScanEvent(type, v) {
    if (type === 'seeked' && !scan.back) post({ type: 'scan', ok: true, at: v.currentTime });
    if (type === 'play' && !scan.back) {
      // The viewer pressed Play: back to where the video waited, and play from there.
      if (navigator.userActivation && navigator.userActivation.isActive) scanEnd(true);
      else v.pause(); // the site's player resumed by itself after the jump
    }
  }

  function scanEnd(play) {
    var s = scan;
    if (!s) return;
    if (s.back) { if (play) s.play = true; return; }
    s.back = true;
    s.play = play;
    var v = s.v;
    var timer = 0;
    var finish = function () {
      v.removeEventListener('seeked', finish);
      clearTimeout(timer);
      if (scan !== s) return;
      v.muted = s.muted;
      v.style.opacity = s.opacity;
      scan = null;
      media('pause', v);
      if (s.play) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    };
    v.addEventListener('seeked', finish);
    timer = setTimeout(finish, 4000);
    try { if (!v.paused) v.pause(); v.currentTime = s.home; } catch (e) { finish(); }
  }

  window.addEventListener('pagehide', function () { if (scan) { scan.v.muted = scan.muted; scan.v.style.opacity = scan.opacity; } });

  bridge.onmessage = function (e) {
    var m;
    try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.cmd === 'tap') {
      tapOn = !!m.on;
      if (tapOn && current && !current.paused) tap(current);
    } else if (m.cmd === 'segs') {
      if (!m.on) scanEnd(false);
      segsOn(!!m.on);
    } else if (m.cmd === 'scan') {
      if (typeof m.to === 'number') scanTo(m.to);
    } else if (m.cmd === 'scan-end') {
      scanEnd(false);
    } else if (m.cmd === 'captions') {
      if (m.on) captionsOn(m.i | 0); else captionsOff();
    } else if (m.cmd === 'hold') {
      // Smart Delay: wait until enough English is ready ahead of the video.
      var hv = current || capVideo;
      if (hv && !hv.paused) hv.pause();
    } else if (m.cmd === 'resume') {
      var rv = current || capVideo;
      if (scan) scanEnd(true);
      else if (rv && rv.paused) { var p = rv.play(); if (p && p.catch) p.catch(function () {}); }
    } else if (m.cmd === 'tracks') {
      var tv = current || capVideo || document.querySelector('video');
      if (tv) reportTracks(tv);
    }
  };

  // ---- Captions: the video's own subtitle tracks (textTracks), read only when the viewer
  // starts AI subtitles. Cues of a track from another site load only if that site allows it
  // (CORS); nothing is fetched by this script.
  var capVideo = null;
  var capTrack = null;
  var capPrevMode = null;
  var capTimer = 0;
  var capSent = -1;
  var capStart = 0;
  var capWarned = false;
  var watched = typeof WeakSet === 'function' ? new WeakSet() : null;

  function tracksOf(v) {
    var out = [];
    var tt = v.textTracks;
    if (!tt) return out;
    for (var i = 0; i < tt.length; i++) {
      var t = tt[i];
      if (t.kind === 'subtitles' || t.kind === 'captions' || !t.kind) {
        out.push({ i: i, kind: t.kind || '', label: t.label || '', lang: t.language || '' });
      }
    }
    return out;
  }

  function reportTracks(v) {
    post({ type: 'tracks', top: window === window.top, list: tracksOf(v) });
  }

  function watchTracks(v) {
    if (!capVideo || v === current) capVideo = v;
    if (!v.textTracks || (watched && watched.has(v))) { reportTracks(v); return; }
    if (watched) watched.add(v);
    var again = function () { if (v === capVideo || v === current) reportTracks(v); };
    if (v.textTracks.addEventListener) {
      v.textTracks.addEventListener('addtrack', again);
      v.textTracks.addEventListener('removetrack', again);
    }
    reportTracks(v);
  }

  function captionsOn(i) {
    captionsOff();
    var v = current || capVideo || document.querySelector('video');
    if (!v || !v.textTracks || !v.textTracks[i]) {
      post({ type: 'cues-error', why: 'notrack' });
      return;
    }
    capVideo = v;
    capTrack = v.textTracks[i];
    capPrevMode = capTrack.mode;
    // Hidden: the cues load but the page does not draw them; the app shows them in English.
    capTrack.mode = 'hidden';
    capSent = -1;
    capStart = Date.now();
    capWarned = false;
    sendCues();
    capTimer = setInterval(sendCues, 1000);
  }

  function captionsOff() {
    if (capTimer) clearInterval(capTimer);
    capTimer = 0;
    if (capTrack && capPrevMode !== null) {
      try { capTrack.mode = capPrevMode; } catch (e) { /* track gone */ }
    }
    capTrack = null;
    capPrevMode = null;
  }

  function sendCues() {
    if (!capTrack) return;
    var cues = capTrack.cues;
    var n = cues ? cues.length : 0;
    if (n === 0) {
      if (!capWarned && Date.now() - capStart > 8000) {
        capWarned = true;
        post({ type: 'cues-error', why: 'empty' });
      }
      return;
    }
    if (n === capSent) return;
    // Cues can arrive in any order while a stream loads: send them all; the app drops repeats.
    var batch = [];
    for (var k = 0; k < n; k++) {
      var c = cues[k];
      if (typeof c.text !== 'string') continue;
      batch.push([c.startTime, c.endTime, c.text]);
      if (batch.length === 200) {
        post({ type: 'cues', lang: capTrack.language || '', cues: batch });
        batch = [];
      }
    }
    if (batch.length) post({ type: 'cues', lang: capTrack.language || '', cues: batch });
    capSent = n;
  }

  post({ type: 'hello', top: window === window.top });
})();
