package com.genvn.asset;

/** A permanent image URL target, retained even when another appearance or retry becomes active. */
public record AssetPublication(String publicationId, String assetId, String fileName, String mimeType) {}
