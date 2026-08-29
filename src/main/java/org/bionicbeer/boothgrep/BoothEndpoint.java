/*
 * Booth Grep plugin for Halo
 * Copyright (C) 2026 bionicbeer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

import java.util.*;

@Slf4j
@Component
public class BoothEndpoint implements CustomEndpoint {

    private final WebClient webClient;
    private final ReactiveExtensionClient client;
    private final ExtensionGetter extensionGetter;
    private final S3PresignService s3PresignService;

    private static final String CONFIGMAP_NAME = "booth-grep-configmap";
    private static final String SETTINGS_KEY = "ai";

    /** Direct browser uploads are capped at 1 GB (presigned PUT supports up to 5 GB). */
    private static final long MAX_DIRECT_UPLOAD_SIZE = 1024L * 1024 * 1024;

    public BoothEndpoint(ReactiveExtensionClient client, ExtensionGetter extensionGetter,
                         S3PresignService s3PresignService) {
        this.client = client;
        this.extensionGetter = extensionGetter;
        this.s3PresignService = s3PresignService;
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
                .GET("/booth/ai/defaults", this::getAiDefaults,
                        builder -> builder.operationId("GetAiDefaults")
                                .description("Get AI organize default settings from plugin config")
                                .tag(tag))
                .POST("/booth/ai/organize", this::organizeAiContent,
                        builder -> builder.operationId("OrganizeWithAi")
                                .description("Organize content using AI Foundation language model")
                                .tag(tag))
                .POST("/booth/upload/presign", this::presignUpload,
                        builder -> builder.operationId("PresignBoothUpload")
                                .description("Issue a presigned PUT URL for direct browser upload to an S3 policy")
                                .tag(tag))
                .POST("/booth/upload/complete", this::completeUpload,
                        builder -> builder.operationId("CompleteBoothUpload")
                                .description("Verify a direct upload and register it as a Halo attachment")
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
                    String raw = body.getUrl();
                    if (raw == null || raw.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "URL is required"));
                    }
                    String url = resolveBoothUrl(raw);
                    if (url == null) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "Invalid booth URL: cannot extract item ID"));
                    }
                    String ua = body.getUserAgent() != null && !body.getUserAgent().isBlank()
                            ? body.getUserAgent()
                            : DEFAULT_USER_AGENT;
                    return fetchAndParse(url, ua)
                            .flatMap(data -> ServerResponse.ok().bodyValue(data));
                });
    }

    /**
     * Normalize various booth.pm URL formats into a canonical URL.
     * Supported inputs:
     * <ul>
     *   <li>Full shop URL: {@code https://kashiwer.booth.pm/items/8651879}</li>
     *   <li>Localized URL: {@code https://booth.pm/zh-cn/items/8651879}</li>
     *   <li>Bare item ID: {@code 8651879}</li>
     * </ul>
     * All resolve to {@code https://booth.pm/zh-cn/items/8651879}.
     *
     * @return the canonical URL, or {@code null} if the input cannot be parsed
     */
    private String resolveBoothUrl(String input) {
        String trimmed = input.trim();

        // Case 1: bare numeric item ID
        if (trimmed.matches("^\\d+$")) {
            return "https://booth.pm/zh-cn/items/" + trimmed;
        }

        // Case 2: URL containing booth.pm — extract item ID from path
        if (trimmed.contains("booth.pm")) {
            // Match /items/{digits} anywhere in the path
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/items/(\\d+)")
                    .matcher(trimmed);
            if (m.find()) {
                return "https://booth.pm/zh-cn/items/" + m.group(1);
            }
            return null;
        }

        return null;
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
                .flatMap(result -> fetchItemCategories(url, userAgent)
                        .map(categories -> {
                            result.setCategories(categories);
                            return result;
                        })
                        .defaultIfEmpty(result))
                .onErrorResume(e -> {
                    log.error("Failed to scrape {}: {}", url, e.getMessage());
                    return Mono.just(new ScrapeResult() {{
                        setError("Failed to fetch page: " + e.getMessage());
                    }});
                });
    }

    /**
     * Fetch the product category path from the booth.pm item JSON API
     * (e.g. {@code https://booth.pm/zh-cn/items/8651879.json}).
     * Returns the category path ordered from top-level parent to leaf,
     * e.g. {@code ["3D Models", "3D Textures"]}. Empty on any failure.
     */
    private Mono<List<String>> fetchItemCategories(String url, String userAgent) {
        return webClient.get()
                .uri(url + ".json")
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    List<String> categories = new ArrayList<>();
                    try {
                        com.fasterxml.jackson.databind.JsonNode node =
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                        com.fasterxml.jackson.databind.JsonNode category = node.path("category");
                        com.fasterxml.jackson.databind.JsonNode parentName = category.path("parent").path("name");
                        if (parentName.isTextual() && !parentName.asText().isBlank()) {
                            categories.add(parentName.asText());
                        }
                        com.fasterxml.jackson.databind.JsonNode name = category.path("name");
                        if (name.isTextual() && !name.asText().isBlank()) {
                            categories.add(name.asText());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse booth categories JSON for {}: {}", url, e.getMessage());
                    }
                    return categories;
                })
                .onErrorResume(e -> {
                    log.warn("Failed to fetch booth categories for {}: {}", url, e.getMessage());
                    return Mono.just(new ArrayList<>());
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
        private List<String> categories = new ArrayList<>();
        private String error;
    }

    // ==================== AI Settings & Organize (AI Foundation) ====================

    private Mono<AiSettings> loadAiSettings() {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                .map(cm -> {
                    String json = cm.getData() != null ? cm.getData().get(SETTINGS_KEY) : null;
                    if (json != null && !json.isEmpty() && !json.equals("{}")) {
                        try {
                            return AiSettings.fromJson(json);
                        } catch (Exception e) {
                            return new AiSettings();
                        }
                    }
                    return new AiSettings();
                })
                .switchIfEmpty(Mono.just(new AiSettings()));
    }

    private Mono<ServerResponse> getAiDefaults(ServerRequest request) {
        return loadAiSettings()
                .flatMap(settings -> ServerResponse.ok().bodyValue(settings));
    }

    private Mono<ServerResponse> organizeAiContent(ServerRequest request) {
        return request.bodyToMono(OrganizeRequest.class)
                .flatMap(body -> {
                    if (body.getContent() == null || body.getContent().isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "Content is required"));
                    }

                    return loadAiSettings()
                            .flatMap(settings -> resolveLanguageModel(settings.getModelName())
                                    .flatMap(model -> model.generateText(buildOrganizeRequest(body)))
                                    .map(organizeResult -> Map.of("result", (Object) organizeResult.getText())))
                            .flatMap(result -> ServerResponse.ok().bodyValue(result))
                            .onErrorResume(e -> {
                                log.error("AI organize error: {}", e.getMessage());
                                return ServerResponse.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                                        .bodyValue(Map.of("error", "AI 内容整理失败: " + e.getMessage()));
                            });
                });
    }

    /**
     * Resolve a language model from AI Foundation. Falls back to the site-level
     * default language model when no model name is configured.
     */
    private Mono<run.halo.aifoundation.chat.LanguageModel> resolveLanguageModel(String modelName) {
        return extensionGetter.getEnabledExtension(AiModelService.class)
                .switchIfEmpty(Mono.error(() ->
                        new IllegalStateException("AI Foundation 插件未启用，请先安装并启用 AI Foundation")))
                .flatMap(service -> (modelName == null || modelName.isBlank())
                        ? service.languageModel()
                        : service.languageModel(modelName));
    }

    private GenerateTextRequest buildOrganizeRequest(OrganizeRequest body) {
        String userContent = body.getPrompt() != null && !body.getPrompt().isBlank()
                ? body.getPrompt() + "\n\n" + body.getContent()
                : body.getContent();
        return GenerateTextRequest.builder()
                .system("你是一个内容整理助手，请直接返回整理后的内容，不要添加任何解释。")
                .prompt(userContent)
                .build();
    }

    @Data
    public static class OrganizeRequest {
        private String content;
        private String prompt;
    }

    // ==================== S3 direct upload (presigned PUT URL) ====================

    private Mono<ServerResponse> presignUpload(ServerRequest request) {
        return request.bodyToMono(UploadPresignRequest.class)
                .flatMap(body -> {
                    if (body.getPolicyName() == null || body.getPolicyName().isBlank()
                            || body.getFileName() == null || body.getFileName().isBlank()) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "policyName 与 fileName 为必填项"));
                    }
                    if (body.getSize() <= 0 || body.getSize() > MAX_DIRECT_UPLOAD_SIZE) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "文件大小超出直传限制（1 GB）"));
                    }
                    String objectKey = generateObjectKey(body.getFileName());
                    return s3PresignService.loadS3PolicyConfig(body.getPolicyName())
                            .map(cfg -> {
                                java.net.URL uploadUrl = s3PresignService.presignPut(
                                        cfg, objectKey, java.time.Duration.ofMinutes(10));
                                return Map.of("uploadUrl", uploadUrl.toString(),
                                        "objectKey", objectKey, "expiresIn", 600);
                            })
                            .flatMap(res -> ServerResponse.ok().bodyValue(res))
                            .onErrorResume(e -> {
                                log.warn("Failed to presign upload for policy {}: {}",
                                        body.getPolicyName(), e.getMessage());
                                return ServerResponse.badRequest().bodyValue(
                                        Map.of("error", String.valueOf(e.getMessage())));
                            });
                });
    }

    private Mono<ServerResponse> completeUpload(ServerRequest request) {
        return request.bodyToMono(UploadCompleteRequest.class)
                .flatMap(body -> {
                    if (body.getPolicyName() == null || body.getPolicyName().isBlank()
                            || body.getObjectKey() == null || body.getObjectKey().isBlank()
                            || body.getFileName() == null || body.getFileName().isBlank()) {
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", "policyName、objectKey 与 fileName 为必填项"));
                    }
                    Mono<String> owner = request.exchange().getPrincipal()
                            .map(java.security.Principal::getName)
                            .defaultIfEmpty("anonymousUser");
                    return s3PresignService.loadS3PolicyConfig(body.getPolicyName())
                            .flatMap(cfg -> s3PresignService.verifyObjectExists(cfg, body.getObjectKey())
                                    .flatMap(exists -> {
                                        if (!Boolean.TRUE.equals(exists)) {
                                            return Mono.error(new IllegalStateException(
                                                    "存储桶中未找到该文件，上传可能未完成"));
                                        }
                                        return owner.flatMap(ownerName -> s3PresignService.registerAttachment(
                                                cfg, body.getObjectKey(), body.getFileName(),
                                                body.getSize(), body.getMediaType(), ownerName));
                                    }))
                            .flatMap(permalink -> ServerResponse.ok()
                                    .bodyValue(Map.of("permalink", permalink)))
                            .onErrorResume(e -> {
                                log.warn("Failed to complete direct upload for object {}: {}",
                                        body.getObjectKey(), e.getMessage());
                                return ServerResponse.badRequest().bodyValue(
                                        Map.of("error", String.valueOf(e.getMessage())));
                            });
                });
    }

    /**
     * Generate a collision-safe bucket object key while preserving the original
     * file extension (limited to a short alphanumeric suffix for URL safety).
     */
    private String generateObjectKey(String fileName) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            String candidate = fileName.substring(dot).toLowerCase();
            if (candidate.matches("\\.[a-z0-9]{1,8}")) {
                ext = candidate;
            }
        }
        String random = Long.toString(
                Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)), 36);
        return "booth-download-" + System.currentTimeMillis() + "-" + random + ext;
    }

    @Data
    public static class UploadPresignRequest {
        private String policyName;
        private String fileName;
        private long size;
        private String mediaType;
    }

    @Data
    public static class UploadCompleteRequest {
        private String policyName;
        private String objectKey;
        private String fileName;
        private long size;
        private String mediaType;
    }

    @Data
    public static class AiSettings {
        private String modelName = "";
        private String titlePrompt = "请优化以下标题，使其更简洁、吸引人，适合中文博客文章：";
        private String descriptionPrompt = "你即将收到一段爬取自Booth.pm的商品描述，你需要完成以下任务并只返回修改后的文本：1. 去除爬取过程中意外混入的其他商品的标题和价格；2.认真阅读文本，并用中文按介绍、使用说明、更新记录、其他文中提到的内容来回复。";

        public static AiSettings fromJson(String json) {
            AiSettings s = new AiSettings();
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<?, ?> map = mapper.readValue(json, Map.class);
                if (map.get("modelName") != null) s.setModelName(String.valueOf(map.get("modelName")));
                if (map.containsKey("titlePrompt")) s.setTitlePrompt(String.valueOf(map.get("titlePrompt")));
                if (map.containsKey("descriptionPrompt")) s.setDescriptionPrompt(String.valueOf(map.get("descriptionPrompt")));
            } catch (Exception ignored) {}
            return s;
        }
    }
}
