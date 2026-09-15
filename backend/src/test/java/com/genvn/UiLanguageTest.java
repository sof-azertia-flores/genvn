package com.genvn;

import com.genvn.config.UiLanguage;
import com.genvn.prompt.Prompts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiLanguageTest {
    @Test void normalizesAliasesAndDefaultsToChinese() {
        assertEquals("zh", UiLanguage.normalize(null));
        assertEquals("zh", UiLanguage.normalize("zh-CN"));
        assertEquals("en", UiLanguage.normalize("en-US"));
        assertEquals("en", UiLanguage.normalize("English"));
        assertTrue(Prompts.compilerSystem("en").contains("in English"));
        assertTrue(Prompts.sceneSystem("zh").contains("Simplified Chinese"));
        assertFalse(Prompts.sceneSystem("en").contains("same language as the story bible"));
    }
}
