export type StatName = "Body" | "Agility" | "Perception" | "Intellect" | "Will" | "Presence";

export const STATS: StatName[] = ["Body", "Agility", "Perception", "Intellect", "Will", "Presence"];

export const STAT_LABEL: Record<StatName, string> = {
  Body: "体能 Body",
  Agility: "敏捷 Agility",
  Perception: "感知 Perception",
  Intellect: "智识 Intellect",
  Will: "意志 Will",
  Presence: "魅力 Presence",
};

/**
 * Jackson serialises the Java `Stat` enum map keys as BODY / PERCEPTION / ...
 * The UI uses the display spelling, so look up both.
 */
export function statValue(stats: Record<string, number>, stat: StatName): number {
  return stats[stat.toUpperCase()] ?? stats[stat] ?? 0;
}

export interface Check {
  stat: StatName | string;
  dc: number;
  description: string | null;
}

export interface Choice {
  id: string;
  text: string;
  approach: string | null;
  actionKind?: "dialogue" | "action";
  playerExpression?: string | null;
  check: Check | null;
  requirements: string[];
}

export interface Block {
  type: "narration" | "dialogue";
  speakerId: string | null;
  speakerName: string | null;
  text: string;
  expression: string | null;
}

export interface CharacterPresence {
  characterId: string;
  name: string;
  expression: string | null;
  position: "left" | "center" | "right" | string;
  visualDescription: string | null;
  /** Validated portrait id from the session's asset manifest, or null for the placeholder. */
  assetId?: string | null;
}

export interface SceneLocation {
  id: string;
  name: string;
  visualDescription: string | null;
  backgroundPrompt: string | null;
  /** Validated background id from the session's asset manifest, or null for the placeholder. */
  backgroundAssetId?: string | null;
}

export interface SceneMeta {
  generatedBy: string;
  fromSpeculativeCache: boolean;
  generationMillis: number;
  outcomeContext: string | null;
  sourceChoiceId: string | null;
  repairAttempts: number;
}

export interface DeltaOp {
  op: string;
  target: string | null;
  amount: number | null;
  value: string | null;
  reason: string | null;
}

export interface SceneBundle {
  sceneId: string;
  beatId: string | null;
  location: SceneLocation;
  characters: CharacterPresence[];
  blocks: Block[];
  choices: Choice[];
  proposedStateDelta: { ops: DeltaOp[] };
  storyProgressNote: string | null;
  meta: SceneMeta;
}

export interface InventoryItem {
  name: string;
  description: string | null;
  acquiredAtScene: string | null;
}

export interface ContinuityEntry {
  id: string;
  description: string;
  status: "unresolved" | "partiallyResolved" | "resolved" | string;
  introducedAtScene: string | null;
  resolvedAtScene: string | null;
}

export interface CharacterState {
  id: string;
  name: string;
  met: boolean;
  relationship: number;
  lastSeenSceneId: string | null;
  knowledge: string[];
}

export interface PlayerCharacter {
  id: string;
  visualDescription: string;
  name: string;
  background: string;
  stats: Record<string, number>;
  hp: number;
  maxHp: number;
  traits: string[];
  conditions: string[];
}

export interface StoryProgress {
  arcNumber: number;
  scenesPlayed: number;
  beatsCompleted: number;
  totalBeats: number;
  fraction: number;
}

export interface GameState {
  sessionId: string;
  stateVersion: number;
  player: PlayerCharacter;
  characters: Record<string, CharacterState>;
  inventory: InventoryItem[];
  flags: Record<string, string>;
  currentLocationId: string | null;
  knownLocationIds: string[];
  currentArcTitle: string | null;
  currentBeatId: string | null;
  scenesInCurrentBeat: number;
  completedBeats: string[];
  continuityLedger: ContinuityEntry[];
  currentSceneId: string | null;
  recentEvents: string[];
  storyProgress: StoryProgress;
  rejectedOpsLog: string[];
}

export interface StoryBeat {
  id: string;
  title: string;
  purpose: string | null;
  completionConditions: string | null;
  importance: string | null;
}

export interface NpcProfile {
  id: string;
  name: string;
  description: string;
  personality: string;
  visualDescription: string;
  relationshipToPlayer: string;
}

export interface CompiledStory {
  artStyle: string;
  playerVisual: NpcProfile | null;
  authorCanon: { originalOutline: string; facts: string[] };
  bible: {
    premise: string;
    tone: string;
    themes: string[];
    characters: {
      id: string;
      name: string;
      description: string;
      personality: string;
      visualDescription: string;
      relationshipToPlayer: string;
    }[];
    locations: { id: string; name: string; description: string; visualDescription: string }[];
    importantObjects: string[];
    mysteries: string[];
    hardCanon: string[];
    softCanon: string[];
  };
  spine: { arcTitle: string; beats: StoryBeat[] };
  laterArcs: { arcTitle: string }[];
}

/** A die already cast on the current scene. The number was seen, so it binds: play resumes from it. */
export interface PendingRollView {
  choiceId: string;
  roll: RollView;
}

export interface RollResponse {
  sessionId: string;
  choiceId: string;
  roll: RollView;
  /** True when this die had already been cast earlier for the same choice. */
  reused: boolean;
}

