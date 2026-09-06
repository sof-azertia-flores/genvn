package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Facts the user themselves asserted. These are never generated, never edited and never
 * contradicted. They are re-injected verbatim into every prompt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthorCanon(String originalOutline, List<String> facts) {
    public AuthorCanon {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
