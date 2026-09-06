package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.game.ContinuityEntry;
import com.genvn.game.GameState;
import com.genvn.narrative.Choice;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;

import static com.genvn.llm.MockStoryTemplates.*;

/**
 * Writes one mock scene as provider-shaped JSON.
 *
 * Templates, but state-aware ones: the beat, the approach the player actually took, and the
 * SUCCESS/FAILURE the engine already decided all select the text. Failures are written
 * fail-forward, exactly as the real prompt demands.
 *
 * A scene narrates the beat it is closing, but offers the choices of the beat it is opening.
 */
class MockSceneWriter {

    private final ObjectMapper mapper;
    private final boolean zh;
    private final String place;
    private final String object;
    private final String witness;
    private final String absent;
    private final GameState state;
    private final CompiledStory story;
    private final Choice choice;
    private final String outcome;
    private final int narrationBeat;
    private final int choiceBeat;
    private final boolean completesBeat;
    private final boolean ending;
    private final int seed;
    private final int sceneIndex;

    MockSceneWriter(ObjectMapper mapper, boolean zh, String place, String object, String witness, String absent,
                    GameState state, CompiledStory story, Choice choice, String outcome,
                    int narrationBeat, int choiceBeat, boolean completesBeat, boolean ending,
                    int seed, int sceneIndex) {
        this.mapper = mapper;
        this.zh = zh;
        this.place = place;
        this.object = object;
        this.witness = witness;
        this.absent = absent;
        this.state = state;
        this.story = story;
        this.choice = choice;
        this.outcome = outcome;
        this.narrationBeat = narrationBeat;
        this.choiceBeat = choiceBeat;
        this.completesBeat = completesBeat;
        this.ending = ending;
        this.seed = seed;
        this.sceneIndex = sceneIndex;
    }

    String write() {
        ObjectNode root = mapper.createObjectNode();
        putLocation(root, ending ? "loc_interior" : locationFor(choiceBeat));

        ArrayNode characters = root.putArray("characters");
        boolean witnessOnStage = !ending && (choice == null || isSocial());
        if (witnessOnStage) {
            ObjectNode c = characters.addObject();
            c.put("characterId", "npc_witness");
            c.put("name", witness);
            c.put("expression", isFailure() ? "worried" : (choice == null ? "suspicious" : "sad"));
            c.put("position", "right");
            c.put("visualDescription", zh
                    ? "上了年纪的人，洗旧的外套，站在光线边缘"
                    : "an older person in a washed-out coat at the edge of the light");
        }

        if (choice != null) {
            ObjectNode c = characters.addObject();
            c.put("characterId", "player");
            c.put("name", state.player.name);
            c.put("expression", choice.playerExpression());
            c.put("position", "right");
            c.put("visualDescription", state.player.visualDescription);
        }
        ArrayNode blocks = root.putArray("blocks");
        if (choice != null && "dialogue".equals(choice.actionKind())) {
            dialogue(blocks, "player", state.player.name, choice.text(), choice.playerExpression());
        }
        if (ending) {
            writeEnding(blocks);
        } else {
            if (choice == null) {
                // Opening: set the place first, let the witness speak into it, then hand over.
                narration(blocks, ambience(choiceBeat));
                if (witnessOnStage) {
                    dialogue(blocks, "npc_witness", witness, witnessLine(), "suspicious");
                }
                narration(blocks, zh
                        ? "你还站在门外。往前一步，这件事就归你了。"
                        : "You are still outside. One step forward and this becomes yours.");
            } else {
                narration(blocks, consequence());
                if (witnessOnStage) {
                    dialogue(blocks, "npc_witness", witness, witnessLine(), isFailure() ? "worried" : "sad");
                }
                narration(blocks, ambience(choiceBeat));
            }
        }

        ArrayNode choices = root.putArray("choices");
        if (!ending) writeChoices(choices);

        root.set("proposedStateDelta", buildDelta());
        root.put("storyProgressNote", ending
                ? (zh ? "主线已走完，等待续写下一个 Arc。" : "Main spine complete; awaiting the next arc.")
                : (zh ? "第 %d 幕，节拍 %s%s".formatted(sceneIndex + 1, String.valueOf(state.currentBeatId),
                            completesBeat ? "（本幕结束该节拍）" : "")
                      : "Scene %d, beat %s%s".formatted(sceneIndex + 1, String.valueOf(state.currentBeatId),
                            completesBeat ? " (closes this beat)" : "")));
        return root.toString();
    }

    // ------------------------------------------------------------------ prose

