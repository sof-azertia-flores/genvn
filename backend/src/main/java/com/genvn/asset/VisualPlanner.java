package com.genvn.asset;

import com.genvn.config.ImageProperties;
import com.genvn.game.GameState;
import com.genvn.game.PlayerCharacter;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;
import com.genvn.story.NpcProfile;
import com.genvn.story.StoryBeat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the visual asset plan from the ALREADY VALIDATED Story Bible and Spine -- no extra
 * model call, so the opening never waits on it. Deterministic: the same story always plans the
 * same ids, which is what lets branches, beats and restarts share one picture.
 *
 * Scale is bounded by config, never by invention: it only plans locations and characters the
 * compiler established, in bible order, opening location first.
 */
@Component
public class VisualPlanner {

    public record Plan(String style, String styleKey, List<AssetSpec> specs) {}

    private static final int VARIANT_PRIORITY = 100;

    private final ImageProperties props;

    public VisualPlanner(ImageProperties props) {
        this.props = props;
    }

    public Plan plan(CompiledStory story, GameState state, String existingStyle) {
        String style = existingStyle != null && !existingStyle.isBlank() ? existingStyle : styleFor(story);
        String styleKey = styleKey(style);
        List<AssetSpec> specs = new ArrayList<>();
        List<StoryBeat> beats = story.spine.beats();

        // The protagonist is always planned, even when NPC planning is disabled or capped.
        // These four essentials receive first-batch priority and share a single base reference.
        if (story.playerVisual != null) {
            NpcProfile player = story.playerVisual;
            String beatId = beats.isEmpty() ? null : beats.get(0).id();
            String baseId = AssetSpec.portraitId(PlayerCharacter.ID, AssetSpec.BASE_VARIANT);
            specs.add(new AssetSpec(baseId, AssetKind.PORTRAIT, player.id(), player.name(), AssetSpec.BASE_VARIANT,
                    portraitPrompt(style, player, "neutral"), styleKey, beatId, -4, null, AssetSpec.GENERAL, false));
            specs.add(characterCard(player, style, styleKey, beatId).withPriority(-3));
            int priority = -2;
            for (String pose : List.of("talking", "action")) {
                specs.add(new AssetSpec(AssetSpec.portraitId(player.id(), pose), AssetKind.PORTRAIT_VARIANT,
                        player.id(), player.name(), pose, variantPrompt(style, player, pose), styleKey, beatId,
                        priority++, baseId, AssetSpec.GENERAL, false));
            }
        }

        // Backgrounds: the opening location first, then the rest in bible order.
        Set<String> locationOrder = new LinkedHashSet<>();
        if (state != null && state.currentLocationId != null) locationOrder.add(state.currentLocationId);
        for (LocationProfile loc : story.bible.locations()) locationOrder.add(loc.id());
        int i = 0;
        for (String locId : locationOrder) {
            if (i >= Math.max(0, props.getPlan().getLocations())) break;
            LocationProfile loc = story.bible.location(locId);
            if (loc == null) continue;
            String beatId = beats.isEmpty() ? null : beats.get(Math.min(i, beats.size() - 1)).id();
            specs.add(new AssetSpec(
                    AssetSpec.backgroundId(loc.id(), AssetSpec.DEFAULT_VARIANT),
                    AssetKind.BACKGROUND, loc.id(), loc.name(), AssetSpec.DEFAULT_VARIANT,
                    backgroundPrompt(style, loc), styleKey, beatId, i, null, AssetSpec.GENERAL, true));
            i++;
        }

        // Base sprites and one reusable card come before optional expression/pose variants.
        int c = 0;
        List<String> expressions = props.getPlan().expressionList();
        for (NpcProfile npc : story.bible.characters()) {
            if (c >= Math.max(0, props.getPlan().getCharacters())) break;
            String baseId = AssetSpec.portraitId(npc.id(), AssetSpec.BASE_VARIANT);
            String beatId = beats.isEmpty() ? null : beats.get(Math.min(c, beats.size() - 1)).id();
            specs.add(new AssetSpec(baseId, AssetKind.PORTRAIT, npc.id(), npc.name(), AssetSpec.BASE_VARIANT,
                    portraitPrompt(style, npc, "neutral"), styleKey, beatId, c, null, AssetSpec.GENERAL, false));
            specs.add(characterCard(npc, style, styleKey, beatId).withPriority(c + 1));
            int v = 0;
            for (String expression : expressions) {
                specs.add(new AssetSpec(AssetSpec.portraitId(npc.id(), expression), AssetKind.PORTRAIT_VARIANT,
                        npc.id(), npc.name(), expression, variantPrompt(style, npc, expression), styleKey, beatId,
                        VARIANT_PRIORITY + c * 2 + v, baseId, AssetSpec.GENERAL, false));
                v++;
            }
            c++;
        }
        // Unassigned appearances carry no name, role, personality or future plot.
        // They are never part of the first batch and only occupy spare image workers.
        int reserve = 0;
        for (var visual : story.preparedVisuals) {
            if (reserve >= com.genvn.story.PreparedVisual.MAX_PREPARED) break;
            if (story.visualCharacter(visual.id()) != null) continue;
            String label = "预备形象 " + (++reserve);
            String baseId = AssetSpec.portraitId(visual.id(), AssetSpec.BASE_VARIANT);
            specs.add(new AssetSpec(baseId, AssetKind.PORTRAIT, visual.id(), label, AssetSpec.BASE_VARIANT,
                    "Transparent character sprite for a visual novel. Appearance only: " + visual.visualDescription()
                            + ". One isolated person, head to upper thighs, all hair and arms in frame; genuine alpha transparency. "
                            + "No scenery, border, text or invented name, profession, personality or narrative symbolism. " + style,
                    styleKey, null, AssetSpec.IDLE_PRIORITY + reserve * 2, null, AssetSpec.GENERAL, false));
            specs.add(new AssetSpec(AssetSpec.characterCardId(visual.id()), AssetKind.CHARACTER_CARD, visual.id(), label,
                    AssetSpec.DEFAULT_VARIANT,
                    "Create a permanent character card from this transparent appearance reference. Preserve the exact face, "
                            + "hair, clothing and physical features. Add a delicate border and a simple opaque abstract background "
                            + "whose colors harmonize with the outfit. This is only an unassigned visual design: no name, lettering, "
                            + "occupation, personality, relationships, story, logos or narrative props. " + style,
                    styleKey, null, AssetSpec.IDLE_PRIORITY + reserve * 2 + 1, baseId, AssetSpec.GENERAL, false));
        }
        return new Plan(style, styleKey, specs);
    }

