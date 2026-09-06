package com.genvn.llm;

public record LlmResponse(String text, String model, long durationMillis) {}