    private String consequence() {
        if (isSocial()) return socialConsequence();
        if (isCautious()) return cautiousConsequence();
        if (isNone()) {
            return pick(zh ? new String[]{
                            "你按自己想的做了。这一次没有什么阻拦你——这本身就让人不太舒服。",
                            "事情按你预想的顺序发生了。安静得像是这栋房子在让路。"}
                        : new String[]{
                            "You do the thing you said you would. Nothing stops you, which is its own kind of wrong.",
                            "It happens in the order you expected — quietly, as though the building stepped aside."});
        }
        if (isContinuation()) return continuationConsequence();
        boolean ok = isSuccess();
        return switch (narrationBeat) {
            case BEAT_ARRIVAL -> ok
                    ? pick(zh ? new String[]{
                            "你绕着%s走了整整一圈才停下。后门上的锁是新的——比这栋房子上任何一样东西都新。".formatted(place),
                            "你把整面外墙看了一遍。一楼的窗户都从里面闩上了，只有最东边那扇没有。"}
                        : new String[]{
                            "You walk the whole way around %s before you stop. The lock on the back door is new — newer than anything else on the building.".formatted(place),
                            "You read the whole outside wall. Every ground-floor window is barred from the inside except the one at the east end."})
                    : pick(zh ? new String[]{
                            "长草里有东西绊了你一下，你踉跄着按住墙。手掌下的木头是软的，像浸过水。你收回手，指尖是湿的。",
                            "你在屋角踩空了半级石阶，膝盖磕在台边。等你直起身，天已经比刚才暗了一整档。"}
                        : new String[]{
                            "Something in the grass catches your foot and you catch yourself on the wall. The wood gives under your palm like something waterlogged. You pull back. Your fingertips are wet.",
                            "You misjudge a half step at the corner of the house and your knee goes into the stone edge. By the time you are upright the light has dropped a full stop."});
            case BEAT_EXPLORE -> ok
                    ? pick(zh ? new String[]{
                            "第三个抽屉是空的，但它比旁边两个浅了一寸。你把手伸进去，指尖碰到一个用布裹着的硬东西。",
                            "你把白布一张张掀开。最后一张下面不是家具，是一只已经打开过的木箱，里面垫着报纸。"}
                        : new String[]{
                            "The third drawer is empty, but it sits an inch shallower than the two beside it. You reach in and your fingers find something hard, wrapped in cloth.",
                            "You take the dust sheets off one at a time. Under the last one is not furniture but a wooden crate, already opened, packed with newspaper."})
                    : pick(zh ? new String[]{
                            "抽屉整个滑了出来，连里面的东西一起砸在地上。在空房子里，这个声音响得不讲道理。你站着数了十秒。楼上没有回应——但那种安静也没有回来。",
                            "柜门的合页锈死了，你使了劲，整扇门连着一块木片脱了下来。你抱住它没让它落地，可手电滚进了床底，光从下面照上来，把整个房间的影子翻了个个儿。"}
                        : new String[]{
                            "The drawer comes all the way out and dumps itself on the floor. In an empty house the noise is unreasonable. You stand still and count to ten. Nothing answers from upstairs — but the old quiet does not come back either.",
                            "The cabinet hinge is rusted solid; you force it and the whole door comes away with a strip of the frame. You catch it before it lands, but the torch rolls under the bed and lights the room from below, turning every shadow the wrong way up."});
            case BEAT_EVIDENCE -> ok
                    ? pick(zh ? new String[]{
                            "书架和墙之间有一指宽的缝。你把手电横过去，看见一叠纸卡在踢脚线上方——不是掉进去的，是被塞进去的。",
                            "你把每一本书都翻了一遍书脊。第九本的封底是硬的：里面夹着对折过四次的一张纸，折痕已经发白。"}
                        : new String[]{
                            "There is a finger's width between the shelf and the wall. You turn the torch sideways and see paper wedged above the skirting board — not fallen in. Put in.",
                            "You go along every spine on the shelf. The ninth book has a stiff back board: inside is a sheet folded four times, the creases gone white with age."})
                    : pick(zh ? new String[]{
                            "书架比看上去重得多。你只挪开半尺，一整排书就朝你倒下来。你抬手去挡，手背蹭在柜角上。纸确实在那里，可现在全散在地上，顺序没了。",
                            "你太急着抽那张纸，它从中间裂开。你手上留着上半页，下半页还卡在缝里，够不到。上半页只写了半句话。"}
                        : new String[]{
                            "The shelf is heavier than it looks. You get it half a foot out before a whole row of books comes down on you. You get an arm up; the back of your hand catches the cabinet edge. The papers are there — but they are on the floor now, and the order is gone.",
                            "You pull at the sheet too fast and it tears across the middle. You are holding the top half; the bottom half is still in the gap, out of reach. The top half stops mid-sentence."});
            case BEAT_CONFRONT -> ok
                    ? pick(zh ? new String[]{
                            "你在楼梯口把呼吸放到最慢。声音开始了：三步，停，两步，停。你听出来了——那不是在走。那是有人站在原地，把重量从一只脚换到另一只脚。",
                            "你把耳朵贴在楼梯的立板上。声音是从上面传下来的，但不是踩在地板上——它先经过墙，再进到木头里。上面那个东西贴着墙在动。"}
                        : new String[]{
                            "You slow your breathing at the foot of the stairs. It begins: three steps, a pause, two steps, a pause. And you hear it — that is not walking. That is someone standing still, shifting their weight from one foot to the other.",
                            "You put your ear against the riser. The sound comes down from above, but not through the floorboards — it goes into the wall first, then into the wood. Whatever is up there is moving along the wall."})
                    : pick(zh ? new String[]{
                            "你上到第七级，木头在脚下叫了一声。声音停了。整栋房子停了。然后它又开始——比刚才快，而且这一次，是朝着楼梯来的。",
                            "你等到了午夜，然后什么也没有发生。你正要站起来，声音在你正上方响了一下——只有一下，近得像是隔着一层板。"}
                        : new String[]{
                            "You are seven steps up when the wood cries out under you. The sound stops. The whole house stops. Then it starts again — faster than before — and this time it is coming toward the stairs.",
                            "You wait out midnight and nothing happens at all. You are getting to your feet when it sounds once, directly above you — once only, close enough to be through a single board."});
            default -> ok
                    ? pick(zh ? new String[]{
                            "信里那句话不是比喻。你从下缘把墙板撬起来，后面是一个刚好放得下一本书的空腔。",
                            "你按着那句话数过去：第三块板，从窗户往里。它是松的，边缘被人反复摸过，木头已经发亮。"}
                        : new String[]{
                            "The line in the letter was not a metaphor. You lever the panel up from its lower edge, and behind it is a cavity exactly the size of one book.",
                            "You count it out the way the line said: third panel in from the window. It is loose, and its edge is polished smooth where a hand went back to it again and again."})
                    : pick(zh ? new String[]{
                            "墙板裂了，可惜裂错了地方。你从豁口里掏出来的只有一半——另一半压在梁下面，凭你手上的工具够不着。",
                            "你拆错了一块。后面是实心的砖。等你找到对的那块，木头已经被你弄裂了，里面的东西沾着潮气，边角糊成一片。"}
                        : new String[]{
                            "The panel splits, but not where you wanted it to. What you pull out of the gap is only half — the rest is pinned under the joist, past anything you are carrying.",
                            "You take out the wrong panel first. Behind it is solid brick. By the time you find the right one the wood is already split, and what is inside has taken the damp: the corners have gone to pulp."});
        };
    }

