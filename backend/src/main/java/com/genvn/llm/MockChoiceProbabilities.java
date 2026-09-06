package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.narrative.Choice;
import com.genvn.speculation.ChoiceProbabilityEstimator.BranchOptions;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic offline preference estimate, based solely on choices the player actually made. */
final class MockChoiceProbabilities {
    private static final Map<String, List<String>> WORDS = Map.of(
            "investigation", List.of("调查", "搜索", "观察", "检查", "阅读", "翻找", "inspect", "search", "examine", "read", "investigate", "observe"),
            "social", List.of("询问", "交谈", "说服", "安慰", "打听", "聊天", "ask", "talk", "persuade", "speak", "negotiate"),
            "cautious", List.of("等待", "避开", "躲", "退后", "离开", "隐蔽", "谨慎", "wait", "hide", "careful", "avoid", "retreat"),
            "risky", List.of("强行", "攻击", "威胁", "冒险", "冲", "打破", "attack", "force", "rush", "break", "risk"),
            "resource", List.of("使用", "装备", "钥匙", "工具", "道具", "use", "equip", "tool", "key"));

    private MockChoiceProbabilities() {}

    @SuppressWarnings("unchecked")
    static String generate(ObjectMapper mapper, LlmRequest request) {
        List<String> history = (List<String>) request.mockContext().getOrDefault("previousChoices", List.of());
        List<Choice> current = (List<Choice>) request.mockContext().getOrDefault("currentChoices", List.of());
        List<BranchOptions> branches = (List<BranchOptions>) request.mockContext().getOrDefault("branches", List.of());
        ObjectNode response = mapper.createObjectNode();
        group(response.putObject("currentChoices"), current, history);
        ObjectNode next = response.putObject("nextChoices");
        for (BranchOptions branch : branches) group(next.putObject(branch.branchId()), branch.choices(), history);
        return response.toString();
    }

    private static void group(ObjectNode output, List<Choice> choices, List<String> history) {
        for (Choice choice : choices) {
            double weight = 1;
            String category = choice.approach() == null ? "" : choice.approach().toLowerCase(Locale.ROOT);
            List<String> words = WORDS.get(category);
            if (words != null) for (String past : history) {
                String lower = past.toLowerCase(Locale.ROOT);
                if (words.stream().anyMatch(lower::contains)) weight += 1;
            }
            output.put(choice.id(), weight);
        }
    }
}