    /** A spec for a picture the scene generator asked for, validated against the bible. */
    public AssetSpec fromRequest(CompiledStory story, AssetRequest request, String style, String styleKey, String beatId) {
        if (request == null || request.kind() == null || request.subjectId() == null) return null;
        String variant = AssetSpec.slug(request.variant() == null || request.variant().isBlank()
                ? AssetSpec.DEFAULT_VARIANT : request.variant());
        String kind = request.kind().trim().toLowerCase();
        String extra = request.description() == null ? "" : " " + request.description().trim();
        if (kind.startsWith("background")) {
            LocationProfile loc = story.bible.location(request.subjectId());
            if (loc == null) return null;
            return new AssetSpec(AssetSpec.backgroundId(loc.id(), variant), AssetKind.BACKGROUND, loc.id(), loc.name(),
                    variant, backgroundPrompt(style, loc) + " Visual variant: " + variant + "." + extra,
                    styleKey, beatId, 1, null, AssetSpec.GENERAL, true);
        }
        if (kind.startsWith("portrait")) {
            NpcProfile npc = story.visualCharacter(request.subjectId());
            if (npc == null) return null;
            String baseId = AssetSpec.portraitId(npc.id(), AssetSpec.BASE_VARIANT);
            boolean isBase = AssetSpec.BASE_VARIANT.equals(variant);
            return new AssetSpec(AssetSpec.portraitId(npc.id(), variant),
                    isBase ? AssetKind.PORTRAIT : AssetKind.PORTRAIT_VARIANT, npc.id(), npc.name(), variant,
                    (isBase ? portraitPrompt(style, npc, "neutral") : variantPrompt(style, npc, variant)) + extra,
                    styleKey, beatId, isBase ? 1 : VARIANT_PRIORITY, isBase ? null : baseId, AssetSpec.GENERAL, false);
        }
        return null;
    }

