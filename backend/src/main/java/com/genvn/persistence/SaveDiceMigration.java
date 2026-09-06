package com.genvn.persistence;

import com.genvn.game.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dice are cast for every checked choice the moment a scene becomes current. Saves written
 * before that change carry no dice for their current scene; at startup each one is loaded once
 * so the dice get cast and written back, instead of waiting for the first request to do it.
 */
@Component
public class SaveDiceMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SaveDiceMigration.class);

    private final GameSessionRepository repository;
    private final SessionService sessions;

    public SaveDiceMigration(GameSessionRepository repository, SessionService sessions) {
        this.repository = repository;
        this.sessions = sessions;
    }

    @Override
    public void run(ApplicationArguments args) {
        int checked = 0;
        int migrated = 0;
        for (GameSessionRepository.SessionSummary summary : repository.list()) {
            checked++;
            try {
                if (sessions.migrateSave(summary.id())) migrated++;
            } catch (RuntimeException e) {
                log.warn("Save {} could not be checked for sealed dice: {}", summary.id(), e.toString());
            }
        }
        log.info("Saves: {} checked; every current scene now carries its sealed dice{}", checked,
                migrated == checked ? "" : " (" + (checked - migrated) + " could not be loaded)");
    }
}
