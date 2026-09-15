package com.genvn.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetCoordinator;
import com.genvn.config.GenvnProperties;
import com.genvn.game.GameSession;
import com.genvn.game.PlayerCharacter;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.StructuredLlm;
import com.genvn.persistence.GameSessionRepository;
import com.genvn.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the pool of unassigned appearance-only designs topped up. A design belongs to exactly
 * one person: the moment a committed scene gives it an identity, the design leaves the pool,
 * its pictures keep their id (which is now that person's id) and a replacement is sketched in
 * the background. Purely additive and never load-bearing: if the call fails, the pool is
 * simply smaller until the next commit tries again.
 */
@Service
public class SpareDesignService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpareDesignService.class);

    private final StructuredLlm llm;
    private final GameSessionRepository repository;
    private final AssetCoordinator assets;
    private final ObjectMapper mapper;
    private final GenvnProperties properties;
    private volatile int target;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "genvn-spare-designs");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public SpareDesignService(StructuredLlm llm, GenvnProperties properties, GameSessionRepository repository,
                              AssetCoordinator assets, ObjectMapper mapper) {
        this.llm = llm;
        this.repository = repository;
        this.assets = assets == null ? AssetCoordinator.disabled() : assets;
        this.mapper = mapper;
        this.properties = properties;
        setTarget(properties.getSpareDesigns());
    }

    /** Pool size to maintain; capped by {@link PreparedVisual#MAX_PREPARED}, 0 disables. */
    public void setTarget(int target) {
        this.target = Math.max(0, Math.min(PreparedVisual.MAX_PREPARED, target));
    }

    public int target() {
        return target;
    }

    /** Called with the session monitor held, after a scene has been committed. */
    public void maybeReplenish(GameSession session) {
        int wanted = target;
        if (wanted <= 0 || session.deleted || session.story == null) return;
        int have = session.story.preparedVisuals == null ? 0 : session.story.preparedVisuals.size();
        if (have >= wanted) return;
        if (!inFlight.add(session.id)) return;
        final int needed = wanted - have;
        final CompiledStory story;
        try {
            story = mapper.convertValue(session.story, CompiledStory.class);
        } catch (RuntimeException e) {
            inFlight.remove(session.id);
            return;
        }
        executor.submit(() -> {
            try {
                List<PreparedVisual> sketched = llm.call(request(story, needed), SpareDesignResponse.class,
                        r -> validate(r, story, needed)).value().preparedVisuals();
                List<PreparedVisual> added = new ArrayList<>();
                synchronized (session) {
                    if (session.deleted) return;
                    List<PreparedVisual> pool = new ArrayList<>(session.story.preparedVisuals);
                    for (PreparedVisual design : sketched) {
                        if (pool.size() >= target) break;
                        // Re-check against the live story: a scene may have introduced someone meanwhile.
                        if (session.story.visualCharacter(design.id()) != null || session.story.preparedVisual(design.id()) != null) continue;
                        pool.add(design);
                        added.add(design);
                    }
                    if (added.isEmpty()) return;
                    session.story.preparedVisuals = pool;
                    repository.save(session);
                    assets.onSpareDesignsAdded(session, added);
                }
                log.info("Session {}: sketched {} spare design(s) to keep {} on hand: {}", session.id, added.size(), target,
                        added.stream().map(PreparedVisual::id).toList());
            } catch (RuntimeException e) {
                log.info("Session {}: spare design sketch failed, the pool stays smaller for now ({})", session.id, e.getMessage());
            } finally {
                inFlight.remove(session.id);
            }
        });
    }

    private LlmRequest request(CompiledStory story, int needed) {
        List<String> established = new ArrayList<>();
        for (NpcProfile npc : story.bible.characters()) if (npc.visualDescription() != null && !npc.visualDescription().isBlank()) established.add(npc.visualDescription());
        for (NpcProfile npc : story.encounteredNpcs) if (npc.visualDescription() != null && !npc.visualDescription().isBlank()) established.add(npc.visualDescription());
        if (story.playerVisual != null && story.playerVisual.visualDescription() != null) established.add(story.playerVisual.visualDescription());
        List<String> spares = story.preparedVisuals.stream().map(PreparedVisual::visualDescription).toList();
        List<String> taken = new ArrayList<>(takenIds(story));
        // The compiler's own designs fit because it saw the whole outline; a replacement must see
        // the same world: fixed facts, canon, and the places people move through set the era.
        List<String> places = story.bible.locations().stream()
                .map(loc -> loc.name() + (loc.description() == null || loc.description().isBlank() ? "" : " -- " + loc.description()))
                .toList();
        var setting = new Prompts.SpareDesignSetting(
                story.authorCanon == null ? List.of() : story.authorCanon.facts(),
                story.bible.premise(), story.bible.tone(), story.bible.themes(),
                story.bible.hardCanon(), story.bible.softCanon(), places, story.artStyle);
        return LlmRequest.of(LlmPurpose.SPARE_DESIGNS, Prompts.spareDesignSystem(properties.getLanguage()),
                Prompts.spareDesignUser(needed, setting, established, spares, taken),
                Map.of("story", story, "count", needed, "takenIds", taken, "language", properties.getLanguage()));
    }

    /** Every id that already names somebody or some design; a spare must not collide with any of them. */
    public static Set<String> takenIds(CompiledStory story) {
        Set<String> taken = new LinkedHashSet<>();
        taken.add(PlayerCharacter.ID);
        for (NpcProfile npc : story.bible.characters()) taken.add(npc.id());
        for (NpcProfile npc : story.encounteredNpcs) taken.add(npc.id());
        for (PreparedVisual design : story.preparedVisuals) taken.add(design.id());
        return taken;
    }

    /** Null when usable. */
    static String validate(SpareDesignResponse response, CompiledStory story, int needed) {
        if (response == null || response.preparedVisuals().isEmpty()) return "missing 'preparedVisuals' (need " + needed + ")";
        Set<String> taken = takenIds(story);
        Set<String> seen = new HashSet<>();
        for (PreparedVisual design : response.preparedVisuals()) {
            if (design == null || !PreparedVisual.validId(design.id())) return "a design has an invalid id (short lowercase slug like npc_visual_4)";
            if (taken.contains(design.id())) return "id '" + design.id() + "' is already taken; choose an unused id";
            if (!seen.add(design.id())) return "duplicate design id '" + design.id() + "'";
            if (design.visualDescription() == null || design.visualDescription().isBlank()
                    || design.visualDescription().length() > 2000) return "design '" + design.id() + "' needs an appearance description of 1-2000 characters";
        }
        return null;
    }

    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