    // ------------------------------------------------------------------ prompts

    public AssetSpec characterCard(NpcProfile npc, String style, String styleKey, String beatId) {
        return new AssetSpec(AssetSpec.characterCardId(npc.id()), AssetKind.CHARACTER_CARD,
                npc.id(), npc.name(), AssetSpec.DEFAULT_VARIANT,
                "Create the permanent character card from the transparent character reference. Preserve the person's "
                        + "face, age, outfit, hairstyle and recognisable identity exactly. Use a composed signature pose. "
                        + "Add a restrained decorative border and a fully painted opaque background with motifs suited to "
                        + "this character's role and personality: " + nz(npc.description(), npc.visualDescription())
                        + "; " + nz(npc.personality(), npc.relationshipToPlayer())
                        + ". This card is reused across every expression and scene. No words, lettering or watermark. " + style,
                styleKey, beatId, 1, AssetSpec.portraitId(npc.id(), AssetSpec.BASE_VARIANT), AssetSpec.GENERAL, false);
    }

    public static String styleFor(CompiledStory story) {
        if (story.artStyle != null && !story.artStyle.isBlank()) {
            return "Visual direction supplied for the entire work: " + story.artStyle.trim()
                    + ". Preserve this medium, palette, rendering and character design across every illustration. "
                    + "The asset's required transparency/composition takes precedence over any background instructions. "
                    + "No text, lettering, captions, logos or watermark.";
        }
        String tone = story.bible.tone() == null ? "" : story.bible.tone().trim();
        return "Visual novel illustration, painterly digital art, cinematic lighting, consistent character design, "
                + "muted palette with warm accents" + (tone.isEmpty() ? "" : "; mood: " + tone) + ". No text, no watermark.";
    }

    public static String styleKey(String style) {
        return Integer.toHexString(style == null ? 0 : style.hashCode());
    }

    static String backgroundPrompt(String style, LocationProfile loc) {
        return "Stage background for a visual novel. " + nz(loc.visualDescription(), loc.description())
                + " Wide establishing shot, no people, leave the lower third uncluttered for dialogue. " + style;
    }

    static String portraitPrompt(String style, NpcProfile npc, String expression) {
        return "Transparent character sprite for a visual novel, " + expression + " expression. " + nz(npc.visualDescription(), npc.description())
                + " Three-quarter view, from head to upper thighs, facing slightly left, centred, arms and hair fully inside "
                + "the frame. Isolated person on a genuinely transparent alpha background, no scenery, no border, "
                + "no shadow rectangle and no painted checkerboard. Consistent outfit and hairstyle. " + style;
    }

    /**
     * Deliberately minimal and positive. The reference picture already carries the art direction
     * and the character's appearance, and on at least one gpt-image route the alpha channel is
     * decided from the prompt rather than enforced by the API parameter: bisected live on
     * 2026-09-05, prompts of this shape came back RGBA four times out of four, a longer sentence
     * with negations ("no scenery, no background, ...") was a coin flip, and anything carrying the
     * art-direction text or an appended output block came back opaque RGB every time.
     */
    static String variantPrompt(String style, NpcProfile npc, String expression) {
        return "Same character as the reference; keep the face, outfit, hairstyle and art style; "
                + expression + " expression and a natural " + expression + " pose; transparent background.";
    }

    private static String nz(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null ? "" : b.trim();
    }
}
