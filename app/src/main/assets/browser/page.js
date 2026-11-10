// Injected at document start into every frame of pages opened in the built-in browser.
// It reports what the page's videos do (play, pause, seek, speed, time) and, only after the
// viewer starts AI subtitles, taps the playing video's sound with the Web Audio API and hands
// it to the app as 16 kHz mono PCM. It never downloads media, never touches protected (DRM)
// video and never taps media that the page's origin may not read, because Chromium would
// return silence for it and the viewer would lose the sound as well.
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

  bridge.onmessage = function (e) {
    var m;
    try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.cmd === 'tap') {
      tapOn = !!m.on;
      if (tapOn && current && !current.paused) tap(current);
    } else if (m.cmd === 'captions') {
      if (m.on) captionsOn(m.i | 0); else captionsOff();
    } else if (m.cmd === 'hold') {
      // Smart Delay: wait until enough English is ready ahead of the video.
      var hv = current || capVideo;
      if (hv && !hv.paused) hv.pause();
    } else if (m.cmd === 'resume') {
      var rv = current || capVideo;
      if (rv && rv.paused) { var p = rv.play(); if (p && p.catch) p.catch(function () {}); }
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