    /**
     * Later arcs are not a replay of the first night: they are re-reading, asking again, and
     * going back to a space the player has already mapped.
     */
    private String continuationConsequence() {
        boolean ok = isSuccess();
        int stage = narrationBeat - BEAT_EVIDENCE; // 0 re-read, 1 ask again, 2 go back
        return switch (Math.max(0, Math.min(2, stage))) {
            case 0 -> ok
                    ? (zh ? "你把已经读过三遍的那几页又摊开一次。这一次你看的是页边：有一行被指甲划过的浅痕，压着一个你以前当成污渍的数字。"
                          : "You lay out the pages you have already read three times. This time you look at the margins — and there is a line scored by a fingernail, holding down a number you had been reading as a smudge.")
                    : (zh ? "你把顺序重新排了一遍，越排越不对。有一页的纸比其他的薄，字迹也更用力——它根本不属于这一叠。你现在不知道它是从哪里来的。"
                          : "You put the order back together and it comes out worse. One sheet is thinner than the rest and pressed harder — it does not belong to this stack at all. And now you do not know where it came from.");
            case 1 -> ok
                    ? (zh ? "你把那个数字给%s看。他看了很久，久到你以为他不打算说话了。然后他说出了一个地址，说完就把手缩回了口袋。".formatted(witness)
                          : "You show %s the number. They look at it for a long time, long enough that you think they are not going to speak. Then they say an address, and put their hands back in their pockets.".formatted(witness))
                    : (zh ? "%s没有接你的话，只是把门推开了一点点，说天太晚了。但在他关门之前，你看见他往%s的方向看了一眼——不是随便看的。".formatted(witness, place)
                          : "%s does not take up the question, only opens the door a little wider and says it is late. But before it closes you see them glance toward %s — and it is not an idle glance.".formatted(witness, place));
            default -> ok
                    ? (zh ? "你回到那个空腔，这一次连着旁边的一块板一起拆。第二个格子比第一个浅，里面的东西是用同一种布包的。"
                          : "You go back to the cavity and this time take the neighbouring panel with it. The second recess is shallower than the first, and what is in it is wrapped in the same cloth.")
                    : (zh ? "你把第二块板也撬开了，里面是空的——但不是一直空着的。灰上留着一个方方正正的印子，边缘很干净。有人比你先到。"
                          : "You get the second panel off and the space behind it is empty — but it has not always been. There is a clean square in the dust with sharp edges. Someone got here first.");
        };
    }

