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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Issues presigned PUT URLs for S3-compatible storage policies so the browser can
 * upload download files directly to the bucket without routing bytes through the
 * Halo server (Scheme A: presigned PUT URL).
 *
 * <p>After the browser finishes the PUT, {@link #registerAttachment} verifies the
 * object exists (presigned GET + HEAD) and creates a Halo Attachment, so the file
 * appears in the attachment library, gains a permalink and is removed from the
 * bucket together with the object when deleted.</p>
 */
@Slf4j
@Component
public class S3PresignService {

    /** Annotation read by plugin-s3 to locate the bucket object (reconcile + delete). */
    public static final String OBJECT_KEY_ANNOTATION = "s3os.plugin.halo.run/object-key";
    public static final String EXTERNAL_LINK_ANNOTATION = "storage.halo.run/external-link";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final ReactiveExtensionClient client;

    public S3PresignService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /** Connection details extracted from a storage policy and its configMap. */
    public record PolicyConfig(String policyName, String endpoint, String protocol, String region,
                               String bucket, String accessKey, String accessSecret,
                               boolean pathStyle, String domain) {
    }

    /**
     * Load and validate an S3 storage policy together with its credentials.
     * Fails with {@link IllegalArgumentException} when the policy does not exist or
     * is not an S3-compatible template, and with {@link IllegalStateException} when
     * the policy settings are missing or incomplete.
     */
    public Mono<PolicyConfig> loadS3PolicyConfig(String policyName) {
        return client.fetch(Policy.class, policyName)
                .switchIfEmpty(Mono.error(() ->
                        new IllegalArgumentException("存储策略不存在: " + policyName)))
                .flatMap(policy -> {
                    String template = policy.getSpec().getTemplateName();
                    if (template == null || !template.toLowerCase().contains("s3")) {
                        return Mono.error(() -> new IllegalArgumentException(
                                "预签名直传仅支持 S3 对象存储策略，当前策略模板为: " + template));
                    }
                    String configMapName = policy.getSpec().getConfigMapName();
                    return client.fetch(ConfigMap.class, configMapName)
                            .switchIfEmpty(Mono.error(() ->
                                    new IllegalStateException("存储策略尚未完成配置")))
                            .map(cm -> parseConfig(policyName, cm));
                });
    }

    private PolicyConfig parseConfig(String policyName, ConfigMap cm) {
        String json = cm.getData() == null ? null : cm.getData().get("default");
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("存储策略缺少配置数据");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            // Endpoint may be stored as bare host; tolerate protocol prefix / trailing slash.
            String endpoint = text(node, "endpoint")
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/+$", "");
            String protocol = text(node, "endpointProtocol");
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                protocol = "https";
            }
            String region = text(node, "region");
            String bucket = text(node, "bucket");
            String accessKey = text(node, "accessKey");
            String accessSecret = text(node, "accessSecret");
            boolean pathStyle = node.path("enablePathStyleAccess").asBoolean(false);
            // Custom domain bound to the bucket (plugin-s3 "自定义域名"), used for public links.
            String domain = text(node, "domain")
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/+$", "");
            if (endpoint.isEmpty() || bucket.isEmpty() || accessKey.isEmpty() || accessSecret.isEmpty()) {
                throw new IllegalStateException("存储策略配置不完整（需要 endpoint、bucket、accessKey、accessSecret）");
            }
            return new PolicyConfig(policyName, endpoint, protocol,
                    region.isEmpty() ? "us-east-1" : region,
                    bucket, accessKey, accessSecret, pathStyle, domain);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("存储策略配置解析失败: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private S3Presigner buildPresigner(PolicyConfig cfg) {
        return S3Presigner.builder()
                .region(Region.of(cfg.region()))
                .endpointOverride(URI.create(cfg.protocol() + "://" + cfg.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.accessKey(), cfg.accessSecret())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(cfg.pathStyle())
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    /** Sign a PUT request for the given object key; Content-Type stays unsigned. */
    public URL presignPut(PolicyConfig cfg, String objectKey, Duration ttl) {
        try (S3Presigner presigner = buildPresigner(cfg)) {
            return presigner.presignPutObject(request -> request
                    .signatureDuration(ttl)
                    .putObjectRequest(put -> put.bucket(cfg.bucket()).key(objectKey)))
                    .url();
        }
    }

    /**
     * Check whether the object exists in the bucket by issuing a ranged GET request
     * against a short-lived presigned GET URL. AWS presigned URLs are bound to the
     * HTTP method they were signed for, so a HEAD request against a GET signature
     * would be rejected; Range is not part of the signature and keeps the transfer
     * to a single byte.
     */
    public Mono<Boolean> verifyObjectExists(PolicyConfig cfg, String objectKey) {
        URL url;
        try (S3Presigner presigner = buildPresigner(cfg)) {
            url = presigner.presignGetObject(request -> request
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(get -> get.bucket(cfg.bucket()).key(objectKey)))
                    .url();
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("生成校验请求失败: " + e.getMessage(), e));
        }
        return Mono.fromCallable(() -> {
                    HttpRequest get = HttpRequest.newBuilder(url.toURI())
                            .GET()
                            .header("Range", "bytes=0-0")
                            .timeout(Duration.ofSeconds(15))
                            .build();
                    return HTTP_CLIENT.send(get, HttpResponse.BodyHandlers.discarding()).statusCode();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(status -> status == 200 || status == 206);
    }

    /**
     * Create the Halo Attachment for an object that was uploaded directly to the
     * bucket. The object-key annotation lets plugin-s3 resolve the permalink and
     * delete the object when the attachment is removed; the permalink is also set
     * eagerly so the card works even before the policy reconciler runs.
     */
    public Mono<String> registerAttachment(PolicyConfig cfg, String objectKey, String fileName,
                                           long size, String mediaType, String ownerName) {
        String permalink = buildPermalink(cfg, objectKey);
        Attachment attachment = new Attachment();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setGenerateName("attachment-");
        metadata.setAnnotations(Map.of(
                OBJECT_KEY_ANNOTATION, objectKey,
                EXTERNAL_LINK_ANNOTATION, permalink));
        attachment.setMetadata(metadata);
        Attachment.AttachmentSpec spec = new Attachment.AttachmentSpec();
        spec.setDisplayName(fileName);
        spec.setMediaType(mediaType == null || mediaType.isBlank()
                ? "application/octet-stream" : mediaType);
        spec.setSize(size);
        spec.setOwnerName(ownerName);
        spec.setPolicyName(cfg.policyName());
        attachment.setSpec(spec);
        return client.create(attachment)
                .flatMap(created -> client.fetch(Attachment.class, created.getMetadata().getName())
                        .flatMap(latest -> {
                            String existing = latest.getStatus() != null
                                    ? latest.getStatus().getPermalink() : null;
                            if (existing != null && !existing.isBlank()) {
                                return Mono.just(existing);
                            }
                            Attachment.AttachmentStatus status = latest.getStatus() != null
                                    ? latest.getStatus() : new Attachment.AttachmentStatus();
                            status.setPermalink(permalink);
                            latest.setStatus(status);
                            // The policy reconciler may race us; keep our permalink on conflict.
                            return client.update(latest)
                                    .map(updated -> permalink)
                                    .onErrorReturn(permalink);
                        }));
    }

    /**
     * Permalink matching plugin-s3 link style. When the policy binds a custom
     * domain, links must use it: the bucket endpoint rejects unsigned access
     * (R2 answers 400 InvalidArgument/Authorization), so a bucket-domain link
     * written into an article would be undownloadable.
     */
    public String buildPermalink(PolicyConfig cfg, String objectKey) {
        if (!cfg.domain().isEmpty()) {
            return cfg.protocol() + "://" + cfg.domain() + "/" + objectKey;
        }
        String host = cfg.pathStyle()
                ? cfg.endpoint() + "/" + cfg.bucket()
                : cfg.bucket() + "." + cfg.endpoint();
        return cfg.protocol() + "://" + host + "/" + objectKey;
    }
}
