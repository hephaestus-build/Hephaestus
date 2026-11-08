/** Shared attribution policy for pull request titles, bodies, commit messages, and changesets.
 * A `Co-authored-by:` trailer is valid, even when it names a model. AGENTS.md § Pull requests owns the policy.
 */

export interface AttributionPattern {
	readonly name: string;
	readonly pattern: RegExp;
}

/** The trailer Claude Code writes to record its own session; `verify-changesets.ts` reuses it. */
export const CLAUDE_SESSION_PATTERN = /^claude-session:/imu;

export const MODEL_ATTRIBUTION_PATTERNS: readonly AttributionPattern[] = [
	{ name: "a Claude-Session trailer", pattern: CLAUDE_SESSION_PATTERN },
	{ name: 'a "Generated with" marker', pattern: /\bgenerated with\b/iu },
	{ name: "a claude.ai/code or session link", pattern: /claude\.ai\/(?:code|session)/iu },
];

/** The names of every pattern in `text`, or an empty array when none match. */
export const findModelAttribution = (text: string): readonly string[] =>
	MODEL_ATTRIBUTION_PATTERNS.filter(({ pattern }) => pattern.test(text)).map(({ name }) => name);
