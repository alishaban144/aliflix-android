package com.aliflix.app.player

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import org.json.JSONArray
import org.json.JSONObject

internal fun shouldResolveMobileMoviepireEpisode(selection: PlaybackSelection): Boolean =
    selection.source.identity.usesMoviepire &&
        selection.media.type == MediaType.TV

internal fun mobileMoviepireEpisodeBootstrapUrl(selection: PlaybackSelection): String? =
    selection.takeIf(::shouldResolveMobileMoviepireEpisode)?.let {
        "${it.source.baseUrl.trimEnd('/')}/series/${it.media.id}"
    }

/**
 * Replays Moviepire's own exact episode flow. Moviepire decides movie-versus-series from its
 * router query, while its details page supplies the authoritative episode link and `me` count.
 * Selecting the original season control and clicking that original link keeps this resilient to
 * player-source changes without constructing any embedded-player URL in Aliflix.
 */
internal fun moviepireExactEpisodeResolverScript(
    tmdbId: Int,
    seasonNumber: Int,
    episodeNumber: Int,
): String =
    """
    (() => {
      const expectedId = $tmdbId;
      const expectedSeason = $seasonNumber;
      const expectedEpisode = $episodeNumber;
      const current = new URL(location.href);
      const watchPath = "/watch/" + expectedId;
      const seriesPath = "/series/" + expectedId;
      const currentPath = current.pathname.replace(/\/+$/, "") || "/";

      if (currentPath === watchPath) {
        const exact = Number(current.searchParams.get("s")) === expectedSeason &&
          Number(current.searchParams.get("e")) === expectedEpisode;
        if (!exact) return "wrong-route";
        return document.querySelector(".player iframe, iframe[title^='Video Player']")
          ? "ready"
          : "waiting-player";
      }
      if (currentPath !== seriesPath) return "wrong-route";

      const seasonSelect = Array.from(document.querySelectorAll("select")).find((candidate) => {
        const options = Array.from(candidate.options || []);
        return options.some((option) => Number(option.value) === expectedSeason) &&
          options.some((option) => /season/i.test(option.textContent || ""));
      });
      if (!seasonSelect) return "waiting-details";
      if (Number(seasonSelect.value) !== expectedSeason) {
        seasonSelect.value = String(expectedSeason);
        seasonSelect.dispatchEvent(new Event("input", { bubbles: true }));
        seasonSelect.dispatchEvent(new Event("change", { bubbles: true }));
        return "season-changed";
      }

      const episodeLinks = Array.from(document.querySelectorAll("a[href]"));
      const exactLink = episodeLinks.find((link) => {
        try {
          const target = new URL(link.href, location.href);
          return target.pathname.replace(/\/+$/, "") === watchPath &&
            Number(target.searchParams.get("s")) === expectedSeason &&
            Number(target.searchParams.get("e")) === expectedEpisode;
        } catch (_) {
          return false;
        }
      });
      if (exactLink) {
        exactLink.click();
        return "episode-clicked";
      }

      const episodeSection = Array.from(document.querySelectorAll("section,div")).find((node) => {
        const heading = node.querySelector(":scope > h2, :scope > h3, :scope > .title-row h3");
        return /episodes/i.test(heading?.textContent || "");
      });
      const expandButton = episodeSection?.querySelector(
        ".title-extend button, button [class*='chevron-down']"
      )?.closest("button");
      if (expandButton) {
        expandButton.click();
        return "episodes-expanded";
      }
      return "waiting-episodes";
    })();
    """.trimIndent()

internal fun mobileMoviepireShieldOriginRules(sourceHost: String): Set<String> {
    val cleanSourceHost = sourceHost
        .trim()
        .lowercase()
        .removePrefix("www.")
        .takeIf { host ->
            host.isNotBlank() &&
                host.none(Char::isWhitespace) &&
                host.all { character ->
                    character.isLetterOrDigit() || character == '.' || character == '-'
                }
        }
    return (PlaybackNavigationPolicy.moviepirePlayerDocumentHosts + listOfNotNull(cleanSourceHost))
        .flatMapTo(linkedSetOf()) { host ->
            listOf("https://$host", "https://*.$host")
        }
}

/**
 * Finds Moviepire's own source selector without relying on a server label or a generated class
 * name. The selector is tagged before it is hidden so native choices can still dispatch the
 * original React input/change events.
 */
