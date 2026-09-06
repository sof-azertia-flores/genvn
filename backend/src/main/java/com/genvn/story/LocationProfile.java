package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationProfile(
        String id,
        String name,
        String description,
        String visualDescription
) {}
