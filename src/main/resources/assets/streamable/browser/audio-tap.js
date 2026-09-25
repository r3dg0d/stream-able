// Stream-able browser audio tap.
//
// The JCEF build MCEF Modern ships has no CefAudioHandler, so Chromium's audio
// cannot be intercepted natively. Instead, this script routes what the page
// plays through Web Audio inside the page:
//
//   <audio>/<video> elements  -> MediaElementSource ┐
//   the page's own Web Audio  -> (connect to        ├─> hub: volume ─┬─> monitor gain -> speakers
//                                 destination)      ┘                └─> tap -> 16-bit PCM -> Java
//
// and hands 16-bit PCM to Stream-able through a JCEF message router query
// (window.streamableAudioQuery). Nothing leaves the computer except in the
// player's own recording or stream.
//
// Configuration arrives as the __SA_CONFIG__ object; re-injecting the script
// only updates it. Not captured: speechSynthesis, audio inside iframes.
(function (config) {
  'use strict';
  var existing = window.__streamableAudioTap;
  if (existing) {
    existing.configure(config);
    return;
  }
  if (typeof AudioContext === 'undefined' || typeof AudioNode === 'undefined') {
    return;
  }

  var CHUNK = 2048;
  var state = { monitor: true, capture: false, volume: 1 };
  var hubs = new Map();
  var internal = new WeakSet();
  var hookedMedia = new WeakSet();
  var ownContext = null;
  var nextStream = 1;
  var counters = { hooked: 0, hookFailed: 0, chunks: 0 };
  var origConnect = AudioNode.prototype.connect;
  var origDisconnect = AudioNode.prototype.disconnect;

  function isLiveDestination(node) {
    return typeof AudioDestinationNode !== 'undefined' && node instanceof AudioDestinationNode
        && !(typeof OfflineAudioContext !== 'undefined' && node.context instanceof OfflineAudioContext);
  }

  function toBase64(int16) {
    var bytes = new Uint8Array(int16.buffer, int16.byteOffset, int16.byteLength);
    var binary = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }

  function send(stream, rate, pcm) {
    var query = window.streamableAudioQuery;
    if (typeof query !== 'function') {
      return;
    }
    try {
      query({
        request: 'sa-pcm:' + stream + ':' + Math.round(rate) + ':2:' + toBase64(pcm),
        persistent: false,
        onSuccess: function () {},
        onFailure: function () {}
      });
    } catch (e) {
      // The bridge is not there (engine without the router): keep playing normally.
    }
  }

  function hubFor(ctx) {
    var hub = hubs.get(ctx);
    if (hub) {
      return hub;
    }
    var input = ctx.createGain();
    var volume = ctx.createGain();
    var monitor = ctx.createGain();
    var tap = ctx.createScriptProcessor(CHUNK, 2, 2);
    [input, volume, monitor, tap].forEach(function (n) { internal.add(n); });
    origConnect.call(input, volume);
    origConnect.call(volume, monitor);
    origConnect.call(monitor, ctx.destination);
    origConnect.call(volume, tap);
    origConnect.call(tap, ctx.destination);   // a processor only runs while connected; it outputs silence
    var stream = nextStream++;
    tap.onaudioprocess = function (event) {
      if (!state.capture) {
        return;
      }
      var buffer = event.inputBuffer;
      var left = buffer.getChannelData(0);
      var right = buffer.numberOfChannels > 1 ? buffer.getChannelData(1) : left;
      var audible = false;
      for (var i = 0; i < left.length; i++) {
        if (left[i] !== 0 || right[i] !== 0) {
          audible = true;
          break;
        }
      }
      if (!audible) {
        return;   // silence costs nothing to send: the mixer pads gaps itself
      }
      var pcm = new Int16Array(left.length * 2);
      for (var j = 0; j < left.length; j++) {
        var l = Math.max(-1, Math.min(1, left[j]));
        var r = Math.max(-1, Math.min(1, right[j]));
        pcm[2 * j] = l < 0 ? l * 0x8000 : l * 0x7FFF;
        pcm[2 * j + 1] = r < 0 ? r * 0x8000 : r * 0x7FFF;
      }
      counters.chunks++;
      send(stream, ctx.sampleRate, pcm);
    };
    hub = {
      input: input,
      apply: function () {
        volume.gain.value = state.volume;
        monitor.gain.value = state.monitor ? 1 : 0;
      }
    };
    hub.apply();
    hubs.set(ctx, hub);
    return hub;
  }

  // The page's own Web Audio: anything connected to a live destination goes
  // through that context's hub instead.
  AudioNode.prototype.connect = function (destination) {
    if (!internal.has(this) && isLiveDestination(destination)) {
      var args = Array.prototype.slice.call(arguments);
      args[0] = hubFor(destination.context).input;
      origConnect.apply(this, args);
      return destination;
    }
    return origConnect.apply(this, arguments);
  };
  AudioNode.prototype.disconnect = function (destination) {
    if (!internal.has(this) && isLiveDestination(destination)) {
      var args = Array.prototype.slice.call(arguments);
      args[0] = hubFor(destination.context).input;
      return origDisconnect.apply(this, args);
    }
    return origDisconnect.apply(this, arguments);
  };

  // Media elements, including `new Audio(url)` that is never added to the page.
  function hookMedia(element) {
    if (!element || hookedMedia.has(element)) {
      return;
    }
    hookedMedia.add(element);
    try {
      if (!ownContext) {
        ownContext = new AudioContext();
      }
      if (ownContext.state === 'suspended') {
        ownContext.resume();
      }
      var source = ownContext.createMediaElementSource(element);
      source.connect(ownContext.destination);   // through the hub, via the patched connect
      counters.hooked++;
    } catch (e) {
      counters.hookFailed++;
      // Already routed by the page itself (then its own graph is tapped), or unsupported.
    }
  }

  var origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    hookMedia(this);
    return origPlay.apply(this, arguments);
  };
  document.addEventListener('play', function (event) {
    if (event.target instanceof HTMLMediaElement) {
      hookMedia(event.target);
    }
  }, true);
  Array.prototype.forEach.call(document.querySelectorAll('audio, video'), function (element) {
    if (!element.paused || element.autoplay) {
      hookMedia(element);
    }
  });

  var tapApi = {
    // For diagnostics: what the tap has routed so far.
    stats: function () {
      return {
        contexts: hubs.size,
        hooked: counters.hooked,
        hookFailed: counters.hookFailed,
        chunks: counters.chunks,
        mediaContext: ownContext ? ownContext.state : 'none'
      };
    },
    configure: function (next) {
      state.monitor = !!next.monitor;
      state.capture = !!next.capture;
      state.volume = typeof next.volume === 'number' ? Math.max(0, Math.min(2, next.volume)) : 1;
      hubs.forEach(function (hub) { hub.apply(); });
    }
  };
  Object.defineProperty(window, '__streamableAudioTap', { value: tapApi, configurable: false });
  tapApi.configure(config);
})(__SA_CONFIG__);
