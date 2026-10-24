package com.example.extension.managed.web

/**
 * Stages B, C, D, E: Bundled runtime JavaScript inspection and interaction script.
 * Injected safely into the controlled headless WebView environment.
 * Executes DOM media inspection, player state examination, script unpacking,
 * resource entry queries, and play activation to surface dynamic media URLs.
 */
object RuntimeMediaExtractionScript {

    val EARLY_HOOK_SCRIPT: String = """
        (function() {
            try {
                if (window.__cineStreamEarlyHooked) return;
                window.__cineStreamEarlyHooked = true;

                // Neutralize common adblock detection traps that halt player loading
                try {
                    if (typeof window.cRAds === 'undefined') window.cRAds = true;
                    if (typeof window.xRds === 'undefined') window.xRds = false;
                } catch(_) {}

                // Block popup ad windows from redirecting WebView
                try {
                    window.open = function() { return null; };
                } catch(_) {}

                function notifyBridge(url) {
                    if (!url || typeof url !== 'string') return;
                    url = url.trim();
                    if (url.indexOf('http://') !== 0 && url.indexOf('https://') !== 0 && url.indexOf('//') !== 0) return;
                    if (url.indexOf('//') === 0) url = 'https:' + url;

                    var lower = url.toLowerCase();
                    if (lower.indexOf('googleads') !== -1 || lower.indexOf('facebook') !== -1 || 
                        lower.indexOf('analytics') !== -1 || lower.indexOf('doubleclick') !== -1 ||
                        lower.indexOf('histats') !== -1) {
                        return;
                    }

                    if (window.SafeScraperBridge && window.SafeScraperBridge.postMessage) {
                        window.SafeScraperBridge.postMessage(JSON.stringify({
                            type: "EXTRACTION_RESULT",
                            streamUrl: url
                        }));
                    }
                }

                function isMediaCandidate(url) {
                    if (!url || typeof url !== 'string') return false;
                    url = url.replace(/\\\//g, '/');
                    var u = url.toLowerCase().split('?')[0];
                    return u.indexOf('.m3u8') !== -1 || u.indexOf('.mp4') !== -1 ||
                           u.indexOf('.mkv') !== -1 || u.indexOf('.webm') !== -1 ||
                           u.indexOf('.mpd') !== -1 || u.indexOf('.m4v') !== -1 ||
                           url.indexOf('videodelivery.net') !== -1 ||
                           url.indexOf('/hls/') !== -1 || url.indexOf('/hls2/') !== -1 ||
                           url.indexOf('.urlset/') !== -1;
                }

                function extractMediaUrlFromText(text) {
                    if (!text || typeof text !== 'string') return null;
                    var cleanText = text.replace(/\\\//g, '/');
                    var fileMatch = cleanText.match(/(?:file|source|src|video_url|stream_url|hlsUrl)\s*:\s*["'](https?:[^"']+)["']/i);
                    if (fileMatch && isMediaCandidate(fileMatch[1])) return fileMatch[1];
                    var urlMatch = cleanText.match(/https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4|mkv|webm|mpd)(?:\?[^"'\s<>\\]*)?/i);
                    if (urlMatch && isMediaCandidate(urlMatch[0])) return urlMatch[0];
                    return null;
                }

                // 1. Hook window.eval to observe unpacked/evaluated payloads
                if (window.eval) {
                    var origEval = window.eval;
                    window.eval = function(str) {
                        try {
                            if (typeof str === 'string') {
                                var found = extractMediaUrlFromText(str);
                                if (found) notifyBridge(found);
                            }
                        } catch(_) {}
                        return origEval.apply(this, arguments);
                    };
                }

                // 2. Hook window.Function
                if (window.Function) {
                    var origFunction = window.Function;
                    window.Function = function() {
                        try {
                            for (var a = 0; a < arguments.length; a++) {
                                if (typeof arguments[a] === 'string') {
                                    var found = extractMediaUrlFromText(arguments[a]);
                                    if (found) notifyBridge(found);
                                }
                            }
                        } catch(_) {}
                        return origFunction.apply(this, arguments);
                    };
                }

                // 3. Hook window.fetch
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        try {
                            var u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            if (isMediaCandidate(u)) notifyBridge(u);
                        } catch(_) {}
                        return origFetch.apply(this, arguments);
                    };
                }

                // 4. Hook XMLHttpRequest.prototype.open
                if (window.XMLHttpRequest && window.XMLHttpRequest.prototype && window.XMLHttpRequest.prototype.open) {
                    var origOpen = window.XMLHttpRequest.prototype.open;
                    window.XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (url && isMediaCandidate(url.toString())) notifyBridge(url.toString());
                        } catch(_) {}
                        return origOpen.apply(this, arguments);
                    };
                }

                // 5. Hook HTMLMediaElement.prototype.src
                if (window.HTMLMediaElement && window.HTMLMediaElement.prototype) {
                    var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if (desc && desc.set) {
                        var origSet = desc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                            set: function(val) {
                                try {
                                    if (val && isMediaCandidate(val)) notifyBridge(val);
                                } catch(_) {}
                                return origSet.call(this, val);
                            },
                            get: desc.get,
                            configurable: true
                        });
                    }
                }

                // 6. Hook HTMLSourceElement.prototype.src
                if (window.HTMLSourceElement && window.HTMLSourceElement.prototype) {
                    var srcDesc = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
                    if (srcDesc && srcDesc.set) {
                        var origSourceSet = srcDesc.set;
                        Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                            set: function(val) {
                                try {
                                    if (val && isMediaCandidate(val)) notifyBridge(val);
                                } catch(_) {}
                                return origSourceSet.call(this, val);
                            },
                            get: srcDesc.get,
                            configurable: true
                        });
                    }
                }

                // 7. Hook Element.prototype.setAttribute
                if (window.Element && window.Element.prototype && window.Element.prototype.setAttribute) {
                    var origSetAttr = Element.prototype.setAttribute;
                    Element.prototype.setAttribute = function(name, val) {
                        try {
                            if ((name === 'src' || name === 'data-src') && val && isMediaCandidate(val.toString())) {
                                notifyBridge(val.toString());
                            }
                        } catch(_) {}
                        return origSetAttr.apply(this, arguments);
                    };
                }

                // 8. Trap window.Playerjs definition
                var _playerjs = window.Playerjs;
                try {
                    Object.defineProperty(window, 'Playerjs', {
                        configurable: true,
                        enumerable: true,
                        get: function() { return _playerjs; },
                        set: function(val) {
                            if (typeof val === 'function' && !val.__cineStreamHooked) {
                                var orig = val;
                                _playerjs = function(cfg) {
                                    try {
                                        if (cfg) {
                                            var candidate = cfg.file || cfg.source || cfg.src || cfg.video_url || cfg.stream_url;
                                            if (candidate && isMediaCandidate(candidate)) notifyBridge(candidate);
                                        }
                                    } catch(_) {}
                                    return new orig(cfg);
                                };
                                _playerjs.prototype = orig.prototype;
                                _playerjs.__cineStreamHooked = true;
                                for (var k in orig) {
                                    try { _playerjs[k] = orig[k]; } catch(_) {}
                                }
                            } else {
                                _playerjs = val;
                            }
                        }
                    });
                } catch(_) {}

                // 9. Trap jwplayer definition
                var _jwplayer = undefined;
                try {
                    Object.defineProperty(window, 'jwplayer', {
                        configurable: true,
                        enumerable: true,
                        get: function() { return _jwplayer; },
                        set: function(val) {
                            if (typeof val === 'function' && !val.__cineStreamHooked) {
                                var orig = val;
                                _jwplayer = function() {
                                    var instance = orig.apply(this, arguments);
                                    if (instance && typeof instance.setup === 'function' && !instance.setup.__cineStreamHooked) {
                                        var origSetup = instance.setup;
                                        instance.setup = function(options) {
                                            try {
                                                if (options) {
                                                    if (options.file && isMediaCandidate(options.file)) notifyBridge(options.file);
                                                    if (Array.isArray(options.sources)) {
                                                        for (var s = 0; s < options.sources.length; s++) {
                                                            var sf = options.sources[s].file || options.sources[s].src;
                                                            if (sf && isMediaCandidate(sf)) notifyBridge(sf);
                                                        }
                                                    }
                                                }
                                            } catch(_) {}
                                            return origSetup.apply(this, arguments);
                                        };
                                        instance.setup.__cineStreamHooked = true;
                                    }
                                    return instance;
                                };
                                _jwplayer.prototype = orig.prototype;
                                _jwplayer.__cineStreamHooked = true;
                            } else {
                                _jwplayer = val;
                            }
                        }
                    });
                } catch(_) {}

                // 10. Hook Hls.prototype.loadSource if Hls library is loaded
                var _hls = undefined;
                try {
                    Object.defineProperty(window, 'Hls', {
                        configurable: true,
                        enumerable: true,
                        get: function() { return _hls; },
                        set: function(val) {
                            _hls = val;
                            try {
                                if (val && val.prototype && val.prototype.loadSource && !val.prototype.loadSource.__cineStreamHooked) {
                                    var origLoad = val.prototype.loadSource;
                                    val.prototype.loadSource = function(url) {
                                        if (url && isMediaCandidate(url)) notifyBridge(url);
                                        return origLoad.apply(this, arguments);
                                    };
                                    val.prototype.loadSource.__cineStreamHooked = true;
                                }
                            } catch(_) {}
                        }
                    });
                } catch(_) {}
            } catch(_) {}
        })();
    """.trimIndent()

