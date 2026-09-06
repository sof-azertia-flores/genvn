package com.genvn.api;

/** The observed scene is still current, but its one accepted choice is already in progress. */
public class ChoiceResolvingException extends RuntimeException {
    public ChoiceResolvingException(String choiceId) {
        super("Choice '" + choiceId + "' is already being resolved. Wait for the session to update.");
    }
}
