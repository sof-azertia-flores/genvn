package com.genvn.api;

/**
 * The framework of this save is being rewritten right now. Every other mutation waits: the story
 * a choice would be resolved against is about to stop existing.
 */
public class RestructureInProgressException extends RuntimeException {
    public RestructureInProgressException() {
        super("这个存档正在重塑剧情，请等它完成再继续。");
    }
}