    private String socialConsequence() {
        if (isSuccess()) {
            return pickWide(zh ? new String[]{
                            "你把问题问完了，然后没有再说话。沉默比追问管用——过了一会儿，%s自己开了口，说了一句本来不打算说的。".formatted(witness),
                            "你没有追着问，只是等。%s先是摇头，接着像是跟自己商量完了，说了一件他这些年没跟任何人提过的事。".formatted(witness),
                            "你换了个问法，把话头递给他。%s接住了——他说的比你问的多，而且他自己也听见了这一点，于是停在了半句上。".formatted(witness)}
                        : new String[]{
                            "You finish the question and then say nothing. The silence works better than pressing would have: after a while %s says something they had not meant to.".formatted(witness),
                            "You do not chase it. You just wait. %s shakes their head first, then seems to finish an argument with themselves, and tells you a thing they have not told anyone in years.".formatted(witness),
                            "You put the question a different way and hand it over. %s takes it — and answers past what you asked, then hears themselves doing it and stops mid-sentence.".formatted(witness)});
        }
        if (isFailure()) {
            return pickWide(zh ? new String[]{
                            "你问得太快了，接连两句。%s的下巴收了一下，把手从口袋里拿出来又插回去。你能看见那扇门在他脸上关上。".formatted(witness),
                            "你提到了那个名字。%s的表情没有变，但他往后退了半步，把话头转到天气上——转得太生硬，反而告诉了你一些东西。".formatted(witness),
                            "你说到一半就知道说错了。%s点点头，说了两句客气话，然后看了一眼手表。剩下的问题你只能自己带回去。".formatted(witness)}
                        : new String[]{
                            "You ask too fast, two questions on top of each other. %s's jaw tightens; their hands come out of their pockets and go back in. You can see the door closing in their face.".formatted(witness),
                            "You use the name. %s's expression does not change, but they take half a step back and move the conversation to the weather — so clumsily that it tells you something anyway.".formatted(witness),
                            "You know it is wrong before you have finished saying it. %s nods, offers two polite sentences, and checks their watch. The rest of your questions go home with you.".formatted(witness)});
        }
        return zh
                ? "你把话说完了。%s听着，点了一下头，什么也没有补充。".formatted(witness)
                : "You say your piece. %s listens, nods once, and adds nothing.".formatted(witness);
    }

    private String cautiousConsequence() {
        if (isFailure()) {
            return pickWide(zh ? new String[]{
                            "你确实小心了，但小心花掉了时间。等你准备好动手，光已经暗到只剩手电那一圈。",
                            "你把每一步都想了两遍。想到第三遍的时候，你发现自己其实是在拖延——而外面的天，已经不给你第二次机会了。",
                            "你把准备做得很足，足到多余。等你终于伸出手，才发现刚才那点声音已经停了——你错过了它是从哪儿来的。"}
                        : new String[]{
                            "You are careful, and being careful costs time. By the time you are ready to move, the light has gone down to the circle of your torch and nothing more.",
                            "You think every step through twice. Somewhere in the third pass you realise you are stalling — and outside, the light has stopped offering you a second chance.",
                            "You prepare thoroughly, then past thoroughly. When you finally put your hand out, the small sound has already stopped — and you have missed where it came from."});
        }
        return pickWide(zh ? new String[]{
                        "你先把该做的准备做完了。没有什么戏剧性的事情发生——但你现在知道自己站在哪儿，退路在哪儿。",
                        "你花了几分钟把环境理顺：门开着，灯的位置记住了，脚下没有东西会绊人。做完这些，呼吸才慢下来。",
                        "你没有急着往前。你先站定，把这个地方从头到尾看了一遍，直到它不再像一张随时会翻过来的照片。"}
                    : new String[]{
                        "You take the time to set yourself up first. Nothing dramatic happens — but you know where you are standing now, and where the way out is.",
                        "You spend a few minutes making the space make sense: door open, light placed, nothing underfoot to catch you. Only after that does your breathing slow down.",
                        "You do not go forward straight away. You stand still and look the place over end to end, until it stops feeling like a photograph about to turn over."});
    }

