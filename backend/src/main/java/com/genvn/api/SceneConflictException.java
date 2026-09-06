package com.genvn.api;

/** A choice must refer to the exact scene and state the player was shown. */
public class SceneConflictException extends RuntimeException {
    public SceneConflictException(String message) { super(message); }
}
