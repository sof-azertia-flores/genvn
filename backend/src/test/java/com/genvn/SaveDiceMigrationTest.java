package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.config.GenvnProperties;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.persistence.FileGameSessionRepository;
import com.genvn.persistence.SaveDiceMigration;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.StoryCompiler;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Saves written before dice were cast ahead are brought up to date once, on disk, at startup. */
class SaveDiceMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Stack(SessionService sessions, FileGameSessionRepository repository, SpeculativeGenerator speculative) {}

    private Stack stack(Path dataDir, ScriptedRandom dice) {
        var client = new ScriptedLlmClient(mapper, request -> ScriptedLlmClient.choiceOf(request) == null
                ? SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .choiceWithCheck("cD", "Climb to the window", "Agility", 12)
                        .choice("cP", "Wait", "cautious").build()
                : SceneJson.scene("Later.").choice("c1", "Go on", "cautious").build());
        var properties = Engine.properties(false, false);
        properties.setDataDir(dataDir.toString());
        var llm = new StructuredLlm(client, mapper, new LlmCallLog());
        var generator = new SceneGenerator(llm, new ContextRenderer());
        var reducer = new StateReducer();
        var cache = new BranchCache();
        var resolver = new CheckResolver(new DiceService(dice));
        var speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties, resolver);
        var repository = new FileGameSessionRepository(mapper, properties);
        var sessions = new SessionService(new StoryCompiler(llm), generator, reducer, resolver, cache, speculative,
                new ArcContinuationService(llm, new ContextRenderer(), properties), repository, mapper);
        return new Stack(sessions, repository, speculative);
    }

    @Test
    @DisplayName("an older save gets dice for its current scene, keeps a die the player already saw, and is written back")
    void olderSaveIsMigratedOnDiskAndKeepsTheRevealedDie(@TempDir Path dir) throws Exception {
        Stack first = stack(dir, new ScriptedRandom(4, 15));
        GameSession created;
        try {
            created = first.sessions.create(Engine.OUTLINE, Engine.alex());
            // The player already revealed cC's die (4): that number must survive the migration.
            first.sessions.roll(created.id, "cC");
        } finally {
            first.speculative.close();
        }
        Path file = dir.resolve("sessions").resolve(created.id + ".json");
        ObjectNode json = (ObjectNode) mapper.readTree(file.toFile());
        assertTrue(json.has("sceneDice"), "a fresh save carries its dice");
        // Rewrite the file the way an older build left it: no dice, only the revealed pending roll.
        json.remove("sceneDice");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), json);
        assertFalse(mapper.readTree(file.toFile()).has("sceneDice"));

        // A new process: cD would draw 20 if migration cast it, cC must keep the revealed 4.
        Stack second = stack(dir, new ScriptedRandom(20, 20));
        try {
            new SaveDiceMigration(second.repository, second.sessions).run(null);
        } finally {
            second.speculative.close();
        }

        ObjectNode migrated = (ObjectNode) mapper.readTree(file.toFile());
        assertTrue(migrated.has("sceneDice"), "the migration wrote the dice back to disk");
        assertEquals(4, migrated.get("sceneDice").get("cC").get("d20").asInt(), "the revealed die is the scene's die");
        assertEquals(20, migrated.get("sceneDice").get("cD").get("d20").asInt(), "the unrevealed choice got a fresh die");
        assertFalse(migrated.get("sceneDice").has("cP"), "a plain choice has no die");
        assertEquals(4, migrated.get("pendingRoll").get("roll").get("d20").asInt(), "the pending roll itself is untouched");

        // Running again changes nothing: dice are cast once per scene, never re-cast.
        String before = Files.readString(file);
        Stack third = stack(dir, new ScriptedRandom(1, 1));
        try {
            new SaveDiceMigration(third.repository, third.sessions).run(null);
        } finally {
            third.speculative.close();
        }
        assertEquals(mapper.readTree(before).get("sceneDice"), mapper.readTree(file.toFile()).get("sceneDice"));
    }
}
