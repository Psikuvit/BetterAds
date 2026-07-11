package me.psikuvit.betterads.embed;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.fraud.ViewTokenService;
import me.psikuvit.betterads.storage.entities.AdLink;
import me.psikuvit.betterads.storage.repositories.AdLinkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class EmbedService {

    private final AdLinkRepository adLinkRepository;
    private final ViewTokenService viewTokenService;

    @Value("${app.base-url}")
    private String baseUrl;

    public EmbedService(AdLinkRepository adLinkRepository, ViewTokenService viewTokenService) {
        this.adLinkRepository = adLinkRepository;
        this.viewTokenService = viewTokenService;
    }

    public AdLink generateLink(Long adId) {
        return adLinkRepository.findByAdId(adId).orElseGet(() -> {
            AdLink link = new AdLink();
            link.setAdId(adId);
            link.setToken(UUID.randomUUID().toString());
            AdLink saved = adLinkRepository.save(link);
            log.info("Generated embed link for adId={}: token={}", adId, saved.getToken());
            return saved;
        });
    }

    public Optional<AdLink> findByToken(String token) {
        return adLinkRepository.findByToken(token);
    }

    public Optional<AdLink> findByAdId(Long adId) {
        return adLinkRepository.findByAdId(adId);
    }

    public String embedUrl(String token) {
        return baseUrl + "/embed/" + token;
    }

    public String embedSnippet(String token) {
        return "<iframe src=\"" + embedUrl(token) + "\" " +
               "width=\"640\" height=\"360\" frameborder=\"0\" " +
               "allow=\"autoplay; fullscreen\" allowfullscreen></iframe>";
    }

    public String widgetHtml(Long adId) {
        String viewToken = viewTokenService.issueToken(adId);
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; -webkit-user-select: none; user-select: none; }
                body { background: #000; display: flex; align-items: center;
                       justify-content: center; width: 100vw; height: 100vh; overflow: hidden; }
                #wrap { position: relative; width: 100%%; height: 100%%; }
                video { position: absolute; top: 0; left: 0; width: 100%%; height: 100%%;
                        object-fit: contain; pointer-events: none; }
                #shield { position: absolute; inset: 0; z-index: 2; background: transparent; }
                #error { color: #fff; font-family: sans-serif; font-size: 14px; }
              </style>
            </head>
            <body oncontextmenu="return false">
              <div id="wrap">
                <video id="a" autoplay playsinline
                       disablepictureinpicture
                       controlslist="nodownload noplaybackrate nofullscreen"></video>
                <video id="b" playsinline muted
                       disablepictureinpicture
                       controlslist="nodownload noplaybackrate nofullscreen"
                       style="display:none"></video>
                <div id="shield"></div>
              </div>
              <div id="error" style="display:none">Ad unavailable</div>
              <script>
                (function() {
                  var adId = %d;
                  var vt = %s;
                  var locale = (navigator.language || 'en').split('-')[0];
                  var primary = document.getElementById('a');
                  var preload = document.getElementById('b');
                  var active = primary;
                  var standby = preload;
                  var expectedTime = 0;
                  var playlist = [];
                  var currentIndex = 0;

                  function reportSize(v) {
                    if (v.videoWidth && v.videoHeight) {
                      try { parent.postMessage({ type: 'ad-resize', width: v.videoWidth, height: v.videoHeight }, '*'); } catch(e) {}
                    }
                  }

                  active.addEventListener('loadedmetadata', function() { reportSize(active); });

                  active.addEventListener('timeupdate', function() {
                    if (Math.abs(active.currentTime - expectedTime) > 1) {
                      active.currentTime = expectedTime;
                    }
                    expectedTime = active.currentTime;
                  });
                  active.addEventListener('seeking', function() {
                    active.currentTime = expectedTime;
                  });
                  active.addEventListener('pause', function() {
                    if (!active.ended) {
                      active.play().catch(function(){});
                    }
                  });
                  document.addEventListener('keydown', function(e) {
                    e.preventDefault();
                  });

                  function swap() {
                    var tmp = active;
                    active = standby;
                    standby = tmp;
                    active.style.display = '';
                    standby.style.display = 'none';
                    standby.pause();
                    standby.removeAttribute('src');
                    standby.load();
                  }

                  function playItem(idx) {
                    var item = playlist[idx];
                    if (!item) { showError(); return; }
                    active.src = item.url;
                    active.load();
                    active.play().catch(function(){});
                  }

                  function preloadNext() {
                    var nextIdx = (currentIndex + 1) %% playlist.length;
                    var item = playlist[nextIdx];
                    if (item) {
                      standby.src = item.url;
                      standby.load();
                    }
                  }

                  active.addEventListener('ended', function() {
                    currentIndex = (currentIndex + 1) %% playlist.length;
                    swap();
                    expectedTime = 0;
                    active.play().catch(function(){});
                    preloadNext();
                  });

                  preload.addEventListener('canplaythrough', function() {
                    preload.removeEventListener('canplaythrough', arguments.callee);
                  });

                  fetch('/api/ads/' + adId + '/playlist?locale=' + locale + '&vt=' + encodeURIComponent(vt))
                    .then(function(r) { return r.ok ? r.json() : Promise.reject(r.status); })
                    .then(function(data) {
                      if (data.ads && data.ads.length > 0) {
                        playlist = data.ads;
                        currentIndex = 0;
                        playItem(0);
                        preloadNext();
                      } else {
                        showError();
                      }
                    })
                    .catch(function() { showError(); });

                  function showError() {
                    primary.style.display = 'none';
                    preload.style.display = 'none';
                    document.getElementById('error').style.display = 'block';
                  }
                })();
              </script>
            </body>
            </html>
            """.formatted(adId, "'" + viewToken + "'");
    }
}
