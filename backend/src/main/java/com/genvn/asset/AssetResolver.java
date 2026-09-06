package com.genvn.asset;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns "this scene is at loc_interior with npc_witness looking worried" into validated asset
 * ids, and renders the compact manifest the scene prompt sees.
 *
 * The model may hint an id; the runtime decides. A hint is honoured only if it names a real
 * asset for the SAME subject that applies to this outcome. Otherwise resolution is semantic:
 * location -> its default background; character + expression -> that variant, else the base
 * portrait. A picture tied to one outcome is never handed to the other.
 */
@Component
public class AssetResolver {

    private final AssetLookup lookup;

    public AssetResolver(AssetLookup lookup) {
        this.lookup = lookup == null ? AssetLookup.NONE : lookup;
    }

    public static AssetResolver none() {
        return new AssetResolver(AssetLookup.NONE);
    }

    public Optional<AssetManifest> snapshot(String sessionId) {
        return lookup.snapshot(sessionId);
    }

    public String resolveBackground(AssetManifest m, String locationId, String hint, String outcome) {
        if (m == null || locationId == null) return null;
        AssetRecord hinted = m.get(hint);
        if (matches(hinted, locationId, outcome, false)) {
            return hinted.spec.assetId();
        }
        AssetRecord dflt = m.get(AssetSpec.backgroundId(locationId, AssetSpec.DEFAULT_VARIANT));
        if (matches(dflt, locationId, outcome, false)) return dflt.spec.assetId();
        AssetRecord best = null;
        for (AssetRecord r : m.records.values()) {
            if (!matches(r, locationId, outcome, false)) continue;
            if (best == null || (r.status == AssetStatus.READY && best.status != AssetStatus.READY)) best = r;
        }
        return best == null ? null : best.spec.assetId();
    }

    public String resolvePortrait(AssetManifest m, String characterId, String expression, String hint, String outcome) {
        if (m == null || characterId == null) return null;
        AssetRecord hinted = m.get(hint);
        if (matches(hinted, characterId, outcome, true)) {
            return hinted.spec.assetId();
        }
        if (expression != null && !expression.isBlank() && !"neutral".equalsIgnoreCase(expression)) {
            AssetRecord variant = m.get(AssetSpec.portraitId(characterId, expression));
            if (matches(variant, characterId, outcome, true)) return variant.spec.assetId();
        }
        AssetRecord base = m.get(AssetSpec.portraitId(characterId, AssetSpec.BASE_VARIANT));
        if (matches(base, characterId, outcome, true)) return base.spec.assetId();
        return null;
    }

    private static boolean matches(AssetRecord record, String subjectId, String outcome, boolean portrait) {
        if (record == null || record.spec == null || record.spec.kind() == null) return false;
        AssetSpec spec = record.spec;
        return (portrait ? spec.kind().transparentSprite() : spec.kind() == AssetKind.BACKGROUND)
                && subjectId.equals(spec.subjectId()) && spec.appliesTo(outcome);
    }

    /** Compact, relevant, never the whole library and never image bytes. */
    public String renderForPrompt(AssetManifest m) {
        if (m == null || m.records.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (AssetRecord r : m.records.values()) {
            // Cards are a stable UI identity, never a stage sprite chosen by the prose model.
            if (r.spec.kind() == AssetKind.CHARACTER_CARD) continue;
            if (shown >= 30) {
                lines.add("- ... and " + (m.records.size() - shown) + " more planned");
                break;
            }
            String kind = switch (r.spec.kind()) {
                case BACKGROUND -> "background";
                case PORTRAIT -> "portrait";
                case PORTRAIT_VARIANT -> "portrait variant (" + r.spec.variant() + ")";
                case CHARACTER_CARD -> "character card";
            };
            String applies = AssetSpec.GENERAL.equals(r.spec.applicability()) ? "" : " · only for " + r.spec.applicability();
            lines.add("- " + r.spec.assetId() + "  " + kind + " · " + r.spec.subjectName()
                    + " [" + r.spec.subjectId() + "] · " + r.status.name().toLowerCase() + applies);
            shown++;
        }
        return """
                ### VISUAL ASSETS planned for this story (reference by assetId; the runtime validates every reference)
                %s
                Prefer these pictures. Keep each location's layout and each character's outfit, hair and age exactly as
                established so the pictures stay reusable; do not reinvent a room or a wardrobe per scene. A picture that is
                still generating is fine to reference. Only add an assetRequests entry when no listed picture can show what
                the scene needs, and never bend the story to what is already drawn.
                """.formatted(String.join("\n", lines));
    }
}
