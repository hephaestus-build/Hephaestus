/**
 * Marks left by an AI coding tool that signed its own commit, trailer or pull request
 * description — never a human co-author, who is welcome under the same `Co-authored-by:`
 * trailer. `AGENTS.md` § Pull requests forbids all of these; `pull-request.yml`'s
 * `verify-commit-identity` job refuses them on every commit message and the pull request
 * body, and `verify-changesets.ts` refuses the `Claude-Session` shape in a changeset summary.
 *
 * The workflow step has no checkout (`docs/contributor/ci-cd.mdx` explains why), so it cannot
 * import this module; its inline patterns are hand-kept equal to the ones below, which is what
 * `scripts/model-attribution.test.ts` exists to pin down.
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
	"noreply@anthropic\\.com",
] as const;

export type AttributionPattern = {
	readonly name: string;
	readonly pattern: RegExp;
};

/** `Co-authored-by:` naming a model or tool; a human co-author never matches this. */
export const COAUTHOR_PATTERN = new RegExp(
	String.raw`^co-authored-by:.*(?:${TOOL_NAMES.join("|")})`,
	"im",
);

/** The trailer Claude Code writes to record its own session; `verify-changesets.ts` reuses it. */
export const CLAUDE_SESSION_PATTERN = /^claude-session:/im;

export const MODEL_ATTRIBUTION_PATTERNS: readonly AttributionPattern[] = [
	{ name: "a Co-Authored-By trailer naming a model or tool", pattern: COAUTHOR_PATTERN },
	{ name: "a Claude-Session trailer", pattern: CLAUDE_SESSION_PATTERN },
	{ name: 'a "Generated with" marker', pattern: /generated with/i },
	{ name: "a claude.ai/code or session link", pattern: /claude\.ai\/(?:code|session)/i },
];

/** The names of every pattern in `text`, or an empty array when none match. */
export const findModelAttribution = (text: string): readonly string[] =>
	MODEL_ATTRIBUTION_PATTERNS.filter(({ pattern }) => pattern.test(text)).map(({ name }) => name);
