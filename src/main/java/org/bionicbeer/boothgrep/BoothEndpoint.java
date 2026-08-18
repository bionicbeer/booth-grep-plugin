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
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.*;

@Slf4j
@Component
public class BoothEndpoint implements CustomEndpoint {

    private final WebClient webClient;
    private final ReactiveExtensionClient client;

    private static final String CONFIGMAP_NAME = "booth-grep-configmap";
    private static final String SETTINGS_KEY = "deepseek";

    public BoothEndpoint(ReactiveExtensionClient client) {
        this.client = client;
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
                .GET("/booth/image-proxy", this::proxyImage,
                        builder -> builder.operationId("ProxyBoothImage")
                                .description("Proxy a booth.pximg.net image to avoid CORS issues")
                                .tag(tag))
                .GET("/booth/deepseek/defaults", this::getDeepSeekDefaults,
                        builder -> builder.operationId("GetDeepSeekDefaults")
                                .description("Get DeepSeek AI default settings from plugin config")
                                .tag(tag))
                .POST("/booth/deepseek/organize", this::organizeDeepSeekContent,
                        builder -> builder.operationId("OrganizeWithDeepSeek")
                                .description("Organize content using DeepSeek AI")
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
                    String ua = body.getUserAgent() != null && !body.getUserAgent().isBlank()
                            ? body.getUserAgent()
                            : DEFAULT_USER_AGENT;
                    return fetchAndParse(url, ua)
                            .flatMap(data -> ServerResponse.ok().bodyValue(data));
                });
    }

    private Mono<ServerResponse> proxyImage(ServerRequest request) {
        String imageUrl = request.queryParam("url").orElse("");
        if (imageUrl.isEmpty() || !imageUrl.contains("booth.pximg.net")) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "Invalid image URL"));
        }
        
        return webClient.get()
                .uri(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .header("Referer", "https://booth.pm/")
                .retrieve()
                .bodyToMono(byte[].class)
                .flatMap(bytes -> {
                    String contentType = "image/jpeg";
                    if (imageUrl.endsWith(".png")) contentType = "image/png";
                    else if (imageUrl.endsWith(".gif")) contentType = "image/gif";
                    else if (imageUrl.endsWith(".webp")) contentType = "image/webp";
                    
                    return ServerResponse.ok()
                            .header("Content-Type", contentType)
                            .header("Cache-Control", "public, max-age=86400")
                            .bodyValue(bytes);
                })
                .onErrorResume(e -> {
                    log.error("Failed to proxy image {}: {}", imageUrl, e.getMessage());
                    return ServerResponse.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                            .bodyValue(Map.of("error", "Failed to fetch image"));
                });
    }

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Mono<ScrapeResult> fetchAndParse(String url, String userAgent) {
        return webClient.get()
                .uri(url)
                .header("User-Agent", userAgent)
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

        // Images: multi-strategy extraction
        Set<String> imageSet = new LinkedHashSet<>();

        // Strategy 1: Product carousel images (primary source)
        // booth.pm uses Slick carousel with class "market-item-detail-item-image"
        doc.select("img.market-item-detail-item-image").forEach(img -> {
            String src = img.absUrl("src");
            if (src != null && !src.isEmpty()) {
                imageSet.add(normalizeImageUrl(src));
            }
        });

        // Strategy 2: Thumbnail carousel (may contain images not yet loaded in main carousel)
        doc.select(".primary-image-thumbnails img[src]").forEach(img -> {
            String src = img.absUrl("src");
            if (src != null && !src.isEmpty()) {
                imageSet.add(normalizeImageUrl(src));
            }
        });

        // Strategy 3: og:image as fallback (usually the cover image)
        String ogImage = getMeta(doc, "og:image");
        if (ogImage != null) imageSet.add(normalizeImageUrl(ogImage));

        // Strategy 4: JSON-LD structured data image
        Elements jsonLdScripts = doc.select("script[type=application/ld+json]");
        for (Element script : jsonLdScripts) {
            String text = script.data();
            // Simple regex extraction of image URL from JSON-LD
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"image\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(text);
            if (m.find()) {
                imageSet.add(normalizeImageUrl(m.group(1)));
            }
        }

        // Strategy 5: Catch any remaining booth product images not yet found
        // Look for images in the main product area that we might have missed
        doc.select(".primary-image-area img[src], [class*=market-item] img[src]").forEach(img -> {
            String src = img.absUrl("src");
            if (src != null && !src.isEmpty() && src.contains("booth.pximg.net")) {
                imageSet.add(normalizeImageUrl(src));
            }
        });

        // Filter: keep only product images from booth.pximg.net, exclude tiny/non-product images
        imageSet.removeIf(url -> {
            if (!url.contains("booth.pximg.net")) return true;
            // Exclude known non-product patterns
            if (url.contains("avatar") || url.contains("128x128") || url.contains("48x48")) return true;
            return false;
        });

        // Convert to proxy URLs to avoid CORS/ORB issues in browser
        List<String> proxyImages = new ArrayList<>();
        for (String imgUrl : imageSet) {
            String proxyUrl = "/apis/console.api.booth-grep.halo.run/v1alpha1/booth/image-proxy?url=" 
                    + java.net.URLEncoder.encode(imgUrl, java.nio.charset.StandardCharsets.UTF_8);
            proxyImages.add(proxyUrl);
        }
        result.setImages(proxyImages);

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
        StringBuilder best = new StringBuilder();
        for (Element el : descElements) {
            String text = extractTextWithLineBreaks(el).trim();
            if (text.length() > 50 && text.length() > best.length()) {
                best = new StringBuilder(text);
            }
        }
        return best.length() > 0 ? best.toString() : null;
    }

    /**
     * Extract text from an element while preserving line breaks from <br>, <p>, <div> tags.
     */
    private String extractTextWithLineBreaks(Element el) {
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode) {
                sb.append(((org.jsoup.nodes.TextNode) node).text());
            } else if (node instanceof Element child) {
                String tag = child.tagName().toLowerCase();
                if (tag.equals("br")) {
                    sb.append("\n");
                } else if (tag.equals("p") || tag.equals("div") || tag.equals("li") || tag.equals("tr")) {
                    if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                        sb.append("\n");
                    }
                    sb.append(extractTextWithLineBreaks(child));
                    sb.append("\n");
                } else {
                    sb.append(extractTextWithLineBreaks(child));
                }
            }
        }
        // Collapse multiple consecutive newlines into at most 2
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
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

    /**
     * Normalize booth.pm image URL to get the full-resolution version.
     * Only removes size prefix (e.g. c/72x72_a2_g5/) but keeps _base_resized suffix
     * as it's part of the actual filename on the server.
     */
    private String normalizeImageUrl(String url) {
        if (url == null) return "";
        // Remove size prefix pattern: /c/{size}[_{params]}/
        url = url.replaceFirst("/c/\\d+x\\d+[^/]*/", "/");
        return url;
    }

    @Data
    public static class ScrapeRequest {
        private String url;
        private String userAgent;
    }

    @Data
    public static class ScrapeResult {
        private String title = "";
        private String description = "";
        private String author = "";
        private List<String> images = new ArrayList<>();
        private String error;
    }

    // ==================== DeepSeek Settings & Organize ====================

    private Mono<ServerResponse> getDeepSeekDefaults(ServerRequest request) {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                .map(cm -> {
                    String json = cm.getData() != null ? cm.getData().get(SETTINGS_KEY) : null;
                    if (json != null && !json.isEmpty() && !json.equals("{}")) {
                        try {
                            return DeepSeekSettings.fromJson(json);
                        } catch (Exception e) {
                            return new DeepSeekSettings();
                        }
                    }
                    return new DeepSeekSettings();
                })
                .switchIfEmpty(Mono.just(new DeepSeekSettings()))
                .flatMap(settings -> ServerResponse.ok().bodyValue(settings));
    }

    private Mono<ServerResponse> organizeDeepSeekContent(ServerRequest request) {
        return request.bodyToMono(OrganizeRequest.class)
                .flatMap(body -> {
                    if (body.getContent() == null || body.getContent().isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "Content is required"));
                    }

                    // Read settings from ConfigMap
                    return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                            .flatMap(cm -> {
                                DeepSeekSettings settings = new DeepSeekSettings();
                                String json = cm.getData() != null ? cm.getData().get(SETTINGS_KEY) : null;
                                if (json != null && !json.isEmpty() && !json.equals("{}")) {
                                    try {
                                        settings = DeepSeekSettings.fromJson(json);
                                    } catch (Exception ignored) {}
                                }

                                if (settings.getDeepseekApiKey() == null || settings.getDeepseekApiKey().isBlank()) {
                                    return ServerResponse.badRequest()
                                            .bodyValue(Map.of("error", "DeepSeek API key not configured"));
                                }

                                // Build request body for DeepSeek API
                                Map<String, Object> apiBody = new HashMap<>();
                                apiBody.put("model", "deepseek-chat");
                                apiBody.put("temperature", 0.7);
                                apiBody.put("max_tokens", 2000);
                                apiBody.put("messages", List.of(
                                        Map.of("role", "system", "content", "你是一个内容整理助手，请直接返回整理后的内容，不要添加任何解释。"),
                                        Map.of("role", "user", "content", body.getPrompt() + "\n\n" + body.getContent())
                                ));

                                return webClient.post()
                                        .uri("https://api.deepseek.com/v1/chat/completions")
                                        .header("Content-Type", "application/json")
                                        .header("Authorization", "Bearer " + settings.getDeepseekApiKey())
                                        .bodyValue(apiBody)
                                        .retrieve()
                                        .bodyToMono(Map.class)
                                        .map(data -> {
                                            try {
                                                List<Map<String, Object>> choices =
                                                        (List<Map<String, Object>>) data.get("choices");
                                                if (choices != null && !choices.isEmpty()) {
                                                    Map<String, Object> message =
                                                            (Map<String, Object>) choices.get(0).get("message");
                                                    if (message != null) {
                                                        String content = (String) message.get("content");
                                                        if (content != null) {
                                                            return Map.of("result", content);
                                                        }
                                                    }
                                                }
                                            } catch (Exception e) {
                                                log.error("Failed to parse DeepSeek response: {}", e.getMessage());
                                            }
                                            return Map.of("result", body.getContent());
                                        })
                                        .flatMap(result -> ServerResponse.ok().bodyValue(result))
                                        .onErrorResume(e -> {
                                            log.error("DeepSeek API error: {}", e.getMessage());
                                            return ServerResponse.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                                                    .bodyValue(Map.of("error", "DeepSeek API 请求失败: " + e.getMessage()));
                                        });
                            })
                            .switchIfEmpty(ServerResponse.badRequest()
                                    .bodyValue(Map.of("error", "Settings not initialized")));
                });
    }

    @Data
    public static class OrganizeRequest {
        private String content;
        private String prompt;
    }

    @Data
    public static class DeepSeekSettings {
        private String deepseekApiKey = "";
        private String titlePrompt = "请优化以下标题，使其更简洁、吸引人，适合中文博客文章：";
        private String descriptionPrompt = "你即将收到一段爬取自Booth.pm的商品描述，你需要完成以下任务并只返回修改后的文本：1. 去除爬取过程中意外混入的其他商品的标题和价格；2.认真阅读文本，并用中文按介绍、使用说明、更新记录、其他文中提到的内容来回复。";

        public String toJson() {
            return "{\"deepseekApiKey\":\"" + escapeJson(deepseekApiKey)
                    + "\",\"titlePrompt\":\"" + escapeJson(titlePrompt)
                    + "\",\"descriptionPrompt\":\"" + escapeJson(descriptionPrompt) + "\"}";
        }

        public static DeepSeekSettings fromJson(String json) {
            DeepSeekSettings s = new DeepSeekSettings();
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                if (map.containsKey("deepseekApiKey")) s.setDeepseekApiKey(String.valueOf(map.get("deepseekApiKey")));
                if (map.containsKey("titlePrompt")) s.setTitlePrompt(String.valueOf(map.get("titlePrompt")));
                if (map.containsKey("descriptionPrompt")) s.setDescriptionPrompt(String.valueOf(map.get("descriptionPrompt")));
            } catch (Exception ignored) {}
            return s;
        }

        private static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }
}
