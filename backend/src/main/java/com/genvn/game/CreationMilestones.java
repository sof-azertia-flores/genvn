package com.genvn.game;

import com.genvn.llm.GenerationProgress;

/** Fixed preparation milestones. A stalled provider cannot advance this observer by time alone. */
public final class CreationMilestones {
    private CreationMilestones() {}

    public static GenerationProgress model(SessionService.CreationProgress progress, boolean opening) {
        int[] highWater = {opening ? 60 : 6};
        return (stage, attempt, received) -> {
            String subject = opening ? "开场正文" : "故事框架";
            int value;
            String message;
            String label;
            switch (stage) {
                case REQUEST_STARTED -> {
                    value = opening ? 62 : 8;
                    label = "等待" + subject;
                    message = subject + "请求已准备，正在等待文字模型回应。";
                }
                case RESPONSE_STARTED -> {
                    value = opening ? 65 : 12;
                    label = "接收" + subject;
                    message = "文字服务已接受请求，等待" + subject + "的内容流。";
                }
                case CONTENT_RECEIVED -> {
                    int step = received >= 8192 ? 3 : received >= 4096 ? 2 : received >= 2048 ? 1 : 0;
                    value = (opening ? 67 : 16) + step * (opening ? 3 : 4);
                    label = "接收" + subject;
                    message = step == 0 ? subject + "已开始返回；完整接收后才会检查和采用。"
                            : "已接收至少 " + received + " 个字符的" + subject + "，继续等待完整结果；内容尚未校验。";
                }
                case RESPONSE_RECEIVED -> {
                    value = opening ? 79 : 31;
                    label = subject + "已接收";
                    message = subject + "已完整返回，开始检查数据结构。";
                }
                case JSON_EXTRACTED -> {
                    value = opening ? 81 : 33;
                    label = "检查" + subject + "结构";
                    message = "已提取" + subject + "的结构化数据。";
                }
                case SCHEMA_PARSED -> {
                    value = opening ? 83 : 35;
                    label = "核对" + subject + "规则";
                    message = subject + "的数据结构已读入，正在核对故事与游戏规则。";
                }
                case VALIDATED -> {
                    value = opening ? 85 : 38;
                    label = subject + "校验完成";
                    message = subject + "已通过结构与规则校验。";
                }
                case REPAIR_REQUESTED -> {
                    value = highWater[0];
                    label = "重新检查" + subject;
                    message = subject + "的上一轮未能完成或未通过校验，正在进行第 " + attempt + " 次尝试。";
                }
                default -> throw new IllegalStateException("Unknown generation milestone");
            }
            highWater[0] = Math.max(highWater[0], value);
            progress.report(label, highWater[0], message);
        };
    }
}
