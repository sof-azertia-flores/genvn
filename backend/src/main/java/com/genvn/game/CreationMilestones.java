package com.genvn.game;

import com.genvn.config.UiLanguage;
import com.genvn.llm.GenerationProgress;

/** Fixed preparation milestones. A stalled provider cannot advance this observer by time alone. */
public final class CreationMilestones {
    private CreationMilestones() {}

    // Where each milestone sits inside its window. One ladder, so a new window (a restructure,
    // say) reports the same shape of progress as story compilation does, only over its own range.
    private static final double REQUEST = 0.0625;
    private static final double RESPONSE_STARTED = 0.1875;
    private static final double CONTENT_BASE = 0.3125;
    private static final double CONTENT_STEP = 0.125;
    private static final double RESPONSE_RECEIVED = 0.78125;
    private static final double JSON_EXTRACTED = 0.84375;
    private static final double SCHEMA_PARSED = 0.90625;

    public static GenerationProgress model(SessionService.CreationProgress progress, boolean opening) {
        return model(progress, opening, UiLanguage.ZH);
    }

    public static GenerationProgress model(SessionService.CreationProgress progress, boolean opening, String language) {
        return opening
                ? model(progress, 60, 85, "开场正文", "opening scene", language)
                : model(progress, 6, 38, "故事框架", "story frame", language);
    }

    /**
     * One model call's worth of milestones, mapped onto {@code [floor, ceiling]}.
     *
     * A job made of several model calls gives each call its own window, so the bar moves through
     * the job rather than resetting per call. {@code subject} names what is being written, in both
     * languages, because every message mentions it.
     */
    public static GenerationProgress model(SessionService.CreationProgress progress, int floor, int ceiling,
                                           String subjectZh, String subjectEn, String language) {
        int span = Math.max(0, ceiling - floor);
        int[] highWater = {floor};
        return (stage, attempt, received) -> {
            int value;
            String message;
            String label;
            switch (stage) {
                case REQUEST_STARTED -> {
                    value = at(floor, span, REQUEST);
                    label = UiLanguage.text(language, "等待" + subjectZh, "Waiting for the " + subjectEn);
                    message = UiLanguage.text(language,
                            subjectZh + "请求已准备，正在等待文字模型回应。",
                            "The " + subjectEn + " request is ready; waiting for the language model.");
                }
                case RESPONSE_STARTED -> {
                    value = at(floor, span, RESPONSE_STARTED);
                    label = UiLanguage.text(language, "接收" + subjectZh, "Receiving the " + subjectEn);
                    message = UiLanguage.text(language,
                            "文字服务已接受请求，等待" + subjectZh + "的内容流。",
                            "The language service accepted the request; waiting for the " + subjectEn + " stream.");
                }
                case CONTENT_RECEIVED -> {
                    int step = received >= 8192 ? 3 : received >= 4096 ? 2 : received >= 2048 ? 1 : 0;
                    value = at(floor, span, CONTENT_BASE) + step * scaled(span, CONTENT_STEP);
                    label = UiLanguage.text(language, "接收" + subjectZh, "Receiving the " + subjectEn);
                    message = step == 0
                            ? UiLanguage.text(language,
                                    subjectZh + "已开始返回；完整接收后才会检查和采用。",
                                    "The " + subjectEn + " has started arriving; it is checked only after the full reply.")
                            : UiLanguage.text(language,
                                    "已接收至少 " + received + " 个字符的" + subjectZh + "，继续等待完整结果；内容尚未校验。",
                                    "At least " + received + " characters of the " + subjectEn
                                            + " have arrived; still waiting for the full result, not yet checked.");
                }
                case RESPONSE_RECEIVED -> {
                    value = at(floor, span, RESPONSE_RECEIVED);
                    label = UiLanguage.text(language, subjectZh + "已接收", "Received the " + subjectEn);
                    message = UiLanguage.text(language,
                            subjectZh + "已完整返回，开始检查数据结构。",
                            "The " + subjectEn + " arrived in full; checking its structure.");
                }
                case JSON_EXTRACTED -> {
                    value = at(floor, span, JSON_EXTRACTED);
                    label = UiLanguage.text(language, "检查" + subjectZh + "结构", "Checking " + subjectEn + " structure");
                    message = UiLanguage.text(language,
                            "已提取" + subjectZh + "的结构化数据。",
                            "Structured data for the " + subjectEn + " has been extracted.");
                }
                case SCHEMA_PARSED -> {
                    value = at(floor, span, SCHEMA_PARSED);
                    label = UiLanguage.text(language, "核对" + subjectZh + "规则", "Checking " + subjectEn + " rules");
                    message = UiLanguage.text(language,
                            subjectZh + "的数据结构已读入，正在核对故事与游戏规则。",
                            "The " + subjectEn + " structure is in; checking story and game rules.");
                }
                case VALIDATED -> {
                    value = ceiling;
                    label = UiLanguage.text(language, subjectZh + "校验完成", subjectEn + " validated");
                    message = UiLanguage.text(language,
                            subjectZh + "已通过结构与规则校验。",
                            "The " + subjectEn + " passed structure and rule checks.");
                }
                case REPAIR_REQUESTED -> {
                    value = highWater[0];
                    label = UiLanguage.text(language, "重新检查" + subjectZh, "Rechecking the " + subjectEn);
                    message = UiLanguage.text(language,
                            subjectZh + "的上一轮未能完成或未通过校验，正在进行第 " + attempt + " 次尝试。",
                            "The previous " + subjectEn + " round did not finish or did not pass; attempt " + attempt + ".");
                }
                default -> throw new IllegalStateException("Unknown generation milestone");
            }
            highWater[0] = Math.max(highWater[0], Math.min(ceiling, value));
            progress.report(label, highWater[0], message);
        };
    }

    private static int at(int floor, int span, double fraction) {
        return floor + scaled(span, fraction);
    }

    private static int scaled(int span, double fraction) {
        return (int) Math.round(span * fraction);
    }
}
