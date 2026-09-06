package com.genvn.support;

import java.util.ArrayList;
import java.util.List;

/** Builds provider-shaped scene JSON so tests exercise the real parse/validate path. */
public class SceneJson {

    private String locationId = "loc_interior";
    private String text = "Something happens.";
    private final List<String> ops = new ArrayList<>();
    private final List<String> choices = new ArrayList<>();
    private final List<String> assetRequests = new ArrayList<>();
    private String backgroundAssetId = null;
    private String characterJson = "";

    public static SceneJson scene(String text) {
        SceneJson s = new SceneJson();
        s.text = text;
        return s;
    }

    public SceneJson at(String locationId) {
        this.locationId = locationId;
        return this;
    }

    public SceneJson op(String json) {
        ops.add(json);
        return this;
    }

    public SceneJson addInventory(String item) {
        return op("{\"op\":\"addInventory\",\"target\":\"%s\",\"value\":\"a thing\",\"reason\":\"test\"}".formatted(item));
    }

    public SceneJson setFlag(String key, String value) {
        return op("{\"op\":\"setFlag\",\"target\":\"%s\",\"value\":\"%s\",\"reason\":\"test\"}".formatted(key, value));
    }

    /** Ask the runtime for a picture that is not planned yet (honoured only on commit). */
    public SceneJson assetRequest(String kind, String subjectId, String variant) {
        assetRequests.add("{\"kind\":\"%s\",\"subjectId\":\"%s\",\"variant\":\"%s\",\"description\":\"test\"}"
                .formatted(kind, subjectId, variant));
        return this;
    }

    /** The model's (possibly wrong) hint for which background to use. */
    public SceneJson backgroundHint(String assetId) {
        this.backgroundAssetId = assetId;
        return this;
    }

    /** Put an established character on stage, optionally with an asset hint. */
    public SceneJson withCharacter(String characterId, String name, String expression, String assetHint) {
        characterJson = "{\"characterId\":\"%s\",\"name\":\"%s\",\"expression\":\"%s\",\"position\":\"right\","
                + "\"visualDescription\":\"someone\",\"assetId\":%s}";
        characterJson = characterJson.formatted(characterId, name, expression,
                assetHint == null ? "null" : "\"" + assetHint + "\"");
        return this;
    }

    public SceneJson choice(String id, String text, String approach) {
        choices.add("{\"id\":\"%s\",\"text\":\"%s\",\"approach\":\"%s\",\"check\":null}".formatted(id, text, approach));
        return this;
    }

    public SceneJson choiceWithCheck(String id, String text, String stat, int dc) {
        choices.add(("{\"id\":\"%s\",\"text\":\"%s\",\"approach\":\"investigation\","
                + "\"check\":{\"stat\":\"%s\",\"dc\":%d,\"description\":\"a test check\"}}")
                .formatted(id, text, stat, dc));
        return this;
    }

    public String build() {
        String bg = backgroundAssetId == null ? "null" : "\"" + backgroundAssetId + "\"";
        return ("""
                {
                  "location": {"id":"%s","name":"Somewhere","visualDescription":"a room","backgroundPrompt":"a room","backgroundAssetId":%s},
                  "characters": [%s],
                  "blocks": [{"type":"narration","text":"%s"}],
                  "choices": [%s],
                  "proposedStateDelta": {"ops":[%s]},
                  "storyProgressNote": "test",
                  "assetRequests": [%s]
                }
                """).formatted(locationId, bg, characterJson, text, String.join(",", choices), String.join(",", ops),
                String.join(",", assetRequests));
    }
}
