package com.genvn.api;

import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetRecord;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.persistence.GameSessionRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Picture status and picture bytes. Neither endpoint touches the session monitor, so they stay
 * responsive while a choice is waiting on a long text generation. Files are looked up only by
 * validated ids through the store; a client cannot name a path.
 */
@RestController
@RequestMapping("/api")
public class AssetController {

    private final AssetPipeline pipeline;
    private final AssetStore store;
    private final GameSessionRepository repository;
    private final AssetCoordinator coordinator;
    private final AccessGate gate;

    public AssetController(AssetPipeline pipeline, AssetStore store, GameSessionRepository repository) {
        this(pipeline, store, repository, AssetCoordinator.disabled());
    }

    public AssetController(AssetPipeline pipeline, AssetStore store, GameSessionRepository repository,
                           AssetCoordinator coordinator) {
        this(pipeline, store, repository, coordinator, AccessGate.open());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssetController(AssetPipeline pipeline, AssetStore store, GameSessionRepository repository,
                           AssetCoordinator coordinator, AccessGate gate) {
        this.pipeline = pipeline;
        this.store = store;
        this.repository = repository;
        this.coordinator = coordinator == null ? AssetCoordinator.disabled() : coordinator;
        this.gate = gate == null ? AccessGate.open() : gate;
    }

    @PostMapping("/sessions/{id}/assets/{assetId}/retry")
    public Map<String, Object> retry(@PathVariable String id, @PathVariable String assetId) {
        if (!AssetStore.safeSessionId(id) || !AssetStore.safeAssetId(assetId)) {
            throw new NotFoundException("图片不存在或游戏已删除");
        }
        var session = repository.find(id).orElseThrow(() -> new SessionNotFoundException("游戏不存在或已删除"));
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("游戏不存在或已删除");
            // A retry is a second chance with today's wording, not a replay of the text that failed.
            coordinator.refreshVariantPrompts(session);
            pipeline.manualRetry(id, assetId);
            return status(id);
        }
    }

    /** Lightweight, lock-free, safe to poll every couple of seconds. */
    @GetMapping("/sessions/{id}/assets")
    public Map<String, Object> status(@PathVariable String id) {
        if (!AssetStore.safeSessionId(id)) throw new NotFoundException("No session with id '" + id + "'");
        Map<String, Object> status = pipeline.status(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) status.get("assets");
        // An <img> cannot send the key header, so a locked server signs picture URLs per session.
        String token = gate.assetToken(id);
        String suffix = token.isEmpty() ? "" : "&" + AccessGate.ASSET_TOKEN_PARAM + "=" + token;
        for (Map<String, Object> row : rows) {
            boolean ready = AssetStatus.READY.name().equals(row.get("status"));
            row.put("url", ready
                    ? "/api/assets/" + id + "/" + row.get("assetId") + "?v=" + row.get("generationVersion") + suffix
                    : null);
        }
        return status;
    }

    @GetMapping("/assets/{sessionId}/{assetId}")
    public ResponseEntity<Resource> file(@PathVariable String sessionId, @PathVariable String assetId) {
        if (!AssetStore.safeSessionId(sessionId) || !AssetStore.safeAssetId(assetId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<AssetManifest> manifest = pipeline.snapshot(sessionId);
        if (manifest.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        AssetRecord record = manifest.get().get(assetId);
        if (record == null || record.status != AssetStatus.READY || record.fileName == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<Path> path = store.imagePath(sessionId, record.fileName);
        if (path.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        MediaType type = MediaType.parseMediaType(AssetStore.mimeFor(record.fileName));
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(new FileSystemResource(path.get()));
    }
}
