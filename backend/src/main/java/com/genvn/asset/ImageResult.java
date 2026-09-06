package com.genvn.asset;

public record ImageResult(byte[] bytes, String mimeType, int width, int height, String model) {}
