package com.interview.agent.interview.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.interview.service.InterviewAsrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * DashScope Paraformer 音频转写实现。
 *
 * <p><b>完整处理链路</b></p>
 * <ol>
 *   <li>Step 1: 校验上传文件（空文件/后缀/大小）</li>
 *   <li>Step 2: 获取 DashScope 上传凭证并把音频上传到 OSS</li>
 *   <li>Step 3: 提交异步 ASR 任务并轮询任务状态</li>
 *   <li>Step 4: 下载转写结果 JSON，按 speaker_id 合并句子为说话人段落</li>
 *   <li>Step 5: 返回转写文本（标准化归一由预解析模块负责）</li>
 *   <li>Step 6: 全链路异常统一转业务错误，临时文件在 finally 中清理</li>
 * </ol>
 */
@Service
public class InterviewAsrServiceImpl implements InterviewAsrService {

    private static final Logger log = LoggerFactory.getLogger(InterviewAsrServiceImpl.class);

    private static final long MAX_FILE_SIZE = 300L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".mp3", ".wav", ".m4a", ".flac", ".ogg", ".wma", ".aac");
    private static final String GET_POLICY_URL = "https://dashscope.aliyuncs.com/api/v1/uploads";
    private static final String SUBMIT_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";

    private final EmbeddingProperties embeddingProperties;
    private final HttpClient httpClient;

    public InterviewAsrServiceImpl(EmbeddingProperties embeddingProperties) {
        this.embeddingProperties = embeddingProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String transcribe(MultipartFile file) {
        // Step 1: 入参校验与配置校验。
        validateFile(file);
        String apiKey = embeddingProperties.dashscopeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(50000, "ASR 未配置：请设置 DASHSCOPE_API_KEY");
        }

        String originalName = file.getOriginalFilename() == null ? "audio.wav" : file.getOriginalFilename();
        String suffix = extractExt(originalName);
        Path tempFile = null;
        Path monoFile = null;
        try {
            // Step 2: 临时落盘，准备上传。
            tempFile = Files.createTempFile("interview-audio-", suffix);
            file.transferTo(tempFile);

            // Step 2.5: 多声道转单声道（diarization 对单声道更稳）；返回 null 表示已是单声道。
            monoFile = convertToMono(tempFile);
            Path actualPath = monoFile != null ? monoFile : tempFile;
            String uploadName = monoFile != null ? "mono.wav" : originalName;

            // Step 3: 上传 + 提交任务 + 轮询。
            String ossUrl = uploadToDashscopeOss(actualPath, uploadName, apiKey);
            String taskId = submitTask(ossUrl, apiKey);
            JsonNode output = pollTask(taskId, apiKey);

            // Step 4: 拉取并解析最终转写文本（说话人A/B）。
            // 角色归一化（说话人X → 面试官/我）已下沉到预解析阶段统一执行，这里只返回原始文本。
            String text = parseTranscriptionText(output, apiKey);
            return text;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(50000, "语音转写失败: " + ex.getMessage(), ex);
        } finally {
            // Step 6: 清理临时文件（失败不阻断主流程）。
            deleteQuietly(tempFile);
            deleteQuietly(monoFile);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    /**
     * 用 ffmpeg 把音频转为单声道 16kHz wav（复刻 Python _convert_to_mono）。
     * 已是单声道返回 null；ffmpeg/ffprobe 不可用或失败时返回 null（回退原文件）。
     */
    private Path convertToMono(Path filePath) {
        try {
            // ffprobe 探测声道数
            Process probe = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=channels",
                    "-of", "default=noprint_wrappers=1:nokey=1", filePath.toString())
                    .redirectErrorStream(false)
                    .start();
            String probeOut = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!probe.waitFor(30, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return null;
            }
            int channels = 1;
            if (!probeOut.isEmpty()) {
                try {
                    channels = Integer.parseInt(probeOut.split("\\s+")[0]);
                } catch (NumberFormatException ignored) {
                    channels = 1;
                }
            }
            if (channels <= 1) {
                return null;
            }

            log.info("音频为 {} 声道，转换为单声道...", channels);
            Path mono = Files.createTempFile("interview-audio-mono-", ".wav");
            Process ff = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", filePath.toString(),
                    "-ac", "1", "-ar", "16000", mono.toString())
                    .redirectErrorStream(true)
                    .start();
            ff.getInputStream().readAllBytes();   // 排空输出避免阻塞
            boolean done = ff.waitFor(300, TimeUnit.SECONDS);
            if (!done || ff.exitValue() != 0) {
                if (!done) {
                    ff.destroyForcibly();
                }
                deleteQuietly(mono);
                return null;
            }
            return mono;
        } catch (Exception e) {
            log.warn("ffmpeg 转换失败: {}", e.getMessage());
            return null;
        }
    }

    private void validateFile(MultipartFile file) {
        // Step 1: 基础合法性校验。
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请上传音频文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extractExt(filename);
        if (!ALLOWED_EXT.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BizException(40001, "不支持的音频格式，仅支持 mp3/wav/m4a/flac/ogg/wma/aac");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(40001, "上传文件过大，当前最大支持 300MB");
        }
    }

    /**
     * 上传音频到 DashScope 关联 OSS。
     * <ol>
     *   <li>Step 1: 取上传凭证</li>
     *   <li>Step 2: 组装 multipart/form-data 并上传文件</li>
     *   <li>Step 3: 返回后续 ASR 使用的 oss:// 资源地址</li>
     * </ol>
     */
    private String uploadToDashscopeOss(Path localFile, String originalName, String apiKey) throws Exception {
        String query = "action=" + URLEncoder.encode("getPolicy", StandardCharsets.UTF_8)
                + "&model=" + URLEncoder.encode("paraformer-v2", StandardCharsets.UTF_8);
        HttpRequest getPolicyReq = HttpRequest.newBuilder()
                .uri(URI.create(GET_POLICY_URL + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> policyResp = httpClient.send(getPolicyReq, HttpResponse.BodyHandlers.ofString());
        if (policyResp.statusCode() != 200) {
            throw new BizException(50000, "获取 ASR 上传凭证失败");
        }

        JsonNode data = JsonUtil.mapper().readTree(policyResp.body()).path("data");
        String uploadHost = data.path("upload_host").asText("");
        String uploadDir = data.path("upload_dir").asText("");
        String policy = data.path("policy").asText("");
        String signature = data.path("signature").asText("");
        String accessKeyId = data.path("oss_access_key_id").asText("");
        String acl = data.path("x_oss_object_acl").asText("");
        String forbidOverwrite = data.path("x_oss_forbid_overwrite").asText("");
        if (uploadHost.isBlank() || uploadDir.isBlank() || policy.isBlank() || signature.isBlank() || accessKeyId.isBlank()) {
            throw new BizException(50000, "ASR 上传凭证不完整");
        }

        String objectKey = uploadDir + "/" + sanitizeFilename(originalName);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("OSSAccessKeyId", accessKeyId);
        fields.put("policy", policy);
        fields.put("Signature", signature);
        fields.put("key", objectKey);
        fields.put("x-oss-object-acl", acl);
        fields.put("x-oss-forbid-overwrite", forbidOverwrite);
        fields.put("success_action_status", "200");

        String boundary = "----InterviewAsrBoundary" + System.currentTimeMillis();
        HttpRequest.BodyPublisher body = buildMultipartBody(boundary, fields, localFile, sanitizeFilename(originalName));

        HttpRequest uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(uploadHost))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();
        HttpResponse<String> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        if (uploadResp.statusCode() != 200 && uploadResp.statusCode() != 204) {
            throw new BizException(50000, "上传音频到 ASR 存储失败");
        }

        return "oss://" + objectKey;
    }

    /**
     * 提交异步 ASR 任务并返回 task_id。
     */
    private String submitTask(String ossUrl, String apiKey) throws Exception {
        String payload = JsonUtil.toJson(Map.of(
                "model", "paraformer-v2",
                "input", Map.of("file_urls", List.of(ossUrl)),
                "parameters", Map.of(
                        "language_hints", List.of("zh", "en"),
                        "diarization_enabled", true,
                        "speaker_count", 2
                )
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_TASK_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .header("X-DashScope-OssResourceResolve", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BizException(50000, "提交 ASR 转写任务失败");
        }

        String taskId = JsonUtil.mapper().readTree(resp.body()).path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new BizException(50000, "提交 ASR 转写任务失败：缺少 task_id");
        }
        return taskId;
    }

    /**
     * 轮询异步任务状态，直到成功/失败/超时。
     */
    private JsonNode pollTask(String taskId, String apiKey) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        String taskUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;

        while (System.currentTimeMillis() < deadline) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(taskUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BizException(50000, "轮询 ASR 任务失败");
            }
            JsonNode output = JsonUtil.mapper().readTree(resp.body()).path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                return output;
            }
            if ("FAILED".equals(status)) {
                String message = output.path("message").asText("ASR 转写失败");
                throw new BizException(50000, message);
            }
            Thread.sleep(3000);
        }

        throw new BizException(50000, "语音转写超时，请稍后重试");
    }

    /**
     * 解析转写结果。
     * <ol>
     *   <li>Step 1: 校验任务结果和转写下载地址</li>
     *   <li>Step 2: 下载 transcription JSON</li>
     *   <li>Step 3: 按 speaker_id 聚合连续句子</li>
    *   <li>Step 4: 返回聚合后的原始说话人文本（标准化在预解析阶段执行）</li>
     * </ol>
     */
    private String parseTranscriptionText(JsonNode output, String apiKey) throws Exception {
        JsonNode results = output.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new BizException(50000, "转写结果为空");
        }

        JsonNode first = results.get(0);
        String subtaskStatus = first.path("subtask_status").asText("");
        if (!"SUCCEEDED".equals(subtaskStatus)) {
            String msg = first.path("message").asText("转写子任务失败");
            throw new BizException(50000, msg);
        }

        String transcriptionUrl = first.path("transcription_url").asText("");
        if (transcriptionUrl.isBlank()) {
            throw new BizException(50000, "转写结果地址为空");
        }

        // 下载 JSON 结果（OSS 跨地域可能较慢，read 给 120s + 重试 3 次）
        String respBody = null;
        Exception lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(transcriptionUrl))
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new BizException(50000, "下载转写结果失败: HTTP " + resp.statusCode());
                }
                respBody = resp.body();
                break;
            } catch (Exception e) {
                lastErr = e;
                log.warn("下载转写 JSON 失败（第 {}/3 次）: {}", attempt + 1, e.getMessage());
            }
        }
        if (respBody == null) {
            throw new BizException(50000, "下载转写 JSON 重试 3 次仍失败: "
                    + (lastErr == null ? "" : lastErr.getMessage()));
        }

        JsonNode root = JsonUtil.mapper().readTree(respBody);
        JsonNode transcripts = root.path("transcripts");
        if (!transcripts.isArray() || transcripts.isEmpty()) {
            return "";
        }

        JsonNode transcript = transcripts.get(0);
        JsonNode sentences = transcript.path("sentences");
        if (!sentences.isArray() || sentences.isEmpty()) {
            return transcript.path("text").asText("");
        }

        List<String> lines = new ArrayList<>();
        Integer currentSpeaker = null;
        StringBuilder currentText = new StringBuilder();

        for (JsonNode sentence : sentences) {
            String text = sentence.path("text").asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            int speakerId = sentence.path("speaker_id").asInt(0);

            if (currentSpeaker == null || speakerId != currentSpeaker) {
                if (currentText.length() > 0 && currentSpeaker != null) {
                    lines.add(speakerLabel(currentSpeaker) + "：" + currentText);
                }
                currentSpeaker = speakerId;
                currentText = new StringBuilder(text);
            } else {
                currentText.append(text);
            }
        }
        if (currentText.length() > 0 && currentSpeaker != null) {
            lines.add(speakerLabel(currentSpeaker) + "：" + currentText);
        }

        return String.join("\n", lines);
    }

    /** 将说话人 ID 映射为角色标签（复刻 Python _speaker_label：0→A，1→B，其余→编号）。 */
    private static String speakerLabel(Integer speakerId) {
        if (speakerId == null) {
            return "说话人";
        }
        if (speakerId == 0) {
            return "说话人A";
        }
        if (speakerId == 1) {
            return "说话人B";
        }
        return "说话人" + speakerId;
    }

    private HttpRequest.BodyPublisher buildMultipartBody(String boundary,
                                                         Map<String, String> fields,
                                                         Path file,
                                                         String filename) {
        // Step 1: 先写普通表单字段，再写文件字段，最后写结束边界。
        byte[] separator = "\r\n".getBytes(StandardCharsets.UTF_8);
        List<HttpRequest.BodyPublisher> publishers = new ArrayList<>();

        for (Map.Entry<String, String> e : fields.entrySet()) {
            String part = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue() + "\r\n";
            publishers.add(HttpRequest.BodyPublishers.ofByteArray(part.getBytes(StandardCharsets.UTF_8)));
        }

        String fileHeader = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        publishers.add(HttpRequest.BodyPublishers.ofByteArray(fileHeader.getBytes(StandardCharsets.UTF_8)));
        try {
            publishers.add(HttpRequest.BodyPublishers.ofFile(file));
        } catch (IOException e) {
            throw new BizException(50000, "读取待上传音频失败", e);
        }
        publishers.add(HttpRequest.BodyPublishers.ofByteArray(separator));
        publishers.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));

        return HttpRequest.BodyPublishers.concat(publishers.toArray(new HttpRequest.BodyPublisher[0]));
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String extractExt(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return ".wav";
        }
        return filename.substring(idx).toLowerCase(Locale.ROOT);
    }

}