    val SCRIPT: String = """
        (function() {
            try {
                function notifyBridge(url) {
                    if (!url || typeof url !== 'string') return;
                    url = url.trim();
                    if (url.indexOf('http://') !== 0 && url.indexOf('https://') !== 0 && url.indexOf('//') !== 0) return;
                    if (url.indexOf('//') === 0) url = 'https:' + url;

                    var lower = url.toLowerCase();
                    if (lower.indexOf('googleads') !== -1 || lower.indexOf('facebook') !== -1 || 
                        lower.indexOf('analytics') !== -1 || lower.indexOf('doubleclick') !== -1 ||
                        lower.indexOf('histats') !== -1) {
                        return;
                    }

                    if (window.SafeScraperBridge && window.SafeScraperBridge.postMessage) {
                        window.SafeScraperBridge.postMessage(JSON.stringify({
                            type: "EXTRACTION_RESULT",
                            streamUrl: url
                        }));
                    }
                }

                function isMediaCandidate(url) {
                    if (!url || typeof url !== 'string') return false;
                    url = url.replace(/\\\//g, '/');
                    var u = url.toLowerCase().split('?')[0];
                    return u.indexOf('.m3u8') !== -1 || u.indexOf('.mp4') !== -1 ||
                           u.indexOf('.mkv') !== -1 || u.indexOf('.webm') !== -1 ||
                           u.indexOf('.mpd') !== -1 || u.indexOf('.m4v') !== -1 ||
                           url.indexOf('videodelivery.net') !== -1 ||
                           url.indexOf('/hls/') !== -1 || url.indexOf('/hls2/') !== -1 ||
                           url.indexOf('.urlset/') !== -1;
                }

                function extractMediaUrlFromText(text) {
                    if (!text || typeof text !== 'string') return null;
                    var cleanText = text.replace(/\\\//g, '/');
                    var fileMatch = cleanText.match(/(?:file|source|src|video_url|stream_url|hlsUrl)\s*:\s*["'](https?:[^"']+)["']/i);
                    if (fileMatch && isMediaCandidate(fileMatch[1])) return fileMatch[1];
                    var urlMatch = cleanText.match(/https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4|mkv|webm|mpd)(?:\?[^"'\s<>\\]*)?/i);
                    if (urlMatch && isMediaCandidate(urlMatch[0])) return urlMatch[0];
                    return null;
                }

                function unpackDeanEdwardsDeterministic(text) {
                    if (!text || typeof text !== 'string') return [];
                    var results = [];
                    var headerPattern = /eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)[\s\S]*?\}\s*\(/g;
                    var match;

                    while ((match = headerPattern.exec(text)) !== null) {
                        var idx = match.index + match[0].length;
                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        if (idx >= text.length) break;

                        var pQuote = text[idx];
                        if (pQuote !== "'" && pQuote !== '"') continue;
                        idx++;

                        var payload = '';
                        var escaped = false;
                        while (idx < text.length) {
                            var ch = text[idx];
                            if (escaped) {
                                payload += ch;
                                escaped = false;
                            } else if (ch === '\\') {
                                escaped = true;
                                payload += ch;
                            } else if (ch === pQuote) {
                                idx++;
                                break;
                            } else {
                                payload += ch;
                            }
                            idx++;
                        }

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        if (text[idx] !== ',') continue;
                        idx++;

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        var radixStr = '';
                        while (idx < text.length && /\d/.test(text[idx])) {
                            radixStr += text[idx];
                            idx++;
                        }
                        if (!radixStr) continue;
                        var radix = parseInt(radixStr, 10);

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        if (text[idx] !== ',') continue;
                        idx++;

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        var countStr = '';
                        while (idx < text.length && /\d/.test(text[idx])) {
                            countStr += text[idx];
                            idx++;
                        }
                        if (!countStr) continue;
                        var count = parseInt(countStr, 10);

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        if (text[idx] !== ',') continue;
                        idx++;

                        while (idx < text.length && /\s/.test(text[idx])) idx++;
                        var wordsQuote = text[idx];
                        if (wordsQuote !== "'" && wordsQuote !== '"') continue;
                        idx++;

                        var wordsStr = '';
                        escaped = false;
                        while (idx < text.length) {
                            var ch = text[idx];
                            if (escaped) {
                                wordsStr += ch;
                                escaped = false;
                            } else if (ch === '\\') {
                                escaped = true;
                                wordsStr += ch;
                            } else if (ch === wordsQuote) {
                                idx++;
                                break;
                            } else {
                                wordsStr += ch;
                            }
                            idx++;
                        }

                        var words = wordsStr.split('|');

                        function encodeRadix(c, a) {
                            return (c < a ? '' : encodeRadix(Math.floor(c / a), a)) +
                                ((c = c % a) > 35 ? String.fromCharCode(c + 29) : c.toString(36));
                        }

                        var p = payload;
                        var totalCount = Math.max(count, words.length);
                        for (var c = totalCount - 1; c >= 0; c--) {
                            if (words[c]) {
                                var token = encodeRadix(c, radix);
                                p = p.replace(new RegExp('\\b' + token + '\\b', 'g'), words[c]);
                            }
                        }
                        results.push(p);
                    }
                    return results;
                }

                function scanScripts() {
                    var scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var content = scripts[i].textContent || scripts[i].innerHTML || '';
                        if (!content) continue;

                        // Check packed scripts via deterministic unpacker
                        var unpackedBlocks = unpackDeanEdwardsDeterministic(content);
                        for (var b = 0; b < unpackedBlocks.length; b++) {
                            var foundInUnpacked = extractMediaUrlFromText(unpackedBlocks[b]);
                            if (foundInUnpacked) {
                                notifyBridge(foundInUnpacked);
                                return;
                            }
                        }

                        // Check raw script text
                        var directFound = extractMediaUrlFromText(content);
                        if (directFound) {
                            notifyBridge(directFound);
                            return;
                        }
                    }
                }

                function scanDom() {
                    // Stage B: DOM media inspection
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.currentSrc && isMediaCandidate(v.currentSrc)) {
                            notifyBridge(v.currentSrc);
                            return;
                        }
                        if (v.src && isMediaCandidate(v.src)) {
                            notifyBridge(v.src);
                            return;
                        }
                        var dsrc = v.getAttribute('data-src');
                        if (dsrc && isMediaCandidate(dsrc)) {
                            notifyBridge(dsrc);
                            return;
                        }
                        var sources = v.querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            var s = sources[j];
                            if (s.src && isMediaCandidate(s.src)) {
                                notifyBridge(s.src);
                                return;
                            }
                            var sdsrc = s.getAttribute('data-src');
                            if (sdsrc && isMediaCandidate(sdsrc)) {
                                notifyBridge(sdsrc);
                                return;
                            }
                        }
                    }

                    // Standalone source elements
                    var allSources = document.querySelectorAll('source');
                    for (var k = 0; k < allSources.length; k++) {
                        var src = allSources[k].src || allSources[k].getAttribute('data-src');
                        if (src && isMediaCandidate(src)) {
                            notifyBridge(src);
                            return;
                        }
                    }
                }

                function scanGlobals() {
                    // Stage C: Runtime JS inspection
                    // 1. Explicit Player instances
                    try {
                        if (window.player) {
                            if (typeof window.player.api === 'function') {
                                var f = window.player.api('file');
                                if (f && isMediaCandidate(f)) { notifyBridge(f); return; }
                            }
                            if (window.player.file && isMediaCandidate(window.player.file)) {
                                notifyBridge(window.player.file);
                                return;
                            }
                        }
                    } catch(_) {}

                    // 2. JWPlayer
                    try {
                        if (window.jwplayer && typeof window.jwplayer === 'function') {
                            var jw = window.jwplayer();
                            if (jw && jw.getPlaylist) {
                                var pl = jw.getPlaylist();
                                if (pl && pl[0]) {
                                    if (pl[0].file) { notifyBridge(pl[0].file); return; }
                                    if (pl[0].sources && pl[0].sources[0] && pl[0].sources[0].file) {
                                        notifyBridge(pl[0].sources[0].file);
                                        return;
                                    }
                                }
                            }
                        }
                    } catch(_) {}

                    // 3. VideoJS
                    try {
                        if (window.videojs && typeof window.videojs.getAllPlayers === 'function') {
                            var vjsPlayers = window.videojs.getAllPlayers();
                            for (var vp = 0; vp < vjsPlayers.length; vp++) {
                                var csrc = vjsPlayers[vp].currentSrc();
                                if (csrc && isMediaCandidate(csrc)) { notifyBridge(csrc); return; }
                            }
                        }
                    } catch(_) {}

                    // 4. Inspect window properties generally for player/file configurations
                    try {
                        for (var key in window) {
                            if (!window[key] || typeof window[key] !== 'object') continue;
                            var obj = window[key];
                            if (typeof obj.file === 'string' && isMediaCandidate(obj.file)) {
                                notifyBridge(obj.file);
                                return;
                            }
                            if (typeof obj.api === 'function') {
                                var res = obj.api('file');
                                if (typeof res === 'string' && isMediaCandidate(res)) {
                                    notifyBridge(res);
                                    return;
                                }
                            }
                        }
                    } catch(_) {}

                    // 5. Performance resource entries
                    try {
                        if (window.performance && typeof window.performance.getEntriesByType === 'function') {
                            var entries = window.performance.getEntriesByType('resource');
                            for (var e = 0; e < entries.length; e++) {
                                var name = entries[e].name;
                                if (isMediaCandidate(name)) {
                                    notifyBridge(name);
                                    return;
                                }
                            }
                        }
                    } catch(_) {}
                }

                function dispatchClick(el) {
                    if (!el) return;
                    try {
                        if (typeof PointerEvent === 'function') {
                            el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
                            el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
                        }
                        if (typeof MouseEvent === 'function') {
                            el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                            el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
                        }
                        el.click();
                    } catch(_) {
                        try { el.click(); } catch(__) {}
                    }
                }

                function triggerPlayback() {
                    // Stage E: Player state inspection and play activation
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            v.muted = true;
                            var p = v.play();
                            if (p && p.catch) p.catch(function(){});
                        }
                    } catch(_) {}

                    try {
                        if (window.player && typeof window.player.api === 'function') {
                            window.player.api('play');
                        }
                    } catch(_) {}

                    try {
                        var selectors = [
                            '.play', '.vjs-big-play-button', 'button[class*="play"]', '[id*="play"]',
                            '.play-button', '.jw-display-icon-container', '.ytp-play-button',
                            '#btnpc', '#play_btn', '.btn-play', '.play_btn', '#player', '#vplayer',
                            'div[onclick*="start"]', '.overlay', 'div.play_button', '.play-icon',
                            'div[id*="player"]', 'a.play-btn', '.click-to-play', '.play-wrapper'
                        ];
                        var playBtns = document.querySelectorAll(selectors.join(', '));
                        for (var b = 0; b < playBtns.length; b++) {
                            dispatchClick(playBtns[b]);
                        }
                    } catch(_) {}
                }

                // Initial scan
                scanDom();
                scanGlobals();
                scanScripts();
                triggerPlayback();

                // Setup MutationObserver for dynamic player initialization
                try {
                    var targetNode = document.body || document.documentElement;
                    if (targetNode && window.MutationObserver) {
                        var observer = new MutationObserver(function() {
                            scanDom();
                            scanGlobals();
                        });
                        observer.observe(targetNode, {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: ['src', 'data-src']
                        });
                    }
                } catch(_) {}

                // Staggered observations (Stages B, C, E)
                var checkTimes = [300, 800, 1800, 3500, 5500];
                for (var t = 0; t < checkTimes.length; t++) {
                    setTimeout(function() {
                        scanDom();
                        scanGlobals();
                        scanScripts();
                        triggerPlayback();
                    }, checkTimes[t]);
                }
            } catch(e) {}
        })();
    """.trimIndent()
}