internal fun moviepireServerDiscoveryScript(): String =
    """
    (() => {
      const normalize = (value) => String(value || "").replace(/\s+/g, " ").trim();
      const candidates = Array.from(document.querySelectorAll("select"));
      const select = candidates.find((candidate) => {
        if (candidate.options.length < 1) return false;
        const identity = normalize([
          candidate.getAttribute("aria-label"),
          candidate.getAttribute("name"),
          candidate.id,
          candidate.getAttribute("data-testid")
        ].filter(Boolean).join(" ")).toLowerCase();
        const positivelyNamed = /(?:video\s*)?(?:source|server|stream)/i.test(identity);
        const controls = candidate.closest(".player-controls,[class*='player-controls']");
        const playerContext = Boolean(controls && (
          controls.parentElement?.querySelector("iframe,video") ||
          document.querySelector("iframe[src],video")
        ));
        return positivelyNamed || playerContext;
      });
      if (!select) return "[]";

      select.dataset.aliflixServerSelect = "true";
      const wrapperControls = select.closest(".player-controls,[class*='player-controls']");
      const servers = Array.from(select.options).map((option, index) => {
        const label = normalize(option.label || option.textContent || option.value);
        const key = "option:" + index + ":" + String(option.value || "");
        option.dataset.aliflixServerKey = key;
        return { key, label, selected: option.selected || select.selectedIndex === index };
      }).filter((option) => option.label.length > 0);
      if (servers.length === 0) return "[]";

      select.style.setProperty("visibility", "hidden", "important");
      select.style.setProperty("opacity", "0", "important");
      select.style.setProperty("pointer-events", "none", "important");
      select.setAttribute("aria-hidden", "true");
      select.tabIndex = -1;
      const downloadSelector = [
        "a[download]",
        "a[href*='download' i]",
        "[aria-label*='download' i]",
        "[title*='download' i]",
        "[data-testid*='download' i]",
        "[class*='download' i]"
      ].join(",");
      Array.from(document.querySelectorAll(downloadSelector)).forEach((control) => {
        const target = control.closest("a,button") || control;
        if (!target.querySelector("iframe,video")) {
          target.style.setProperty("display", "none", "important");
          target.setAttribute("aria-hidden", "true");
          target.tabIndex = -1;
        }
      });
      if (wrapperControls && !wrapperControls.querySelector("iframe,video")) {
        wrapperControls.dataset.aliflixWrapperControls = "true";
        wrapperControls.style.setProperty("display", "none", "important");
      }
      return JSON.stringify(servers);
    })();
    """.trimIndent()

internal fun parseMoviepireServerDiscovery(payload: String): List<MoviepireServerOption> =
    runCatching {
        val array = JSONArray(payload)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key").trim()
            val label = item.optString("label").trim()
            if (key.isBlank() || label.isBlank()) return@mapNotNull null
            MoviepireServerOption(
                key = key,
                label = label,
                selected = item.optBoolean("selected"),
            )
        }.distinctBy(MoviepireServerOption::key)
    }.getOrDefault(emptyList())

