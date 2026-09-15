package com.genvn.config;

import com.genvn.asset.AssetPipeline;
import com.genvn.asset.ReloadableImageProvider;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.ReloadableLlmClient;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.SpareDesignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and writes {@code config/application.yml} from the running app. Most keys are applied
 * immediately to the live property beans; bind address, port and data-dir still need a restart.
 */
@Service
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    public static final Path DEFAULT_FILE = Path.of("config", "application.yml");

    public enum Kind { STRING, SECRET, INTEGER, NUMBER, BOOLEAN, LIST }

    public record Field(
            String key,
            String group,
            String label,
            String hint,
            Kind kind,
            Object value,
            boolean secretSet,
            boolean restartRequired
    ) {}

    public record View(String file, List<Field> fields, List<String> applied, List<String> restartPending) {}

    private record Spec(String key, String group, String label, String hint, Kind kind, boolean restartRequired) {}

    private static final List<Spec> CATALOG = List.of(
            spec("server.address", "服务器", "监听地址", "仅本机用 127.0.0.1。改到 0.0.0.0 前请先设置访问密钥。", Kind.STRING, true),
            spec("server.port", "服务器", "端口", "默认 8080。修改后需要重启后端。", Kind.INTEGER, true),
            spec("llm.api-key", "语言模型", "API 密钥", "留空则使用离线模拟。已保存的密钥不会回传。", Kind.SECRET, false),
            spec("llm.base-url", "语言模型", "接口地址", "OpenAI 兼容 /chat/completions 的根路径，不要带后缀。", Kind.STRING, false),
            spec("llm.model", "语言模型", "模型", "例如 gpt-4o-mini。", Kind.STRING, false),
            spec("llm.temperature", "语言模型", "温度", "0 到 2。越高越发散。", Kind.NUMBER, false),
            spec("llm.stream", "语言模型", "流式输出", "长生成时保持网关不断开。", Kind.BOOLEAN, false),
            spec("llm.timeout-seconds", "语言模型", "总超时（秒）", "一次生成的墙钟上限。", Kind.INTEGER, false),
            spec("llm.idle-timeout-seconds", "语言模型", "空闲超时（秒）", "开始输出后允许的最长静默。0 表示关闭。", Kind.INTEGER, false),
            spec("llm.json-mode", "语言模型", "JSON 模式", "关闭后给不接受 json_object 的提供商。", Kind.BOOLEAN, false),
            spec("llm.force-mock", "语言模型", "强制离线模拟", "即使填写了密钥也不调用真实模型。", Kind.BOOLEAN, false),
            spec("llm.reasoning.effort", "推理力度", "默认推理力度", "none / minimal / low / medium / high / xhigh，留空则不发送。", Kind.STRING, false),
            spec("llm.reasoning.story-compile", "推理力度", "编译故事", "覆盖默认。", Kind.STRING, false),
            spec("llm.reasoning.scene-generate", "推理力度", "生成场景", "覆盖默认。", Kind.STRING, false),
            spec("llm.reasoning.arc-continue", "推理力度", "续写章节", "覆盖默认。", Kind.STRING, false),
            spec("llm.reasoning.choice-probabilities", "推理力度", "选项排序", "覆盖默认。", Kind.STRING, false),
            spec("llm.reasoning.spare-designs", "推理力度", "备用形象", "覆盖默认。", Kind.STRING, false),
            spec("llm.reasoning.story-restructure", "推理力度", "重塑剧情", "覆盖默认。重塑要改写铁律与后续节拍，值得更多推理。", Kind.STRING, false),
            spec("genvn.data-dir", "引擎", "存档目录", "相对后端工作目录。修改后需要重启。", Kind.STRING, true),
            spec("genvn.spare-designs", "引擎", "备用形象数量", "0 关闭补充。最多 3。", Kind.INTEGER, false),
            spec("genvn.access-key", "引擎", "访问密钥", "空表示本机开放。已保存的密钥不会回传。", Kind.SECRET, false),
            spec("genvn.allowed-origins", "引擎", "允许的前端来源", "每行一个源，例如 https://vn.example.com。本机来源始终允许。", Kind.LIST, false),
            spec("genvn.language", "引擎", "界面与生成语言", "zh 简体中文，en English。界面文案和模型输出都使用该语言。", Kind.STRING, false),
            spec("genvn.speculation.enabled", "预推演与续章", "预推演", "阅读时预先生成选项结果。", Kind.BOOLEAN, false),
            spec("genvn.speculation.max-branches", "预推演与续章", "每幕分支上限", "每幕最多 4 个选项，各一条。", Kind.INTEGER, false),
            spec("genvn.speculation.threads", "预推演与续章", "预推演线程", "后台生成池大小。", Kind.INTEGER, false),
            spec("genvn.continuation.enabled", "预推演与续章", "自动续章", "主线将尽时在后台规划下一章。", Kind.BOOLEAN, false),
            spec("genvn.continuation.threshold", "预推演与续章", "续章阈值", "已完成节拍比例，0 到 1。", Kind.NUMBER, false),
            spec("image.enabled", "图片", "启用配图", "关闭时只显示占位。", Kind.BOOLEAN, false),
            spec("image.provider", "图片", "图片提供商", "openai 或 disabled。", Kind.STRING, false),
            spec("image.api-key", "图片", "图片 API 密钥", "已保存的密钥不会回传。", Kind.SECRET, false),
            spec("image.base-url", "图片", "图片接口地址", "OpenAI Images API 根路径。", Kind.STRING, false),
            spec("image.model", "图片", "图片模型", "例如 gpt-image-1。", Kind.STRING, false),
            spec("image.background-size", "图片", "背景尺寸", "模型支持的横向尺寸。", Kind.STRING, false),
            spec("image.portrait-size", "图片", "立绘尺寸", "模型支持的纵向尺寸。", Kind.STRING, false),
            spec("image.quality", "图片", "画质", "gpt-image: low|medium|high|auto。留空用提供商默认。", Kind.STRING, false),
            spec("image.output-format", "图片", "输出格式", "png 或 jpeg。", Kind.STRING, false),
            spec("image.timeout-seconds", "图片", "总超时（秒）", "一次出图的墙钟上限。", Kind.INTEGER, false),
            spec("image.idle-timeout-seconds", "图片", "空闲超时（秒）", "收包时允许的最长静默。0 表示关闭。", Kind.INTEGER, false),
            spec("image.concurrency", "图片", "出图并发", "增加立即生效；减少会在工人空闲后收回。", Kind.INTEGER, false),
            spec("image.queue-capacity", "图片", "队列容量", "排队中的出图上限。", Kind.INTEGER, false),
            spec("image.max-attempts", "图片", "每图尝试次数", "含超时与 429 后的重试。", Kind.INTEGER, false),
            spec("image.first-batch-budget", "图片", "开场自动出图", "编译后立刻排队的数量。", Kind.INTEGER, false),
            spec("image.arc-budget", "图片", "每章预算", "0 表示不封顶。", Kind.INTEGER, false),
            spec("image.plan.locations", "图片", "预绘地点数", "编译时规划的背景数量。", Kind.INTEGER, false),
            spec("image.plan.characters", "图片", "预绘角色数", "编译时规划的人物数量。", Kind.INTEGER, false),
            spec("image.plan.expressions", "图片", "表情变体", "逗号分隔，例如 worried,suspicious。", Kind.STRING, false)
    );

    private static Spec spec(String key, String group, String label, String hint, Kind kind, boolean restart) {
        return new Spec(key, group, label, hint, kind, restart);
    }

    private final LlmProperties llm;
    private final GenvnProperties genvn;
    private final ImageProperties image;
    private final Environment environment;
    private final Path file;
    private final ReloadableLlmClient llmReload;
    private final ReloadableImageProvider imageReload;
    private final SpeculativeGenerator speculative;
    private final SpareDesignService spareDesigns;
    private final AssetPipeline pipeline;
    private final Map<String, Object> pendingRestart = new ConcurrentHashMap<>();

    @Autowired
    public RuntimeSettings(LlmProperties llm, GenvnProperties genvn, ImageProperties image, Environment environment,
                           ReloadableLlmClient llmReload, ReloadableImageProvider imageReload,
                           SpeculativeGenerator speculative, SpareDesignService spareDesigns, AssetPipeline pipeline) {
        this(llm, genvn, image, environment, DEFAULT_FILE, llmReload, imageReload, speculative, spareDesigns, pipeline);
    }

    public RuntimeSettings(LlmProperties llm, GenvnProperties genvn, ImageProperties image, Environment environment,
                           Path file, ReloadableLlmClient llmReload, ReloadableImageProvider imageReload,
                           SpeculativeGenerator speculative, SpareDesignService spareDesigns, AssetPipeline pipeline) {
        this.llm = llm;
        this.genvn = genvn;
        this.image = image;
        this.environment = environment;
        this.file = file;
        this.llmReload = llmReload;
        this.imageReload = imageReload;
        this.speculative = speculative;
        this.spareDesigns = spareDesigns;
        this.pipeline = pipeline;
    }

    public View snapshot() {
        List<Field> fields = new ArrayList<>();
        for (Spec spec : CATALOG) fields.add(toField(spec));
        return new View(file.toString(), fields, List.of(), List.copyOf(pendingRestart.keySet()));
    }

    public View update(Map<String, Object> values) {
        if (values == null) throw new IllegalArgumentException("values object is required");
        Set<String> known = CATALOG.stream().map(Spec::key).collect(java.util.stream.Collectors.toSet());
        List<String> applied = new ArrayList<>();
        for (String key : values.keySet()) {
            if (!known.contains(key)) throw new IllegalArgumentException("unknown setting: " + key);
        }
        for (Spec spec : CATALOG) {
            if (!values.containsKey(spec.key())) continue;
            Object coerced = coerce(spec, values.get(spec.key()));
            writeLive(spec, coerced);
            applied.add(spec.key());
        }
        persistFile();
        hotReload(applied);
        log.info("Settings updated: {} key(s) applied{}", applied.size(),
                pendingRestart.isEmpty() ? "" : "; restart still needed for " + pendingRestart.keySet());
        List<Field> fields = new ArrayList<>();
        for (Spec spec : CATALOG) fields.add(toField(spec));
        return new View(file.toString(), fields, applied, List.copyOf(pendingRestart.keySet()));
    }

    private Field toField(Spec spec) {
        Object raw = readLive(spec.key());
        boolean secretSet = spec.kind() == Kind.SECRET && raw instanceof String s && !s.isBlank();
        Object value = spec.kind() == Kind.SECRET ? null : raw;
        return new Field(spec.key(), spec.group(), spec.label(), spec.hint(), spec.kind(), value,
                secretSet, spec.restartRequired());
    }

    private Object readLive(String key) {
        if (pendingRestart.containsKey(key)) return pendingRestart.get(key);
        return switch (key) {
            case "server.address" -> environment == null ? "127.0.0.1"
                    : environment.getProperty("server.address", "127.0.0.1");
            case "server.port" -> {
                String port = environment == null ? "8080" : environment.getProperty("server.port", "8080");
                yield Integer.parseInt(port);
            }
            case "llm.api-key" -> llm.getApiKey();
            case "llm.base-url" -> llm.getBaseUrl();
            case "llm.model" -> llm.getModel();
            case "llm.temperature" -> llm.getTemperature();
            case "llm.stream" -> llm.isStream();
            case "llm.timeout-seconds" -> llm.getTimeoutSeconds();
            case "llm.idle-timeout-seconds" -> llm.getIdleTimeoutSeconds();
            case "llm.json-mode" -> llm.isJsonMode();
            case "llm.force-mock" -> llm.isForceMock();
            case "llm.reasoning.effort" -> llm.getReasoning().getEffort();
            case "llm.reasoning.story-compile" -> llm.getReasoning().getStoryCompile();
            case "llm.reasoning.scene-generate" -> llm.getReasoning().getSceneGenerate();
            case "llm.reasoning.arc-continue" -> llm.getReasoning().getArcContinue();
            case "llm.reasoning.choice-probabilities" -> llm.getReasoning().getChoiceProbabilities();
            case "llm.reasoning.spare-designs" -> llm.getReasoning().getSpareDesigns();
            case "llm.reasoning.story-restructure" -> llm.getReasoning().getStoryRestructure();
            case "genvn.data-dir" -> genvn.getDataDir();
            case "genvn.spare-designs" -> genvn.getSpareDesigns();
            case "genvn.access-key" -> genvn.getAccessKey();
            case "genvn.allowed-origins" -> genvn.getAllowedOrigins();
            case "genvn.language" -> genvn.getLanguage();
            case "genvn.speculation.enabled" -> genvn.getSpeculation().isEnabled();
            case "genvn.speculation.max-branches" -> genvn.getSpeculation().getMaxBranches();
            case "genvn.speculation.threads" -> genvn.getSpeculation().getThreads();
            case "genvn.continuation.enabled" -> genvn.getContinuation().isEnabled();
            case "genvn.continuation.threshold" -> genvn.getContinuation().getThreshold();
            case "image.enabled" -> image.isEnabled();
            case "image.provider" -> image.getProvider();
            case "image.api-key" -> image.getApiKey();
            case "image.base-url" -> image.getBaseUrl();
            case "image.model" -> image.getModel();
            case "image.background-size" -> image.getBackgroundSize();
            case "image.portrait-size" -> image.getPortraitSize();
            case "image.quality" -> image.getQuality();
            case "image.output-format" -> image.getOutputFormat();
            case "image.timeout-seconds" -> image.getTimeoutSeconds();
            case "image.idle-timeout-seconds" -> image.getIdleTimeoutSeconds();
            case "image.concurrency" -> image.getConcurrency();
            case "image.queue-capacity" -> image.getQueueCapacity();
            case "image.max-attempts" -> image.getMaxAttempts();
            case "image.first-batch-budget" -> image.getFirstBatchBudget();
            case "image.arc-budget" -> image.getArcBudget();
            case "image.plan.locations" -> image.getPlan().getLocations();
            case "image.plan.characters" -> image.getPlan().getCharacters();
            case "image.plan.expressions" -> image.getPlan().getExpressions();
            default -> null;
        };
    }

    private void writeLive(Spec spec, Object value) {
        if (spec.restartRequired()) {
            pendingRestart.put(spec.key(), value);
            return;
        }
        switch (spec.key()) {
            case "llm.api-key" -> llm.setApiKey((String) value);
            case "llm.base-url" -> llm.setBaseUrl((String) value);
            case "llm.model" -> llm.setModel((String) value);
            case "llm.temperature" -> llm.setTemperature((Double) value);
            case "llm.stream" -> llm.setStream((Boolean) value);
            case "llm.timeout-seconds" -> llm.setTimeoutSeconds((Integer) value);
            case "llm.idle-timeout-seconds" -> llm.setIdleTimeoutSeconds((Integer) value);
            case "llm.json-mode" -> llm.setJsonMode((Boolean) value);
            case "llm.force-mock" -> llm.setForceMock((Boolean) value);
            case "llm.reasoning.effort" -> llm.getReasoning().setEffort((String) value);
            case "llm.reasoning.story-compile" -> llm.getReasoning().setStoryCompile((String) value);
            case "llm.reasoning.scene-generate" -> llm.getReasoning().setSceneGenerate((String) value);
            case "llm.reasoning.arc-continue" -> llm.getReasoning().setArcContinue((String) value);
            case "llm.reasoning.choice-probabilities" -> llm.getReasoning().setChoiceProbabilities((String) value);
            case "llm.reasoning.spare-designs" -> llm.getReasoning().setSpareDesigns((String) value);
            case "llm.reasoning.story-restructure" -> llm.getReasoning().setStoryRestructure((String) value);
            case "genvn.spare-designs" -> genvn.setSpareDesigns((Integer) value);
            case "genvn.access-key" -> genvn.setAccessKey((String) value);
            case "genvn.allowed-origins" -> {
                @SuppressWarnings("unchecked")
                List<String> origins = (List<String>) value;
                genvn.setAllowedOrigins(origins);
            }
            case "genvn.language" -> genvn.setLanguage((String) value);
            case "genvn.speculation.enabled" -> genvn.getSpeculation().setEnabled((Boolean) value);
            case "genvn.speculation.max-branches" -> genvn.getSpeculation().setMaxBranches((Integer) value);
            case "genvn.speculation.threads" -> genvn.getSpeculation().setThreads((Integer) value);
            case "genvn.continuation.enabled" -> genvn.getContinuation().setEnabled((Boolean) value);
            case "genvn.continuation.threshold" -> genvn.getContinuation().setThreshold((Double) value);
            case "image.enabled" -> image.setEnabled((Boolean) value);
            case "image.provider" -> image.setProvider((String) value);
            case "image.api-key" -> image.setApiKey((String) value);
            case "image.base-url" -> image.setBaseUrl((String) value);
            case "image.model" -> image.setModel((String) value);
            case "image.background-size" -> image.setBackgroundSize((String) value);
            case "image.portrait-size" -> image.setPortraitSize((String) value);
            case "image.quality" -> image.setQuality((String) value);
            case "image.output-format" -> image.setOutputFormat((String) value);
            case "image.timeout-seconds" -> image.setTimeoutSeconds((Integer) value);
            case "image.idle-timeout-seconds" -> image.setIdleTimeoutSeconds((Integer) value);
            case "image.concurrency" -> image.setConcurrency((Integer) value);
            case "image.queue-capacity" -> image.setQueueCapacity((Integer) value);
            case "image.max-attempts" -> image.setMaxAttempts((Integer) value);
            case "image.first-batch-budget" -> image.setFirstBatchBudget((Integer) value);
            case "image.arc-budget" -> image.setArcBudget((Integer) value);
            case "image.plan.locations" -> image.getPlan().setLocations((Integer) value);
            case "image.plan.characters" -> image.getPlan().setCharacters((Integer) value);
            case "image.plan.expressions" -> image.getPlan().setExpressions((String) value);
            default -> throw new IllegalArgumentException("unknown setting: " + spec.key());
        }
    }

    private void hotReload(List<String> applied) {
        if (applied.stream().anyMatch(k -> k.startsWith("llm."))) {
            if (llmReload != null) llmReload.reload();
        }
        if (applied.stream().anyMatch(k -> k.startsWith("image."))) {
            if (imageReload != null) imageReload.reload();
            if (pipeline != null) pipeline.syncWorkers();
        }
        if (applied.contains("genvn.speculation.threads") && speculative != null) {
            speculative.reconfigurePool();
        }
        if (applied.contains("genvn.spare-designs") && spareDesigns != null) {
            spareDesigns.setTarget(genvn.getSpareDesigns());
        }
    }

    private Object coerce(Spec spec, Object raw) {
        if (spec.kind() == Kind.SECRET && raw == null) {
            Object current = readLive(spec.key());
            return current == null ? "" : current;
        }
        return switch (spec.kind()) {
            case STRING, SECRET -> {
                String text = raw == null ? "" : String.valueOf(raw);
                if (spec.key().equals("genvn.language")) yield UiLanguage.normalize(text);
                yield text;
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) yield b;
                yield Boolean.parseBoolean(String.valueOf(raw));
            }
            case INTEGER -> {
                int n = raw instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(raw).trim());
                if (spec.key().equals("server.port") && (n < 1 || n > 65535)) {
                    throw new IllegalArgumentException("server.port must be 1-65535");
                }
                if (n < 0) throw new IllegalArgumentException(spec.key() + " must be >= 0");
                yield n;
            }
            case NUMBER -> {
                double n = raw instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
                if (spec.key().equals("llm.temperature") && (n < 0 || n > 2)) {
                    throw new IllegalArgumentException("llm.temperature must be 0-2");
                }
                if (spec.key().equals("genvn.continuation.threshold") && (n < 0 || n > 1)) {
                    throw new IllegalArgumentException("genvn.continuation.threshold must be 0-1");
                }
                yield n;
            }
            case LIST -> {
                List<String> out = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        String line = item == null ? "" : String.valueOf(item).trim();
                        if (!line.isEmpty()) out.add(line);
                    }
                } else if (raw != null) {
                    for (String line : String.valueOf(raw).split("\\R")) {
                        String t = line.trim();
                        if (!t.isEmpty()) out.add(t);
                    }
                }
                yield out;
            }
        };
    }

    private void persistFile() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", readLive("server.address"));
        server.put("port", readLive("server.port"));
        root.put("server", server);

        Map<String, Object> llmMap = new LinkedHashMap<>();
        llmMap.put("api-key", nullToEmpty(llm.getApiKey()));
        llmMap.put("base-url", llm.getBaseUrl());
        llmMap.put("model", llm.getModel());
        llmMap.put("temperature", llm.getTemperature());
        llmMap.put("stream", llm.isStream());
        llmMap.put("timeout-seconds", llm.getTimeoutSeconds());
        llmMap.put("idle-timeout-seconds", llm.getIdleTimeoutSeconds());
        llmMap.put("json-mode", llm.isJsonMode());
        Map<String, Object> reasoning = new LinkedHashMap<>();
        reasoning.put("effort", nullToEmpty(llm.getReasoning().getEffort()));
        reasoning.put("story-compile", nullToEmpty(llm.getReasoning().getStoryCompile()));
        reasoning.put("scene-generate", nullToEmpty(llm.getReasoning().getSceneGenerate()));
        reasoning.put("arc-continue", nullToEmpty(llm.getReasoning().getArcContinue()));
        reasoning.put("choice-probabilities", nullToEmpty(llm.getReasoning().getChoiceProbabilities()));
        reasoning.put("spare-designs", nullToEmpty(llm.getReasoning().getSpareDesigns()));
        reasoning.put("story-restructure", nullToEmpty(llm.getReasoning().getStoryRestructure()));
        llmMap.put("reasoning", reasoning);
        llmMap.put("force-mock", llm.isForceMock());
        root.put("llm", llmMap);

        Map<String, Object> genvnMap = new LinkedHashMap<>();
        genvnMap.put("data-dir", readLive("genvn.data-dir"));
        genvnMap.put("spare-designs", genvn.getSpareDesigns());
        genvnMap.put("access-key", nullToEmpty(genvn.getAccessKey()));
        genvnMap.put("allowed-origins", genvn.getAllowedOrigins());
        genvnMap.put("language", genvn.getLanguage());
        Map<String, Object> speculation = new LinkedHashMap<>();
        speculation.put("enabled", genvn.getSpeculation().isEnabled());
        speculation.put("max-branches", genvn.getSpeculation().getMaxBranches());
        speculation.put("threads", genvn.getSpeculation().getThreads());
        genvnMap.put("speculation", speculation);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("enabled", genvn.getContinuation().isEnabled());
        continuation.put("threshold", genvn.getContinuation().getThreshold());
        genvnMap.put("continuation", continuation);
        root.put("genvn", genvnMap);

        Map<String, Object> imageMap = new LinkedHashMap<>();
        imageMap.put("enabled", image.isEnabled());
        imageMap.put("provider", image.getProvider());
        imageMap.put("api-key", nullToEmpty(image.getApiKey()));
        imageMap.put("base-url", image.getBaseUrl());
        imageMap.put("model", image.getModel());
        imageMap.put("background-size", image.getBackgroundSize());
        imageMap.put("portrait-size", image.getPortraitSize());
        imageMap.put("quality", nullToEmpty(image.getQuality()));
        imageMap.put("output-format", image.getOutputFormat());
        imageMap.put("timeout-seconds", image.getTimeoutSeconds());
        imageMap.put("idle-timeout-seconds", image.getIdleTimeoutSeconds());
        imageMap.put("concurrency", image.getConcurrency());
        imageMap.put("queue-capacity", image.getQueueCapacity());
        imageMap.put("max-attempts", image.getMaxAttempts());
        imageMap.put("first-batch-budget", image.getFirstBatchBudget());
        imageMap.put("arc-budget", image.getArcBudget());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("locations", image.getPlan().getLocations());
        plan.put("characters", image.getPlan().getCharacters());
        plan.put("expressions", image.getPlan().getExpressions());
        imageMap.put("plan", plan);
        root.put("image", imageMap);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        String body = new Yaml(options).dump(root);
        String header = """
                # Written by genvn's in-app settings. Restart the backend to apply
                # server.address, server.port and genvn.data-dir. Other keys take effect immediately.
                # Secrets in this file are not logged. Keep the file private.
                
                """;
        Path tmp = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "settings-", ".yml.tmp");
            Files.writeString(tmp, header + body);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not write {}: {}", file, e.toString());
            throw new IllegalArgumentException("could not write " + file + ": " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* leftover tmp is harmless */ }
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
