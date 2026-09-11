package com.genvn.persistence;

import com.genvn.game.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Brings every save up to date at startup, once, instead of waiting for the first request.
 *
 * Two migrations run here. Dice are cast for every checked choice the moment a scene becomes
 * current, so a save written before that change gets its dice cast and written back. And a save
 * still stored as a single JSON file is re-saved into the directory layout that holds the scene
 * tree. Both are idempotent: a save already in the current shape is left alone.
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
        int relaid = 0;
        for (GameSessionRepository.SessionSummary summary : repository.list()) {
            checked++;
            try {
                if (sessions.migrateSave(summary.id())) migrated++;
                // Casting dice only saves when something changed, so the layout is moved here.
                if (repository.hasLegacyLayout(summary.id())) {
                    repository.find(summary.id()).ifPresent(repository::save);
                    if (!repository.hasLegacyLayout(summary.id())) relaid++;
                }
            } catch (RuntimeException e) {
                log.warn("Save {} could not be brought up to date: {}", summary.id(), e.toString());
            }
        }
        log.info("Saves: {} checked; every current scene now carries its sealed dice{}{}", checked,
                migrated == checked ? "" : " (" + (checked - migrated) + " could not be loaded)",
                relaid == 0 ? "" : "; " + relaid + " moved to the directory save format");
    }
}