    private String ambience(int beat) {
        if (beat == FINAL) {
            return zh ? "东西在你手上。你能听见自己的呼吸。这栋房子第一次显得只是一栋房子。"
                      : "It is in your hands. You can hear yourself breathing. For the first time the house is only a house.";
        }
        return switch (beat) {
            case BEAT_ARRIVAL -> pick(
                    zh ? new String[]{
                            "天已经压下来了。%s的窗户里没有一盏灯，玻璃把最后一点光原样还给你。".formatted(place),
                            "风从长草里穿过去，屋檐下有什么在轻轻碰着。除此之外，安静得很完整。"}
                        : new String[]{
                            "The sky has come down low. Not one window in %s is lit; the glass hands the last of the light straight back to you.".formatted(place),
                            "Wind goes through the long grass and something under the eaves taps, twice. Otherwise the quiet is complete."});
            case BEAT_EXPLORE -> pick(
                    zh ? new String[]{
                            "屋里的灰是均匀的，只有一条不是：从门口到楼梯，有一道被反复踩过的痕迹。",
                            "家具上盖着白布，位置一点没动过——像是有人打算当天晚上就回来。"}
                        : new String[]{
                            "The dust lies even everywhere but one place: a track from the door to the stairs, walked more than once.",
                            "Sheets over the furniture, everything still where it was set down — as though someone meant to come back that same evening."});
            case BEAT_EVIDENCE -> pick(
                    zh ? new String[]{
                            "纸上的字迹你认得。写得很小、很省，像是写的人知道有一天会有人来读，但不确定是谁。",
                            "日期是连着的，只有一天空着。那一天的位置留了行距，好像原本打算补上。"}
                        : new String[]{
                            "You know the handwriting. Small and sparing, like someone who knew this would be read one day but not by whom.",
                            "The dates run on unbroken except for one gap. Space was left for that day, as if it was going to be filled in later."});
            case BEAT_CONFRONT -> pick(
                    zh ? new String[]{
                            "楼上很黑，黑得像是把光吃掉了。你的手电只照亮扶手的前三级。",
                            "你看了一眼时间。离午夜还有十一分钟。这栋房子好像也知道。"}
                        : new String[]{
                            "The upstairs dark is the kind that eats light. Your torch reaches the first three steps of the banister and stops.",
                            "You check the time. Eleven minutes to midnight. The house seems to know it too."});
            default -> pick(
                    zh ? new String[]{
                            "空腔里的空气比屋子里冷。有人在这里放东西的时候，把它封得很仔细。",
                            "你把手伸进去。指尖先碰到布，再碰到纸的边——很整齐，是被人一页一页理好的。"}
                        : new String[]{
                            "The air in the cavity is colder than the room. Whoever put something here sealed it carefully.",
                            "You put your hand in. Cloth first, then the edge of paper — squared off, straightened page by page by somebody's hands."});
        };
    }

    private String witnessLine() {
        if (choice == null) {
            return zh
                    ? "你要在里面过夜？……那就别管楼上的动静。听见了，也别上去。"
                    : "You're staying the night in there? ... Then leave the upstairs alone. If you hear it, don't go up.";
        }
        if (isFailure()) {
            return zh ? "我该走了。我知道的都告诉你了。" : "I should go. I've told you what I know.";
        }
        return zh
                ? "最后那几天，%s不让我进门。他说等他弄完。我没有问是什么。".formatted(absent)
                : "Those last days, %s wouldn't let me in. Said to wait until it was finished. I never asked what.".formatted(absent);
    }

    private void writeEnding(ArrayNode blocks) {
        narration(blocks, zh
                ? "天开始亮了。%s在光里看上去只是一栋旧房子，什么也不是。".formatted(place)
                : "It starts getting light. In daylight %s is only an old building and nothing else.".formatted(place));
        narration(blocks, zh
                ? "你把找到的东西放进包里。它比你想的轻。"
                : "You put what you found into your bag. It weighs less than you expected.");
        narration(blocks, zh
                ? "这一段到这里为止了。但你手上这份东西指向的不是结束——是下一个地址。"
                : "This part is over. But what you are holding does not point at an ending. It points at another address.");
    }

    // ------------------------------------------------------------------ choices

    private static final int FINAL = MockLlmClient.FINAL_BEAT;

