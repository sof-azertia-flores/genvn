package com.genvn.asset;

public record ImageRequest(
        String prompt,
        int width,
        int height,
        /** png | jpeg | webp */
        String format,
        boolean transparentBackground,
        String quality
) {}
