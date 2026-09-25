/**
 * Running text that names practices and links work, and the rules every sentence of it shares —
 * how a number is written, how items are listed, how a piece of reviewed work is named — in one
 * place, so the overview's sentences and a feedback card's condition line cannot drift apart. A
 * sentence in the overview card or a card body is a list of segments, so a practice name inside
 * it is the same pill and a work reference the same link everywhere without the text being HTML.
 */
import type { ReviewedWorkRef } from "@/api/types.gen";
import { artifactKindNoun, type WorkProvider } from "@/lib/artifact-kinds";

/**
 * A piece of reviewed work as the wire names it — `!425` for a merge request, `#releases` for a
 * conversation, `#318` for an issue, a title for a document — and as the header, the overview
 * card and the feedback card all show it: an `InlineLink` in the foreground colour, a link only
 * when the wire carries the provider's page for it.
 */
export type FeedbackTextSegment =
	| { type: "text"; text: string }
	| { type: "practice"; slug: string; name: string }
	| { type: "group"; slug: string; name: string }
	| { type: "work"; ref: ReviewedWorkRef };

export function text(value: string): FeedbackTextSegment {
	return { type: "text", text: value };
}

export function practice(slug: string, name: string): FeedbackTextSegment {
	return { type: "practice", slug, name };
}

/**
 * A practice group named inside a sentence: its own icon and colour, the way a feedback card's
 * head names the group it sits in, rather than the words a practice pill's absence reads as. The
 * icon and the colour are the workspace's, so the segment carries only the slug to look them up
 * by and the name to show.
 */
export function group(slug: string, name: string): FeedbackTextSegment {
	return { type: "group", slug, name };
}

export function work(ref: ReviewedWorkRef): FeedbackTextSegment {
	return { type: "work", ref };
}

/**
 * How a piece of work is named in a body: the provider's number after its sigil, "#418" or "!421".
 * `feedback-composer.md` writes the number the provider gave the work and nothing else, so the
 * sigil the composer writes is "#"; the "!" a GitLab label carries is matched too, since a label is
 * what a reference is compared against.
 */
const WORK_REFERENCE = /[#!]\d+/gu;

/**
 * A run of the composer's own words with every reference to a piece of work the caller knows — a
 * card's evidence and its clean work — as a link to that piece. A number no known piece carries
 * stays words: a surface links what it can vouch for and invents nothing.
 *
 * A reference is read against the whole label first, sigil included, so "#21" reaches the issue and
 * "!21" the merge request where a GitLab project numbers both independently. The sigil is dropped
 * only when exactly one known piece carries that number, which is what lets a body written with "#"
 * reach a merge request; where two do, the reference is ambiguous and stays words.
 */
export function linkWork(body: string, known: ReviewedWorkRef[]): FeedbackTextSegment[] {
	const labelled = known.filter((ref) => /^[#!]\d+$/u.test(ref.label));
	const byLabel = new Map(labelled.map((ref) => [ref.label, ref] as const));
	const byNumber = new Map<string, ReviewedWorkRef | undefined>();
	for (const ref of labelled) {
		const number = ref.label.slice(1);
		byNumber.set(number, byNumber.has(number) ? undefined : ref);
	}
	const segments: FeedbackTextSegment[] = [];
	let cursor = 0;
	for (const match of body.matchAll(WORK_REFERENCE)) {
		const ref = byLabel.get(match[0]) ?? byNumber.get(match[0].slice(1));
		if (!ref) {
			continue;
		}
		if (match.index > cursor) {
			segments.push(text(body.slice(cursor, match.index)));
		}
		segments.push(work(ref));
		cursor = match.index + match[0].length;
	}
	if (cursor < body.length || segments.length === 0) {
		segments.push(text(body.slice(cursor)));
	}
	return segments;
}

const WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];

/** A number as the prose writes it: a word below ten, a digit from ten. */
export const spell = (n: number, digits = n >= 10): string =>
	digits ? String(n) : (WORDS[n] ?? String(n));

/**
 * `count(2, "practice", "practices")` is "two practices"; from ten the count is a digit. A clause
 * holding two counts passes `digits` for both, so "16 practices and three groups" cannot happen.
 */
export function count(n: number, one: string, many: string, digits = n >= 10): string {
	return `${spell(n, digits)} ${n === 1 ? one : many}`;
}

/**
 * Several counts in one clause, written under one rule: the clause goes to digits as soon as any
 * of its counts does, so "16 practices and three groups" cannot happen. Each caller joins the
 * pieces the way its own sentence reads — "16 practices in 5 groups" in the profile header, "three
 * practices and one group" in the overview.
 */
export function countsTogether(
	items: readonly { n: number; one: string; many: string }[],
): string[] {
	const digits = items.some((item) => item.n >= 10);
	return items.map((item) => count(item.n, item.one, item.many, digits));
}

/**
 * "four pull requests", "four merge requests" on GitLab: the registry's noun for the kind of work
 * at its provider, under the number rule.
 */
export const countedWork = (kind: string | undefined, n: number, provider?: WorkProvider): string =>
	`${spell(n)} ${artifactKindNoun(kind, n, provider)}`;

/** "A", "A and B", "A, B and C". */
export function list(items: FeedbackTextSegment[][]): FeedbackTextSegment[] {
	return items.flatMap((item, index) => {
		if (index === 0) {
			return item;
		}
		return [text(index === items.length - 1 ? " and " : ", "), ...item];
	});
}

/** The work references listed, each as a link: "!421, !423 and !425". */
export const refs = (evidence: ReviewedWorkRef[]): FeedbackTextSegment[] =>
	list(evidence.map((ref) => [work(ref)]));

/**
 * The practices listed, each as its pill: "Scope the change to one concern and Link the issue the
 * change closes". The sibling of {@link refs}, so a run of practices and a run of work references
 * are joined by the one rule.
 */
export const practices = (items: { slug: string; name: string }[]): FeedbackTextSegment[] =>
	list(items.map((item) => [practice(item.slug, item.name)]));