    private void writeChoices(ArrayNode choices) {
        if (choiceBeat == FINAL) {
            addChoice(choices, "c1", zh ? "把东西收好，天亮之前离开。" : "Pack it away and leave before full light.",
                    "cautious", null, 0, null);
            return;
        }
        if (isContinuation()) {
            writeContinuationChoices(choices);
            return;
        }
        switch (choiceBeat) {
            case BEAT_ARRIVAL -> {
                addChoice(choices, "c1", zh ? "在进去之前，先绕着%s走一圈。".formatted(place)
                                : "Walk the whole way around %s before going in.".formatted(place),
                        "investigation", "Perception", 12,
                        zh ? "在天黑透之前看清这栋房子的外围" : "reading the outside of the building before full dark");
                addChoice(choices, "c2", zh ? "你刚才想说什么？请把话说完。"
                                : "What were you about to say? Please finish.",
                        "social", "Presence", 12,
                        zh ? "让一个不想说的人多说一句" : "getting one more sentence out of someone who would rather not");
                addChoice(choices, "c3", zh ? "什么也不问，直接推门进去。" : "Ask nothing. Open the door and go in.",
                        "risky", null, 0, null);
            }
            case BEAT_EXPLORE -> {
                addChoice(choices, "c1", zh ? "把一楼的抽屉和柜子逐个翻开。" : "Go through every drawer and cabinet on the ground floor.",
                        "investigation", "Perception", 13,
                        zh ? "在一屋子旧东西里找出不属于这里的那一件" : "finding the one thing in a room of old things that does not belong");
                addChoice(choices, "c2", zh ? "先把窗帘全部拉开，让光进来，再动手。" : "Open every curtain first. Let the light in, then start.",
                        "cautious", null, 0, null);
                addChoice(choices, "c3", zh ? "对着楼上喊一声%s的名字。".formatted(absent)
                                : "Call %s's name up the stairs.".formatted(absent),
                        "risky", "Will", 13,
                        zh ? "在这样的房子里主动出声" : "making a noise on purpose in a house like this");
                if (!state.inventory.isEmpty()) {
                    addChoice(choices, "c4", zh ? "借着%s，把刚才那间屋子再看一遍。".formatted(state.inventory.get(0).name)
                                    : "Go back over that room using %s.".formatted(state.inventory.get(0).name),
                            "resource", "Intellect", 11,
                            zh ? "用手上已有的东西重新解读现场" : "re-reading the room with what you already carry");
                }
            }
            case BEAT_EVIDENCE -> {
                addChoice(choices, "c1", zh ? "把书架挪开，看它后面。" : "Move the shelf and look behind it.",
                        "investigation", "Perception", 14,
                        zh ? "从一条一指宽的缝里判断有没有东西" : "reading a finger-wide gap for what is inside it");
                addChoice(choices, "c2", zh ? "回去找%s，问%s最后那几天的事。".formatted(witness, absent)
                                : "Go back to %s and ask about %s's last days.".formatted(witness, absent),
                        "social", "Presence", 13,
                        zh ? "让对方说出一直没说的那部分" : "getting at the part they have been holding back");
                addChoice(choices, "c3", zh ? "先把手上的纸按日期排开，读完再动。" : "Lay the pages out by date and read them all before touching anything else.",
                        "cautious", "Intellect", 12,
                        zh ? "在动手之前先把顺序弄对" : "getting the order right before doing anything");
            }
            case BEAT_CONFRONT -> {
                addChoice(choices, "c1", zh ? "赶在午夜之前上二楼。" : "Go up to the second floor before midnight.",
                        "risky", "Will", 15,
                        zh ? "在被警告过的时间上楼" : "going up at the hour you were warned about");
                addChoice(choices, "c2", zh ? "在楼梯口坐下来，等那个声音自己出现。" : "Sit at the foot of the stairs and let the sound come to you.",
                        "cautious", "Will", 12,
                        zh ? "什么都不做地待到午夜" : "doing nothing at all until midnight");
                addChoice(choices, "c3", zh ? "不上楼。先在一楼确定声音的落点。" : "Stay downstairs. Work out exactly where the sound lands.",
                        "investigation", "Perception", 14,
                        zh ? "靠天花板的接缝反推位置" : "triangulating from the ceiling joints");
            }
            default -> {
                addChoice(choices, "c1", zh ? "按信里那句话，去找它说的位置。" : "Follow the line in the letter to the place it names.",
                        "investigation", "Intellect", 14,
                        zh ? "把一句写给家里人的暗语解开" : "reading a line written to be understood only by family");
                addChoice(choices, "c2", zh ? "不管那么多，直接把墙板拆开。" : "Stop being careful. Take the panelling apart.",
                        "risky", "Body", 13,
                        zh ? "在没有工具的情况下硬拆" : "forcing it with what you have");
                addChoice(choices, "c3", zh ? "先确认身后没有人，再动手。" : "Make sure there is no one behind you first.",
                        "cautious", "Perception", 11,
                        zh ? "在动手之前先确认自己是不是一个人" : "checking whether you are alone before you commit");
            }
        }
    }

    /** Arc 2+ is re-reading, asking again and going back -- not another first night. */
    private void writeContinuationChoices(ArrayNode choices) {
        int stage = Math.max(0, Math.min(2, choiceBeat - BEAT_EVIDENCE));
        switch (stage) {
            case 0 -> {
                addChoice(choices, "c1", zh ? "把每一页的页边逐张对一遍。" : "Go along the margin of every page, one at a time.",
                        "investigation", "Perception", 13,
                        zh ? "在读过三遍的东西里找出第四遍才看得见的" : "finding on the fourth pass what three readings missed");
                addChoice(choices, "c2", zh ? "先按纸的厚度和墨色把它们分成两叠。" : "Sort them into two stacks first, by paper weight and ink.",
                        "cautious", "Intellect", 12,
                        zh ? "先确定哪几页根本不属于这一叠" : "establishing which pages never belonged to this stack");
                addChoice(choices, "c3", zh ? "把那个数字拿去问%s。".formatted(witness)
                                : "Take the number to %s.".formatted(witness),
                        "social", "Presence", 13,
                        zh ? "拿着新证据再敲一次那扇门" : "knocking again, this time holding something new");
            }
            case 1 -> {
                addChoice(choices, "c1", zh ? "直接问他那个地址是谁的。" : "Ask them outright whose address it is.",
                        "social", "Presence", 14,
                        zh ? "把最后一个问题问出口" : "asking the one question left");
                addChoice(choices, "c2", zh ? "不追问，等他自己说完。" : "Do not press. Let them finish in their own time.",
                        "cautious", "Will", 12,
                        zh ? "忍住不填补沉默" : "not filling the silence");
                addChoice(choices, "c3", zh ? "顺着他刚才看的方向，自己去看。" : "Follow the direction of that glance yourself.",
                        "investigation", "Perception", 13,
                        zh ? "跟着一个他没打算让你看见的动作" : "following a movement they did not mean you to catch");
            }
            default -> {
                addChoice(choices, "c1", zh ? "回到那个空腔，把旁边那块板也拆开。" : "Go back to the cavity and take the next panel too.",
                        "investigation", "Perception", 14,
                        zh ? "赌第二个格子就在第一个旁边" : "betting the second recess sits beside the first");
                addChoice(choices, "c2", zh ? "把整面墙板全部拆掉。" : "Take the entire run of panelling off the wall.",
                        "risky", "Body", 14,
                        zh ? "不再讲究，直接全拆" : "no more finesse, just all of it");
                addChoice(choices, "c3", zh ? "先确认这一次没有人跟着你。" : "Make sure that this time nobody followed you.",
                        "cautious", "Perception", 12,
                        zh ? "在动手之前先回头" : "looking back before committing");
            }
        }
    }

