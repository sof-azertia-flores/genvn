package com.genvn.asset;

public record ImageEditRequest(
        String prompt,
        byte[] referenceImage,
        String referenceMime,
        int width,
        int height,
        String format,
        boolean transparentBackground,
        String quality
) {}
