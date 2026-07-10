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
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { background: #000; display: flex; align-items: center;
                           justify-content: center; width: 100vw; height: 100vh; }
                    video { width: 100%; height: 100%; object-fit: contain; }
                    #error { color: #fff; font-family: sans-serif; font-size: 14px; }
                  </style>
                </head>
                <body>
                  <video id="ad" controls autoplay muted playsinline></video>
                  <div id="error" style="display:none">Ad unavailable</div>
                  <script>
                    (function() {
                      var adId = %d;
                      var vt = %s;
                      var locale = (navigator.language || 'en').split('-')[0];
                      fetch('/api/ads/' + adId + '?locale=' + locale + '&vt=' + encodeURIComponent(vt))
                        .then(function(r) { return r.ok ? r.json() : Promise.reject(r.status); })
                        .then(function(data) {
                          if (data.variants && data.variants.length > 0) {
                            document.getElementById('ad').src = data.variants[0];
                          } else {
                            showError();
                          }
                        })
                        .catch(function() { showError(); });
                      function showError() {
                        document.getElementById('ad').style.display = 'none';
                        document.getElementById('error').style.display = 'block';
                      }
                    })();
                  </script>
                </body>
                </html>
                """.formatted(adId, "'" + viewToken + "'");
    }
}