export interface SessionView {
  sessionId: string;
  title: string;
  finished: boolean;
  saveHealthy: boolean;
  continuationPending: boolean;
  story: CompiledStory;
  state: GameState;
  scene: SceneBundle;
  pendingRoll: PendingRollView | null;
  /** An existing request owns this choice; wait for it instead of resubmitting. */
  resolvingChoiceId?: string | null;
}

export interface RollView {
  stat: string;
  d20: number;
  modifier: number;
  total: number;
  dc: number;
  success: boolean;
  critical: boolean;
  fumble: boolean;
  summary: string;
}

export interface ChoiceView {
  sessionId: string;
  chosenText: string;
  roll: RollView | null;
  scene: SceneBundle;
  state: GameState;
  story: CompiledStory;
  finished: boolean;
  saveHealthy: boolean;
  continuationPending: boolean;
  meta: {
    fromSpeculativeCache: boolean;
    discardedBranches: number;
    generationMillis: number;
    rejectedOps: string[];
    reusedPendingRoll: boolean;
  };
}

/** GET /api/access: does the server want a key, and is the one we sent right. */
export interface AccessView {
  required: boolean;
  granted: boolean;
}

export interface ConfigView {
  llm: string;
  mockMode: boolean;
  speculationEnabled: boolean;
  continuationEnabled: boolean;
  imageEnabled: boolean;
}

export type SettingsKind = "STRING" | "SECRET" | "INTEGER" | "NUMBER" | "BOOLEAN" | "LIST";

export interface SettingsField {
  key: string;
  group: string;
  label: string;
  hint: string;
  kind: SettingsKind;
  value: string | number | boolean | string[] | null;
  secretSet: boolean;
  restartRequired: boolean;
}

export interface SettingsView {
  file: string;
  fields: SettingsField[];
  applied: string[];
  restartPending: string[];
}

export interface SessionSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  playerName: string;
  scenesPlayed: number;
  finished: boolean;
}

export interface CreationJobView {
  id: string;
  status: "QUEUED" | "RUNNING" | "READY" | "FAILED";
  stage: string;
  /** Completed pipeline stages, not an estimate of time remaining. */
  progress: number;
  logs: { id: number; time: string; message: string }[];
  sessionId: string | null;
  error: string | null;
}

export interface SessionTasksView {
  sessionId: string;
  sceneId: string;
  branches: {
    id: string;
    choiceId: string;
    choiceText: string;
    /** Whether a die is involved. How it landed is never sent: it is sealed until the reveal. */
    check: boolean;
    status: "queued" | "generating" | "ready" | "failed" | "cancelled";
    elapsedMillis: number | null;
    error: string | null;
  }[];
  continuationPending: boolean;
  resolvingChoiceId: string | null;
  speculationEnabled: boolean;
  speculationConcurrency: number;
  secondRound?: {
    status: "waiting_first_round" | "estimating" | "generating" | "ready" | "completed_with_failures" | "skipped";
    candidates: {
      key: string;
      parentChoiceId: string;
      choiceId: string;
      check: boolean;
      probability: number;
      status: string;
    }[];
    limit: number;
  };
}

// ---------------------------------------------------------------- pictures

export type AssetStatusName =
  | "PLANNED" | "QUEUED" | "GENERATING" | "READY" | "FAILED" | "PAUSED" | "MISSING";

export interface AssetView {
  assetId: string;
  kind: "BACKGROUND" | "PORTRAIT" | "PORTRAIT_VARIANT" | "CHARACTER_CARD";
  subjectId: string;
  subjectName: string;
  variant: string;
  status: AssetStatusName;
  fileName: string | null;
  width: number;
  height: number;
  beatId: string | null;
  priority: number;
  dependsOn: string | null;
  applicability: string;
  attempts: number;
  failureReason: string | null;
  generationVersion: number;
  plannedAt: string | null;
  queuedAt: string | null;
  startedAt: string | null;
  readyAt: string | null;
  firstNeededAt: string | null;
  reuseCount: number;
  readyBeforeNeeded: boolean | null;
  /** Present only when READY. Already versioned for cache busting. */
  url: string | null;
}

export interface AssetsStatus {
  version: number;
  style?: string;
  counts: Record<string, number>;
  pending: number;
  assets: AssetView[];
  provider: string;
  enabled: boolean;
  concurrency: number;
  active: number;
  highWaterConcurrency: number;
  queueDepth: number;
  queueCapacity: number;
  /** Set when this session's manifest file could not be read; the story plays without pictures. */
  manifestError?: string | null;
  budget?: {
    arcNumber: number;
    arcAttempts: number;
    arcBudget: number;
    attemptsTotal: number;
    firstBatchQueued: number;
    firstBatchBudget: number;
    paused: boolean;
    pauseReason: string | null;
  };
}

export interface HistoryPage {
  sessionId: string;
  entries: {
    sceneId: string;
    beatId: string | null;
    choiceText: string | null;
    rollSummary: string | null;
    blocks: Block[];
    at: string | null;
    restorable?: boolean;
  }[];
  nextBeforeSceneId: string | null;
}
