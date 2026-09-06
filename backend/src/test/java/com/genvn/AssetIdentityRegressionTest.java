package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.asset.*;
import com.genvn.llm.*;
import com.genvn.story.StoryCompiler;
import com.genvn.support.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssetIdentityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"characters,punctuation", "characters,duplicate", "characters,long",
            "locations,punctuation", "locations,duplicate", "locations,long"})
    void unsafeOrDuplicateCompiledIdsRequireRepairBeforeAnyStoryIsAccepted(String collection, String defect) {
        var fallback = new MockLlmClient(mapper);
        var calls = new AtomicInteger();
        LlmClient client = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest request) {
                LlmResponse response = fallback.complete(request);
                if (calls.incrementAndGet() != 1) return response;
                try {
                    ObjectNode root = (ObjectNode) mapper.readTree(response.text());
                    ObjectNode first = ((ObjectNode) root.path("bible").path(collection).get(0)).deepCopy();
                    ObjectNode second = first.deepCopy();
                    String firstId = switch (defect) {
                        case "punctuation" -> "subject.A";
                        case "long" -> "subject_" + "a".repeat(48) + "x";
                        default -> "subject_same";
                    };
                    String secondId = switch (defect) {
                        case "punctuation" -> "subject_A";
                        case "long" -> "subject_" + "a".repeat(48) + "y";
                        default -> firstId;
                    };
                    first.put("id", firstId); second.put("id", secondId);
                    root.withObject("bible").putArray(collection).add(first).add(second);
                    return new LlmResponse(root.toString(), "identity-test", 0);
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
            @Override public String describe() { return "identity-test"; }
            @Override public boolean isMock() { return true; }
        };
        var compiler = new StoryCompiler(new StructuredLlm(client, mapper, new LlmCallLog()));
        var compiled = compiler.compile("identities", Engine.OUTLINE, Engine.alex());
        assertEquals(1, compiled.repairAttempts());
        assertEquals(2, calls.get());
        assertTrue(compiled.story().bible.characters().stream().allMatch(n -> AssetSpec.safeSubjectId(n.id())));
        assertTrue(compiled.story().bible.locations().stream().allMatch(l -> AssetSpec.safeSubjectId(l.id())));
    }

    @Test void portraitFallbackRejectsAnotherSubjectWrongKindAndInapplicableOutcome() {
        var resolver = AssetResolver.none();
        var manifest = new AssetManifest();
        String base = AssetSpec.portraitId("npc_a", "base");
        String variant = AssetSpec.portraitId("npc_a", "worried");
        put(manifest, base, AssetKind.PORTRAIT, "npc.A", AssetSpec.GENERAL);
        put(manifest, variant, AssetKind.PORTRAIT_VARIANT, "npc.A", AssetSpec.GENERAL);
        assertNull(resolver.resolvePortrait(manifest, "npc_a", "worried", base, "NONE"));
        put(manifest, base, AssetKind.CHARACTER_CARD, "npc_a", AssetSpec.GENERAL);
        put(manifest, variant, AssetKind.BACKGROUND, "npc_a", AssetSpec.GENERAL);
        assertNull(resolver.resolvePortrait(manifest, "npc_a", "worried", variant, "NONE"));
        put(manifest, base, AssetKind.PORTRAIT, "npc_a", "OUTCOME:SUCCESS");
        put(manifest, variant, AssetKind.PORTRAIT_VARIANT, "npc_a", "OUTCOME:SUCCESS");
        assertNull(resolver.resolvePortrait(manifest, "npc_a", "worried", base, "FAILURE"));
        assertEquals(variant, resolver.resolvePortrait(manifest, "npc_a", "worried", null, "SUCCESS"));
    }

    @Test void backgroundFallbackSkipsCollidingDefaultAndSelectsOnlyItsOwnUsableImage() {
        var resolver = AssetResolver.none();
        var manifest = new AssetManifest();
        String base = AssetSpec.backgroundId("loc_a", "default");
        String own = AssetSpec.backgroundId("loc_a", "night");
        put(manifest, base, AssetKind.BACKGROUND, "loc.A", AssetSpec.GENERAL);
        assertNull(resolver.resolveBackground(manifest, "loc_a", base, "NONE"));
        put(manifest, base, AssetKind.PORTRAIT, "loc_a", AssetSpec.GENERAL);
        assertNull(resolver.resolveBackground(manifest, "loc_a", base, "NONE"));
        put(manifest, base, AssetKind.BACKGROUND, "loc_a", "OUTCOME:SUCCESS");
        assertNull(resolver.resolveBackground(manifest, "loc_a", base, "FAILURE"));
        put(manifest, own, AssetKind.BACKGROUND, "loc_a", AssetSpec.GENERAL);
        assertEquals(own, resolver.resolveBackground(manifest, "loc_a", base, "FAILURE"));
    }

    private static void put(AssetManifest manifest, String id, AssetKind kind, String subject, String applicability) {
        manifest.records.put(id, new AssetRecord(new AssetSpec(id, kind, subject, "Name", "base", "visual",
                "style", "beat", 0, null, applicability, kind == AssetKind.BACKGROUND)));
    }
}
