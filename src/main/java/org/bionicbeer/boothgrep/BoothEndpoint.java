package org.bionicbeer.boothgrep;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.*;

@Slf4j
@Component
public class BoothEndpoint implements CustomEndpoint {

    private final WebClient webClient;

    public BoothEndpoint() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "BoothGrepV1alpha1Console";
        return org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route()
                .POST("/booth/scrape", this::scrapeBooth,
                        builder -> builder.operationId("ScrapeBoothProduct")
                                .description("Scrape a booth.pm product page")
                                .tag(tag))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.booth-grep.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> scrapeBooth(ServerRequest request) {
        return request.bodyToMono(ScrapeRequest.class)
                .flatMap(body -> {
                    String url = body.getUrl();
                    if (url == null || url.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "URL is required"));
                    }
                    if (!url.contains("booth.pm")) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "Invalid booth.pm URL"));
                    }
                    return fetchAndParse(url)
                            .flatMap(data -> ServerResponse.ok().bodyValue(data));
                });
    }

    private Mono<ScrapeResult> fetchAndParse(String url) {
        return webClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .header("Accept-Language", "ja,en;q=0.9")
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseHtml)
                .onErrorResume(e -> {
                    log.error("Failed to scrape {}: {}", url, e.getMessage());
                    return Mono.just(new ScrapeResult() {{
                        setError("Failed to fetch page: " + e.getMessage());
                    }});
                });
    }

    private ScrapeResult parseHtml(String html) {
        Document doc = Jsoup.parse(html, "https://booth.pm");
        ScrapeResult result = new ScrapeResult();

        // Title: og:title or fallback
        String ogTitle = getMeta(doc, "og:title");
        if (ogTitle != null) {
            // Remove " - BOOTH" suffix if present
            ogTitle = ogTitle.replaceFirst("\\s*[-|]\\s*BOOTH.*$", "").trim();
        }
        result.setTitle(ogTitle != null ? ogTitle : getFirstText(doc, "h1"));

        // Description: og:description or fallback
        String ogDesc = getMeta(doc, "og:description");
        result.setDescription(ogDesc != null ? ogDesc : "");

        // Author: try multiple strategies
        result.setAuthor(extractAuthor(doc));

        // Images: og:image + page images
        Set<String> imageSet = new LinkedHashSet<>();
        String ogImage = getMeta(doc, "og:image");
        if (ogImage != null) imageSet.add(ogImage);

        // Extract images from JSON-LD or page content
        doc.select("img[src]").forEach(img -> {
            String src = img.absUrl("src");
            if (src != null && !src.isEmpty()
                    && !src.contains("logo") && !src.contains("icon")
                    && !src.contains("avatar") && !src.contains("128x128")
                    && (src.contains("booth.pximg.net") || src.contains("booth.px"))
                    && !src.contains("_base")) {
                // Get original size URL if possible
                src = src.replaceFirst("_base\\.", ".");
                src = src.replaceFirst("_\\d+x\\d+\\.", ".");
                imageSet.add(src);
            }
        });

        // Also check for images in data attributes or srcset
        doc.select("[data-src]").forEach(el -> {
            String src = el.absUrl("data-src");
            if (src != null && src.contains("booth.pximg.net")) {
                imageSet.add(src);
            }
        });

        result.setImages(new ArrayList<>(imageSet));

        // Try to get more detailed description from page body
        String bodyDesc = extractBodyDescription(doc);
        if (bodyDesc != null && bodyDesc.length() > (result.getDescription() != null ? result.getDescription().length() : 0)) {
            result.setDescription(bodyDesc);
        }

        return result;
    }

    private String extractAuthor(Document doc) {
        // Strategy 1: meta tag
        String author = getMeta(doc, "author");
        if (author != null) return author;

        // Strategy 2: seller link pattern
        Element sellerLink = doc.selectFirst("a[href*='/creators/']");
        if (sellerLink != null) {
            String name = sellerLink.text().trim();
            if (!name.isEmpty()) return name;
        }

        // Strategy 3: shop name element
        Element shopName = doc.selectFirst("[class*=seller], [class*=shop-name], [class*=creator]");
        if (shopName != null) {
            String name = shopName.text().trim();
            if (!name.isEmpty()) return name;
        }

        return "";
    }

    private String extractBodyDescription(Document doc) {
        // Try to find the main product description section
        Elements descElements = doc.select("[class*=description], [class*=detail]");
        StringBuilder sb = new StringBuilder();
        for (Element el : descElements) {
            String text = el.text().trim();
            if (text.length() > 50 && text.length() > sb.length()) {
                sb = new StringBuilder(text);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String getMeta(Document doc, String property) {
        Element meta = doc.selectFirst("meta[property=" + property + "]");
        if (meta == null) meta = doc.selectFirst("meta[name=" + property + "]");
        return meta != null ? meta.attr("content") : null;
    }

    private String getFirstText(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el != null ? el.text().trim() : null;
    }

    @Data
    public static class ScrapeRequest {
        private String url;
    }

    @Data
    public static class ScrapeResult {
        private String title = "";
        private String description = "";
        private String author = "";
        private List<String> images = new ArrayList<>();
        private String error;
    }
}
