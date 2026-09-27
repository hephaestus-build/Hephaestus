/**
 * The transparency notice, word for word as the web app shows it (`webapp/src/components/auth/
 * ConsentPage.tsx`, which is the archive of what was accepted). An account accepts a version, so the
 * app shows these words only when the server asks for exactly this version; `wording.test.ts` fails
 * when the two copies drift.
 */
export const WORDING_VERSION = "2026-09-11";

export interface Fact {
	term: string;
	detail: string;
}

export const TERMS: readonly Fact[] = [
	{
		term: "What it reads",
		detail:
			"The work in the tools your project connects, such as pull requests, issues, reviews and chat.",
	},
	{
		term: "Feedback can be wrong",
		detail:
			"It is written by an AI model. Check it against the work it links to before you act on it.",
	},
	{
		term: "Your data",
		detail: "Who runs this instance, what it stores and for how long is in the privacy notice.",
	},
];

export const RESEARCH: readonly Fact[] = [
	{
		term: "Why it matters",
		detail: "What we learn from real projects is what makes the feedback better.",
	},
	{
		term: "What you share",
		detail: "How you use Hephaestus, and how you respond to its feedback.",
	},
	{ term: "What it asks of you", detail: "Nothing extra to do. Occasionally, an optional survey." },
];

/** Equal weight, no glyph on either: a nudge toward one answer is not consent. */
export const ANSWERS = [
	{ value: true, title: "Yes, take part", detail: "Use my usage and feedback for the research." },
	{
		value: false,
		title: "No, don't take part",
		detail: "Keep my usage and feedback out of the research.",
	},
] as const;

export const TERMS_ACCEPTANCE = "I accept the terms of use";