/** Runs only in origin-restricted Moviepire/player documents registered with AndroidX WebKit. */
internal fun mobileMoviepireProgressBridgeScript(): String =
    """
    (() => {
      "use strict";
      if (window.__aliflixPlaybackProgressBridge === true) return true;
      window.__aliflixPlaybackProgressBridge = true;
      const bridge = window.AliflixPlaybackProgress;
      if (!bridge || typeof bridge.postMessage !== "function") return false;

      let activeVideo = null;
      let lastTimeUpdateSentAt = 0;
      let pendingSeekSeconds = null;
      let seekGeneration = 0;
      let subtitleCues = [];
      let subtitleDelaySeconds = 0;
      let subtitleFontPercent = 100;
      let subtitleLanguage = "";
      let subtitleLabel = "Aliflix";
      const subtitleTracks = new WeakMap();
      const minimumDurationSeconds = 60;
      const validNumber = (value) => Number.isFinite(value) && value >= 0;
      const videoScore = (video) => {
        if (!video) return -1;
        const rect = video.getBoundingClientRect();
        const area = Math.max(0, rect.width) * Math.max(0, rect.height);
        const playingScore = video.paused ? 0 : 1000000000;
        const visibleScore = getComputedStyle(video).visibility === "hidden" ? 0 : 100000000;
        return playingScore + visibleScore + area;
      };
      const activate = (video) => {
        if (!activeVideo || !activeVideo.isConnected ||
            video === activeVideo || videoScore(video) >= videoScore(activeVideo)) {
          activeVideo = video;
        }
        return activeVideo === video;
      };
      const seekActiveVideo = () => {
        const video = activeVideo && activeVideo.isConnected
          ? activeVideo
          : document.querySelector("video");
        const seconds = pendingSeekSeconds;
        if (!video || !validNumber(seconds) ||
            !Number.isFinite(video.duration) || video.duration <= 0) return false;
        activate(video);
        let target = Math.min(seconds, Math.max(0, video.duration - 0.25));
        try {
          if (video.seekable && video.seekable.length > 0) {
            const firstStart = video.seekable.start(0);
            if (target < firstStart) target = firstStart;
          }
          if (Math.abs(video.currentTime - target) <= 0.05) {
            pendingSeekSeconds = null;
            return true;
          }
          video.currentTime = target;
          return true;
        } catch (_) {
          return false;
        }
      };
      const requestSeek = (seconds) => {
        if (!validNumber(seconds)) return;
        pendingSeekSeconds = seconds;
        const generation = ++seekGeneration;
        [0, 100, 300, 700, 1400, 2600, 4500, 7500, 11000].forEach((delay) => {
          window.setTimeout(() => {
            if (generation === seekGeneration && pendingSeekSeconds !== null) {
              seekActiveVideo();
            }
          }, delay);
        });
      };
      const controlPlayback = (shouldPlay) => {
        const video = activeVideo && activeVideo.isConnected
          ? activeVideo
          : document.querySelector("video");
        if (!video || video.ended) return false;
        activate(video);
        try {
          if (shouldPlay === true) {
            const result = video.play();
            if (result && typeof result.catch === "function") result.catch(() => {});
          } else {
            video.pause();
          }
          return true;
        } catch (_) {
          return false;
        }
      };
      const ensureSubtitleStyle = () => {
        let style = document.getElementById("aliflix-native-subtitle-style");
        if (!style) {
          style = document.createElement("style");
          style.id = "aliflix-native-subtitle-style";
          (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = `video::cue {
          color: #ffffff;
          background-color: rgba(8, 6, 16, 0.82);
          font-size: ${'$'}{subtitleFontPercent}%;
          font-family: system-ui, sans-serif;
          font-weight: 650;
          text-shadow: 0 1px 3px rgba(0, 0, 0, 0.96);
        }`;
      };
      const clearVideoSubtitles = (video) => {
        const previous = subtitleTracks.get(video);
        if (!previous) return;
        try {
          previous.mode = "disabled";
          Array.from(previous.cues || []).forEach((cue) => previous.removeCue(cue));
        } catch (_) {}
        subtitleTracks.delete(video);
      };
      const applySubtitles = (video) => {
        if (!video) return;
        clearVideoSubtitles(video);
        ensureSubtitleStyle();
        if (!Array.isArray(subtitleCues) || subtitleCues.length === 0) return;
        const Cue = window.VTTCue || window.TextTrackCue;
        if (typeof Cue !== "function" || typeof video.addTextTrack !== "function") return;
        try {
          const track = video.addTextTrack("subtitles", subtitleLabel, subtitleLanguage);
          track.mode = "showing";
          subtitleCues.forEach((raw, index) => {
            if (!Array.isArray(raw) || raw.length < 3) return;
            const start = Number(raw[0]) + subtitleDelaySeconds;
            const end = Number(raw[1]) + subtitleDelaySeconds;
            const text = String(raw[2] || "").slice(0, 500);
            if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end <= 0 || !text) return;
            const cue = new Cue(Math.max(0, start), Math.max(0.01, end), text);
            cue.id = `aliflix-${'$'}{index}`;
            try {
              cue.snapToLines = true;
              cue.line = -3;
              cue.position = 50;
              cue.align = "center";
            } catch (_) {}
            track.addCue(cue);
          });
          subtitleTracks.set(video, track);
        } catch (_) {}
      };
      const applySubtitlesToAllVideos = () => {
        document.querySelectorAll("video").forEach(applySubtitles);
      };
      const setSubtitles = (data) => {
        const rawCues = Array.isArray(data.cues) ? data.cues.slice(0, 6000) : [];
        subtitleCues = rawCues.filter((cue) =>
          Array.isArray(cue) && cue.length >= 3 &&
          Number.isFinite(Number(cue[0])) && Number.isFinite(Number(cue[1])) &&
          Number(cue[1]) > Number(cue[0]) && String(cue[2] || "").length > 0
        );
        const delay = Number(data.delaySeconds);
        subtitleDelaySeconds = Number.isFinite(delay) ? Math.max(-10, Math.min(10, delay)) : 0;
        const font = Number(data.fontPercent);
        subtitleFontPercent = Number.isFinite(font) ? Math.max(70, Math.min(180, font)) : 100;
        subtitleLanguage = String(data.language || "").slice(0, 8).toLowerCase();
        subtitleLabel = String(data.label || "Aliflix").slice(0, 80);
        applySubtitlesToAllVideos();
      };
      const clearSubtitles = () => {
        subtitleCues = [];
        document.querySelectorAll("video").forEach(clearVideoSubtitles);
      };
      const post = (event, video) => {
        if (!video || !validNumber(video.currentTime) ||
            !Number.isFinite(video.duration) || video.duration < minimumDurationSeconds ||
            !activate(video)) return;
        if (pendingSeekSeconds !== null) {
          if (Math.abs(video.currentTime - pendingSeekSeconds) <= 0.05) {
            pendingSeekSeconds = null;
          } else {
            seekActiveVideo();
          }
        }
        let bufferedSeconds = 0;
        try {
          if (video.buffered && video.buffered.length > 0) {
            for (let i = video.buffered.length - 1; i >= 0; i--) {
              if (video.buffered.start(i) <= video.currentTime + 0.5) {
                bufferedSeconds = video.buffered.end(i);
                break;
              }
            }
          }
        } catch (_) {}
        try {
          bridge.postMessage(JSON.stringify({
            event,
            positionSeconds: video.currentTime,
            durationSeconds: video.duration,
            bufferedSeconds: bufferedSeconds,
            documentHidden: document.visibilityState !== "visible",
            nativeStream: window.__aliflixNativeStream?.(video) || null
          }));
        } catch (_) {}
      };
      const onTimeUpdate = (event) => {
        const now = Date.now();
        if (now - lastTimeUpdateSentAt < 250) return;
        lastTimeUpdateSentAt = now;
        post("timeupdate", event.currentTarget);
      };
      const attach = (video) => {
        if (!video || video.dataset.aliflixProgressAttached === "true") return;
        video.dataset.aliflixProgressAttached = "true";
        try {
          video.controls = false;
          video.removeAttribute("controls");
        } catch (_) {}
        activate(video);
        applySubtitles(video);
        video.addEventListener("loadedmetadata", (event) => {
          seekActiveVideo();
          post("loadedmetadata", event.currentTarget);
        });
        video.addEventListener("timeupdate", onTimeUpdate);
        ["durationchange", "canplay", "progress"].forEach((eventName) => {
          video.addEventListener(eventName, seekActiveVideo);
        });
        video.addEventListener("playing", (event) => {
          seekActiveVideo();
          post("playing", event.currentTarget);
        });
        ["play", "pause", "seeking", "seeked", "ended"].forEach((eventName) => {
          video.addEventListener(eventName, (event) => post(eventName, event.currentTarget));
        });
        if (video.readyState >= 1) {
          seekActiveVideo();
          post("loadedmetadata", video);
        }
      };
      const scan = () => document.querySelectorAll("video").forEach(attach);
      new MutationObserver(scan).observe(document.documentElement || document, {
        childList: true,
        subtree: true
      });
      document.addEventListener("DOMContentLoaded", scan, { once: true });
      scan();
      window.setInterval(() => {
        if (activeVideo && !activeVideo.paused) {
          post("timeupdate", activeVideo);
        }
      }, 300);

      const receiveCommand = (raw) => {
        let data = raw;
        if (typeof raw === "string") {
          try { data = JSON.parse(raw); } catch (_) { return false; }
        }
        if (!data) return false;
        if (data.type === "aliflix-seek") {
          requestSeek(Number(data.seconds));
          return true;
        }
        if (data.type === "aliflix-playback-control") {
          controlPlayback(data.shouldPlay);
          return true;
        }
        if (data.type === "aliflix-speed") {
          const s = Number(data.speed);
          if (Number.isFinite(s) && s > 0) {
            const v = activeVideo || document.querySelector("video");
            if (v) v.playbackRate = s;
          }
          return true;
        }
        if (data.type === "aliflix-subtitles") {
          setSubtitles(data);
          return true;
        }
        if (data.type === "aliflix-subtitles-clear") {
          clearSubtitles();
          return true;
        }
        return false;
      };
      bridge.onmessage = (event) => receiveCommand(event.data);
      window.addEventListener("message", (event) => {
        const data = event.data;
        if (!receiveCommand(data)) return;
        // Relay down through arbitrary iframe depth. Android also replies directly to the exact
        // frame that reported progress, while this path covers players that become ready first.
        document.querySelectorAll("iframe").forEach((frame) => {
          try { frame.contentWindow?.postMessage(data, "*"); } catch (_) {}
        });
      });
      return true;
    })();
    """.trimIndent()

