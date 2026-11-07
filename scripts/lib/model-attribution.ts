/** Shared attribution policy for pull request titles, bodies, commit messages, and changesets.
 * Human co-author trailers remain valid. AGENTS.md § Pull requests owns the policy.
 */

const TOOL_NAMES = [
	"claude",
	"codex",
	"copilot",
	"gpt",
	"openai",
	"anthropic",
	"gemini",
	"cursor",
	String.raw`noreply@anthropic\.com`,
] as const;

export interface AttributionPattern {
	readonly name: string;
	readonly pattern: RegExp;
}

/** `Co-authored-by:` naming a model or tool; a human co-author never matches this. */
export const COAUTHOR_PATTERN = new RegExp(
	String.raw`^co-authored-by:.*(?:${TOOL_NAMES.join("|")})`,
	"imu",
);

/** The trailer Claude Code writes to record its own session; `verify-changesets.ts` reuses it. */
export const CLAUDE_SESSION_PATTERN = /^claude-session:/imu;

export const MODEL_ATTRIBUTION_PATTERNS: readonly AttributionPattern[] = [
	{ name: "a Co-Authored-By trailer naming a model or tool", pattern: COAUTHOR_PATTERN },
	{ name: "a Claude-Session trailer", pattern: CLAUDE_SESSION_PATTERN },
	{ name: 'a "Generated with" marker', pattern: /\bgenerated with\b/iu },
	{ name: "a claude.ai/code or session link", pattern: /claude\.ai\/(?:code|session)/iu },
];

/** The names of every pattern in `text`, or an empty array when none match. */
export const findModelAttribution = (text: string): readonly string[] =>
	MODEL_ATTRIBUTION_PATTERNS.filter(({ pattern }) => pattern.test(text)).map(({ name }) => name);
