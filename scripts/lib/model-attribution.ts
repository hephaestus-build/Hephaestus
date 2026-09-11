/**
 * Marks left by an AI coding tool in prose a reader weighs the change by — never a human
 * co-author, who is welcome under the same `Co-authored-by:` trailer. `AGENTS.md` § Pull requests
 * forbids these in a pull request title or body; `pull-request.yml`'s `verify-commit-identity` job
 * refuses them there, and `verify-changesets.ts` refuses the `Claude-Session` shape in a changeset
 * summary. A commit trailer is deliberately outside all of it: `Co-authored-by:` is git's own way to
 * record who or what worked on a commit, and crediting a tool there is allowed.
 *
 * The workflow step has no checkout (`docs/contributor/ci-cd.mdx` explains why), so it cannot
 * import this module; its inline patterns are hand-kept equal to the ones below.
 * `scripts/pull-request-workflows.test.ts` exercises that policy; `scripts/model-attribution.test.ts`
 * exercises this module.
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
	{ name: 'a "Generated with" marker', pattern: /\bgenerated with\b/i },
	{ name: "a claude.ai/code or session link", pattern: /claude\.ai\/(?:code|session)/i },
];

/** The names of every pattern in `text`, or an empty array when none match. */
export const findModelAttribution = (text: string): readonly string[] =>
	MODEL_ATTRIBUTION_PATTERNS.filter(({ pattern }) => pattern.test(text)).map(({ name }) => name);