/**
 * Runs at document start in Moviepire and each of its player frames. Native interception remains
 * authoritative; this layer prevents inline/dynamically-generated ad code and removes scam UI
 * without altering video, player controls, fullscreen, or Cast APIs.
 */
internal fun mobileMoviepireAdShieldScript(): String {
    val blockedHostsJson = PlaybackNavigationPolicy.blockedAdvertisingHosts
        .sorted()
        .joinToString(separator = ",") { host -> JSONObject.quote(host) }
    return """
        (() => {
          "use strict";
          if (window.__aliflixMoviepireAdShield === true) return true;
          try {
            Object.defineProperty(window, "__aliflixMoviepireAdShield", {
              value: true,
              configurable: false,
              writable: false
            });
          } catch (_) {
            window.__aliflixMoviepireAdShield = true;
          }

          const blockedHosts = [$blockedHostsJson];
          const cleanHost = (host) => String(host || "").toLowerCase().replace(/^www\./, "");
          const hostBlocked = (host) => {
            const clean = cleanHost(host);
            return blockedHosts.some((blocked) =>
              clean === blocked || clean.endsWith("." + blocked)
            );
          };
          const urlBlocked = (raw) => {
            if (!raw) return false;
            try {
              const target = new URL(String(raw), location.href);
              if (hostBlocked(target.hostname)) return true;
              const path = target.pathname.toLowerCase();
              if (cleanHost(target.hostname).endsWith("vidrock.ru") && path === "/sbx.js") {
                return true;
              }
              return ["/popunder", "/clickunder", "/push-sdk", "/adserver/", "/ads/"]
                .some((token) => path.includes(token));
            } catch (_) {
              return false;
            }
          };
          const resourceUrl = (node) => {
            if (!node || !node.tagName) return "";
            const tag = node.tagName.toUpperCase();
            if (tag === "OBJECT") return node.getAttribute("data") || "";
            if (tag === "SCRIPT" || tag === "IFRAME" || tag === "EMBED" || tag === "SOURCE") {
              return node.getAttribute("src") || "";
            }
            if (tag === "A" || tag === "LINK") return node.getAttribute("href") || "";
            return "";
          };
          const blockedNode = (node) => urlBlocked(resourceUrl(node));

          const nativeAppendChild = Node.prototype.appendChild;
          const nativeInsertBefore = Node.prototype.insertBefore;
          Node.prototype.appendChild = function(node) {
            if (blockedNode(node)) return node;
            return nativeAppendChild.call(this, node);
          };
          Node.prototype.insertBefore = function(node, reference) {
            if (blockedNode(node)) return node;
            return nativeInsertBefore.call(this, node, reference);
          };

          const nativeAddEventListener = EventTarget.prototype.addEventListener;
          EventTarget.prototype.addEventListener = function(type, listener, options) {
            if (this === document && type === "click" && listener) {
              let source = "";
              try { source = Function.prototype.toString.call(listener).toLowerCase(); } catch (_) {}
              if ([
                "adnetworks", "adlastfired", "popunder", "clickunder",
                "profiton", "hilltopads", "monetag"
              ].some((token) => source.includes(token))) {
                return;
              }
            }
            return nativeAddEventListener.call(this, type, listener, options);
          };

          const noPopup = () => null;
          try {
            Object.defineProperty(window, "open", {
              value: noPopup,
              configurable: false,
              writable: false
            });
          } catch (_) {
            window.open = noPopup;
          }

          const adSelector = [
            "#paldo-ad",
            "ins.adsbygoogle",
            "[id^='google_ads']",
            "[id^='ad-']",
            "[id^='ads-']",
            "[class~='ad']",
            "[class^='ad-']",
            "[class*=' ad-']",
            "[class*='ad-container']",
            "[class*='ad-banner']",
            "[class*='advertisement']",
            "[class*='adsbox']",
            "[data-ad-slot]",
            "[data-ad-unit]",
            "[data-advertisement]",
            "[aria-label='advertisement']",
            "[aria-label='sponsored']"
          ].join(",");
          const scamPhrases = [
            "system warning detected",
            "device has been infected",
            "security alert",
            "file is ready to download",
            "install the recommended security app",
            "run the app to remove all threats",
            "tap the button below",
            "time remaining",
            "your sim card",
            "remove all threats"
          ];
          const ownsVideo = (node) => Boolean(
            node && (
              node.matches && node.matches("video, audio") ||
              node.querySelector && node.querySelector("video, audio")
            )
          );
          const removeNode = (node) => {
            if (!node || ownsVideo(node)) return;
            try { node.remove(); } catch (_) {}
          };
          const ensureStyle = () => {
            if (document.getElementById("aliflix-moviepire-ad-style")) return;
            const parent = document.head || document.documentElement;
            if (!parent) return;
            const style = document.createElement("style");
            style.id = "aliflix-moviepire-ad-style";
            style.textContent = adSelector + "{" +
              "display:none!important;visibility:hidden!important;" +
              "pointer-events:none!important;width:0!important;height:0!important;" +
              "min-width:0!important;min-height:0!important;margin:0!important;padding:0!important}";
            nativeAppendChild.call(parent, style);
          };
          const cleanup = () => {
            ensureStyle();
            try {
              document.querySelectorAll(adSelector).forEach(removeNode);
              document.querySelectorAll(
                "script[src],iframe[src],object[data],embed[src],source[src],link[href]"
              ).forEach((node) => {
                if (blockedNode(node)) removeNode(node);
              });
              document.querySelectorAll("a[href]").forEach((anchor) => {
                if (urlBlocked(anchor.getAttribute("href"))) {
                  removeNode(anchor);
                  return;
                }
                const style = getComputedStyle(anchor);
                const rect = anchor.getBoundingClientRect();
                const viewport = Math.max(1, innerWidth * innerHeight);
                const coverage = (rect.width * rect.height) / viewport;
                const external = (() => {
                  try {
                    return cleanHost(new URL(anchor.href, location.href).hostname) !==
                      cleanHost(location.hostname);
                  } catch (_) { return false; }
                })();
                if (
                  external &&
                  !ownsVideo(anchor) &&
                  (style.position === "fixed" || style.position === "sticky") &&
                  coverage > 0.2
                ) {
                  removeNode(anchor);
                }
              });
              document.querySelectorAll(
                "[role='dialog'],dialog,aside,[class*='modal'],[class*='popup'],[class*='overlay']"
              ).forEach((node) => {
                if (ownsVideo(node)) return;
                const copy = String(node.innerText || node.textContent || "").toLowerCase();
                const matches = scamPhrases.reduce(
                  (count, phrase) => count + (copy.includes(phrase) ? 1 : 0),
                  0
                );
                if (matches >= 2) removeNode(node);
              });
            } catch (_) {}
          };

          let cleanupScheduled = false;
          const scheduleCleanup = () => {
            if (cleanupScheduled) return;
            cleanupScheduled = true;
            requestAnimationFrame(() => {
              cleanupScheduled = false;
              cleanup();
            });
          };
          const observer = new MutationObserver(scheduleCleanup);
          observer.observe(document, { childList: true, subtree: true });
          nativeAddEventListener.call(document, "DOMContentLoaded", cleanup, { once: true });
          nativeAddEventListener.call(document, "click", (event) => {
            const anchor = event.target && event.target.closest
              ? event.target.closest("a[href]")
              : null;
            if (!anchor || !urlBlocked(anchor.getAttribute("href"))) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            removeNode(anchor);
          }, true);
          scheduleCleanup();
          return true;
        })();
    """.trimIndent()
}