    private void addChoice(ArrayNode arr, String id, String text, String approach, String stat, int dc, String description) {
        ObjectNode c = arr.addObject();
        c.put("id", id);
        c.put("text", text);
        c.put("approach", approach);
        boolean dialogue = "social".equals(approach) && "c2".equals(id) && choiceBeat == BEAT_ARRIVAL && !isContinuation();
        c.put("actionKind", dialogue ? "dialogue" : "action");
        c.put("playerExpression", dialogue ? "talking" : "action");
        if (stat == null) {
            c.putNull("check");
        } else {
            ObjectNode check = c.putObject("check");
            check.put("stat", stat);
            check.put("dc", dc);
            check.put("description", description);
        }
    }

    // ------------------------------------------------------------------ delta

    private ObjectNode buildDelta() {
        ObjectNode delta = mapper.createObjectNode();
        ArrayNode ops = delta.putArray("ops");
        if (ending) return delta;

        String locationId = locationFor(choiceBeat);
        if (!locationId.equals(state.currentLocationId)) {
            op(ops, "changeLocation", locationId, null, null, zh ? "场景移动" : "the scene moves");
        }

        if (choice == null) {
            op(ops, "setFlag", "arrived", null, "true", zh ? "玩家已经到达" : "the player has arrived");
            op(ops, "meetCharacter", "npc_witness", null, null, zh ? "在门外遇到目击者" : "meets the witness outside");
            return delta;
        }

        if (isSocial()) {
            op(ops, "relationshipDelta", "npc_witness", isFailure() ? -1 : 1, null,
                    isFailure() ? (zh ? "问得太急" : "pushed too hard") : (zh ? "对方多说了一句" : "they said one more thing"));
            if (isSuccess()) {
                op(ops, "setFlag", "learned_from_witness", null, "true",
                        zh ? "目击者说出了保留的部分" : "the witness gave up what they held back");
            }
        } else if (isCautious()) {
            op(ops, "setFlag", "took_precautions", null, isFailure() ? "slow" : "true",
                    zh ? "玩家先做了准备" : "the player set up first");
        } else {
            writeBeatGain(ops);
        }

        if (completesBeat && state.currentBeatId != null) {
            op(ops, "completeBeat", state.currentBeatId, null, null,
                    zh ? "该节拍的条件已满足" : "this beat's conditions are met");
        }
        return delta;
    }

    /** The concrete thing an investigative / risky / resource action wins or costs. */
    private void writeBeatGain(ArrayNode ops) {
        switch (narrationBeat) {
            case BEAT_ARRIVAL -> op(ops, "setFlag", "entered_house", null, "true",
                    zh ? "玩家进入了内部" : "the player went inside");
            case BEAT_EXPLORE -> {
                String keys = zh ? "一串黄铜钥匙" : "a ring of brass keys";
                if (isSuccess() && !state.hasItem(keys)) {
                    op(ops, "addInventory", keys, null,
                            zh ? "用布裹着，藏在浅抽屉的夹层里" : "wrapped in cloth in the false bottom of a shallow drawer",
                            zh ? "在抽屉夹层里找到" : "found in the drawer's false bottom");
                } else if (isSuccess()) {
                    op(ops, "setFlag", "searched_ground_floor", null, "true",
                            zh ? "一楼已经翻遍了" : "the ground floor has been gone over");
                } else if (isFailure()) {
                    op(ops, "setFlag", "made_noise", null, "true", zh ? "抽屉落地的响声" : "the drawer hitting the floor");
                }
            }
            case BEAT_EVIDENCE -> {
                String pages = zh ? "被塞在踢脚线上方的信页" : "pages wedged above the skirting";
                if (isSuccess() && !state.hasItem(pages)) {
                    op(ops, "addInventory", pages, null,
                            zh ? "%s的字迹，日期连续，只缺一天".formatted(absent) : "%s's handwriting, dates unbroken but for one".formatted(absent),
                            zh ? "从书架后面取出" : "recovered from behind the shelf");
                } else if (isFailure()) {
                    op(ops, "hpDelta", "player", -1, null, zh ? "手背被柜角划到" : "cabinet edge across the back of the hand");
                    op(ops, "setFlag", "papers_scattered", null, "true", zh ? "纸的顺序乱了" : "the pages lost their order");
                }
            }
            case BEAT_CONFRONT -> {
                if (isSuccess()) {
                    op(ops, "setFlag", "identified_the_sound", null, "true",
                            zh ? "确认了声音不是走动" : "the sound is not walking");
                    resolveNthThread(ops, 1);
                } else if (isFailure()) {
                    op(ops, "hpDelta", "player", -2, null, zh ? "在楼梯上失去平衡" : "lost footing on the stairs");
                    String shaken = zh ? "手在抖" : "shaking hands";
                    if (state.player.conditions.stream().noneMatch(c -> c.equalsIgnoreCase(shaken))) {
                        op(ops, "addCondition", shaken, null, null,
                                zh ? "声音朝楼梯来了" : "it came toward the stairs");
                    }
                }
            }
            default -> {
                String prize = zh ? absent + "的" + object : object;
                String half = zh ? "半本" + object : "half of " + object;
                if (isSuccess() && !state.hasItem(prize)) {
                    op(ops, "addInventory", prize, null,
                            zh ? "封在墙板后面的空腔里" : "sealed in the cavity behind the panelling",
                            zh ? "找到了被藏起来的东西" : "the hidden thing, found");
                    resolveNthThread(ops, 0);
                } else if (isFailure() && !state.hasItem(half)) {
                    op(ops, "addInventory", half, null,
                            zh ? "另一半压在梁下面" : "the rest is pinned under the joist",
                            zh ? "只取回了一半" : "only half of it came free");
                    op(ops, "addThread", null, null,
                            zh ? "另一半在哪里？" : "Where is the other half?",
                            zh ? "拆坏了墙板" : "the panel split wrong");
                }
            }
        }
    }

    private void resolveNthThread(ArrayNode ops, int index) {
        var open = state.continuityLedger.stream()
                .filter(e -> !ContinuityEntry.RESOLVED.equals(e.status))
                .toList();
        if (open.size() > index) {
            op(ops, "resolveThread", open.get(index).id, null, null,
                    zh ? "这个问题有答案了" : "this question now has an answer");
        }
    }

    private void op(ArrayNode ops, String name, String target, Integer amount, String value, String reason) {
        ObjectNode o = ops.addObject();
        o.put("op", name);
        if (target != null) o.put("target", target);
        if (amount != null) o.put("amount", amount);
        if (value != null) o.put("value", value);
        if (reason != null) o.put("reason", reason);
    }

    // ------------------------------------------------------------------ helpers

    private String locationFor(int beat) {
        if (beat == FINAL) return "loc_hidden";
        return switch (beat) {
            case BEAT_ARRIVAL -> "loc_threshold";
            case BEAT_EXPLORE, BEAT_EVIDENCE -> "loc_interior";
            case BEAT_CONFRONT -> "loc_upper";
            default -> "loc_hidden";
        };
    }

    private void putLocation(ObjectNode root, String id) {
        LocationProfile profile = story.bible.location(id);
        ObjectNode loc = root.putObject("location");
        loc.put("id", id);
        loc.put("name", profile != null ? profile.name() : place);
        loc.put("visualDescription", profile != null ? profile.visualDescription() : place);
        loc.put("backgroundPrompt", (profile != null ? profile.visualDescription() : place)
                + (zh ? "，视觉小说背景插画，写实光影" : ", visual novel background illustration, cinematic lighting"));
    }

    private void narration(ArrayNode blocks, String text) {
        ObjectNode b = blocks.addObject();
        b.put("type", "narration");
        b.put("text", text);
    }

    private void dialogue(ArrayNode blocks, String speakerId, String speakerName, String text, String expression) {
        ObjectNode b = blocks.addObject();
        b.put("type", "dialogue");
        b.put("speakerId", speakerId);
        b.put("speakerName", speakerName);
        b.put("text", text);
        b.put("expression", expression);
    }

    private boolean isContinuation() { return state.storyProgress.arcNumber > 1; }
    private boolean isSuccess() { return "SUCCESS".equals(outcome); }
    private boolean isFailure() { return "FAILURE".equals(outcome); }
    private boolean isNone() { return !isSuccess() && !isFailure(); }
    private boolean isSocial() { return choice != null && "social".equalsIgnoreCase(choice.approach()); }
    private boolean isCautious() { return choice != null && "cautious".equalsIgnoreCase(choice.approach()); }

    private String pick(String[] options) {
        return options[Math.floorMod(seed, options.length)];
    }

    /** Variant selection for banks that are reused across beats, so lines do not repeat back to back. */
    private String pickWide(String[] options) {
        int mixed = java.util.Objects.hash(sceneIndex, narrationBeat, outcome,
                choice == null ? "" : choice.id(), state.currentLocationId);
        return options[Math.floorMod(mixed, options.length)];
    }
}
