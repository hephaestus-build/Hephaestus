import type {
	ItemRef,
	PracticeProfileOverview,
	ProfileChange,
	ProfileReviewRef,
} from "@/api/types.gen";
import {
	type FeedbackTextSegment,
	group as groupSegment,
	practice as practiceSegment,
	practices as practiceSegments,
	refs,
	text,
} from "@/components/practice-vocabulary/feedback-text";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import { asDate, formatDay } from "@/lib/dates";
import { capitalise, hasText } from "@/lib/text";

type OverviewInput = Pick<PracticeProfileOverview, "latestReview" | "changes">;

export const EMPTY_OVERVIEW: OverviewInput = { changes: [], latestReview: null };

export interface ComposedOverview {
	latestReview?: ProfileReviewRef;
	/**
	 * What moved in each group, for the "All practice groups" table: one sentence per bullet, keyed
	 * by group slug. Practices that made the same move share a bullet.
	 */
	groupSentences: Record<string, FeedbackTextSegment[][] | undefined>;
}

/** What a group's level says: a sentence per practice in its table. */
interface ComposedGroupOverview {
	/** What moved per practice for the group's table, one bullet per sentence, keyed by slug. */
	practiceSentences: Record<string, FeedbackTextSegment[][] | undefined>;
}

type ChangeKind = "resolved" | "reset" | "down" | "up" | "first" | "trend";
type EventKind = ChangeKind;
type EventLevel = "practice" | "group";

interface ProfileEvent {
	kind: EventKind;
	level: EventLevel;
	slug: string;
	name: string;
	/** The registry's label for the standing or trend the subject moved to, when this build knows it. */
	label?: string;
	/**
	 * For resolved feedback: the developer's work coming back clean, or the developer marking it
	 * addressed.
	 */
	resolvedBy?: ProfileChange["resolvedBy"];
	/** For a fall back: the clean run the count fell back from, as the wire reports it. */
	cleanNeeded?: number;
	evidence: ItemRef[];
	at?: Date;
}

type ChangeEvent = ProfileEvent & { kind: ChangeKind };

const PRECEDENCE: readonly EventKind[] = ["resolved", "reset", "down", "up", "first", "trend"];
const rank = (event: ProfileEvent) => PRECEDENCE.indexOf(event.kind);

/** The table's reading order: what needs the reader first, then what it can be glad about. */
const SENTENCE_ORDER: readonly ChangeKind[] = ["reset", "resolved", "down", "up", "first", "trend"];

/** The registries' words, keyed by the enum values the wire carries. */
const STANDING_LABELS: Record<string, string | undefined> = Object.fromEntries(
	Object.entries(PRACTICE_GROUP_STANDING_DEFS).map(([value, def]) => [value, def.label]),
);
const TREND_LABELS: Record<string, string | undefined> = Object.fromEntries(
	Object.entries(PRACTICE_TREND_DEFS).map(([value, def]) => [value, def.label]),
);

/** The registry's word for a standing or a trend, which the wire types as a bare string. */
const standingLabel = (value: string | null) =>
	value === null ? undefined : STANDING_LABELS[value];
const trendLabel = (value: string | null) => (value === null ? undefined : TREND_LABELS[value]);

function changeEvent(change: ProfileChange): ChangeEvent | undefined {
	const group = change.type === "GROUP_MOVED";
	const slug = group ? change.groupSlug : change.practiceSlug;
	const name = group ? change.groupName : change.practiceName;
	if (!hasText(slug) || !hasText(name)) {
		return undefined;
	}
	const base = {
		level: group ? "group" : "practice",
		slug,
		name,
		evidence: change.evidence,
		at: asDate(change.at),
	} satisfies Partial<ProfileEvent>;
	switch (change.type) {
		// New feedback is the feedback card itself, and the pattern label beside the practice says it
		// in the lists: a sentence repeating it adds nothing.
		case "FEEDBACK_NEW": {
			return undefined;
		}
		case "FEEDBACK_RESET": {
			return { ...base, kind: "reset", cleanNeeded: change.cleanNeeded ?? undefined };
		}
		case "FEEDBACK_RESOLVED": {
			return { ...base, kind: "resolved", resolvedBy: change.resolvedBy ?? undefined };
		}
		// A move into or out of a standing that is not a verdict has no direction, and reads as up:
		// only a verdict that got worse is a slip.
		case "STANDING_MOVED":
		case "GROUP_MOVED": {
			return {
				...base,
				kind: change.direction === "DOWN" ? "down" : "up",
				label: standingLabel(change.to),
			};
		}
		// A first sighting says that the practice was seen, not where it landed: the standing it
		// arrived at is the badge beside the sentence on every surface that shows one.
		case "FIRST_RECORDED": {
			return { ...base, kind: "first" };
		}
		case "TREND_TURNED": {
			return { ...base, kind: "trend", label: trendLabel(change.to) };
		}
	}
}

const subjectKey = (event: ProfileEvent) => `${event.level}:${event.slug}`;
const isGood = (event: ProfileEvent) => event.kind === "resolved";
const isSlip = (event: ProfileEvent) => event.kind === "down" || event.kind === "reset";

// Deduplicate held/resolved and changed events separately so one practice can appear in both.
function dedupe<E extends ProfileEvent>(events: E[]): E[] {
	const best = new Map<string, E>();
	for (const event of events) {
		const key = `${subjectKey(event)}:${isGood(event) ? "good" : "change"}`;
		const current = best.get(key);
		if (current === undefined || rank(event) < rank(current)) {
			best.set(key, event);
		}
	}
	const kept = new Set(best.values());
	const surviving = events.filter((event) => kept.has(event));
	const slipped = new Set(surviving.filter(isSlip).map(subjectKey));
	return surviving.filter((event) => !(isGood(event) && slipped.has(subjectKey(event))));
}

const changeEventsOf = (changes: ProfileChange[]): ChangeEvent[] =>
	dedupe(changes.map(changeEvent).filter((event) => event !== undefined));

/**
 * The events that say the same thing collected together, in the order the first of each came. The
 * one home for the rule that the table's bullet and the card's paragraph both follow; what counts
 * as the same thing is the key each of them passes.
 */
function groupByTransition(
	events: ChangeEvent[],
	keyOf: (event: ChangeEvent) => string,
): Map<string, [ChangeEvent, ...ChangeEvent[]]> {
	const groups = new Map<string, [ChangeEvent, ...ChangeEvent[]]>();
	for (const event of events) {
		const together = groups.get(keyOf(event));
		if (together) {
			together.push(event);
		} else {
			groups.set(keyOf(event), [event]);
		}
	}
	return groups;
}

/**
 * The subject inside the group's own cell, where the row already names the group: a group-level
 * event says "the group" rather than repeating the name it sits under.
 */
const subjectInGroup = (event: ProfileEvent): FeedbackTextSegment =>
	event.level === "practice" ? practiceSegment(event.slug, event.name) : text("the group");

/** Adjacent text segments folded together, so a sentence is as few segments as it can be. */
function fold(segments: FeedbackTextSegment[]): FeedbackTextSegment[] {
	const out: FeedbackTextSegment[] = [];
	for (const segment of segments) {
		const last = out.at(-1);
		if (segment.type === "text" && last?.type === "text") {
			out[out.length - 1] = text(last.text + segment.text);
		} else if (segment.type !== "text" || segment.text.length > 0) {
			out.push(segment);
		}
	}
	return out;
}

/**
 * A sentence of segments opened with a capital, under the one rule every written sentence here
 * follows; a sentence that opens with a practice pill or a work link has no text to capitalise.
 */
function capitaliseSegments(segments: FeedbackTextSegment[]): FeedbackTextSegment[] {
	const [first, ...rest] = segments;
	if (first?.type !== "text") {
		return segments;
	}
	const opened = capitalise(first.text);
	return opened === first.text ? segments : [text(opened), ...rest];
}

type Piece = string | FeedbackTextSegment | FeedbackTextSegment[];

function asSegments(piece: Piece): FeedbackTextSegment[] {
	if (typeof piece === "string") {
		return [text(piece)];
	}
	return Array.isArray(piece) ? piece : [piece];
}

const sentence = (...pieces: Piece[]) => capitaliseSegments(fold(pieces.flatMap(asSegments)));

/** The work the event was seen on, linked and led in by `joiner`; nothing when it names none. */
const onWork = (event: ProfileEvent, joiner: string | undefined): FeedbackTextSegment[] =>
	joiner !== undefined && event.evidence.length > 0 ? [text(joiner), ...refs(event.evidence)] : [];

/**
 * "Back to none of 3 clean in a row": where the clean run that resolves a piece of feedback now
 * stands, in the words the card's own line uses ("None clean yet · 3 in a row resolve it"), so the
 * sentence and the card below it never count the same run two ways. The run's length stays a
 * figure, as the card's meter shows it. A change the wire sent without the run's length says only
 * that the run is empty.
 */
const backToClean = (event: ProfileEvent): string =>
	event.cleanNeeded === undefined
		? "back to no clean work in a row"
		: `back to none of ${event.cleanNeeded} clean in a row`;

/**
 * "after !425, !427 and !428 came back clean": the work that resolved a piece of feedback, linked.
 */
const cameBackClean = (event: ProfileEvent): FeedbackTextSegment[] =>
	event.evidence.length > 0
		? fold([text("after "), ...refs(event.evidence), text(" came back clean")])
		: [];

/**
 * A piece of feedback the work resolved says on what, one the developer resolved says when.
 */
function resolvedPredicate(event: ProfileEvent): FeedbackTextSegment[] {
	if (event.resolvedBy === "WORK") {
		return fold([text("resolved by the work "), ...cameBackClean(event)]);
	}
	if (event.resolvedBy === "DEVELOPER") {
		return [
			text(event.at ? `marked as addressed on ${formatDay(event.at)}` : "marked as addressed"),
		];
	}
	return [text("resolved")];
}

/** The words every surface says one kind of change in. */
interface Wording {
	/** What the subjects did, after their names and agreeing with how many there are. */
	predicate: (event: ChangeEvent, plural: boolean) => Piece;
	/** The same news where the row already names the subject. */
	alone: (event: ChangeEvent) => Piece;
	/** What leads into the work the event was seen on; absent where the predicate names it. */
	seenOn?: string;
}

// A standing or trend this build does not know reads as a phrase that fits the sentence, never as
// the enum value.
const standingOf = (event: ChangeEvent) => event.label ?? "a new standing";
const trendShown = (event: ChangeEvent) => event.label ?? "a new trend";

/**
 * A standing that moved says whose it is: the reader's, not the group's alone. A practice and its
 * group share the words, so a group that moved with its practices is said once with them.
 */
const MOVED = {
	predicate: (event, plural) => `${plural ? "are" : "is"} now at ${standingOf(event)} for you`,
	alone: (event) => `your standing is now ${standingOf(event)}`,
	seenOn: " after ",
} satisfies Wording;

const WORDING = {
	resolved: {
		predicate: resolvedPredicate,
		alone: (event) => [text("feedback "), ...resolvedPredicate(event)],
	},
	reset: {
		predicate: (event, plural) => `${plural ? "are" : "is"} ${backToClean(event)}`,
		alone: backToClean,
		seenOn: " after ",
	},
	down: MOVED,
	up: MOVED,
	first: {
		predicate: (_event, plural) => `${plural ? "were" : "was"} seen for the first time`,
		alone: () => "seen for the first time",
		seenOn: " on ",
	},
	trend: {
		predicate: (event, plural) => `now show${plural ? "" : "s"} ${trendShown(event)}`,
		alone: (event) => `now shows ${trendShown(event)}`,
		seenOn: " over ",
	},
} satisfies Record<ChangeKind, Wording>;

const wordingOf = (event: ChangeEvent): Wording => WORDING[event.kind];

const predicate = (event: ChangeEvent, plural = false): FeedbackTextSegment[] =>
	asSegments(wordingOf(event).predicate(event, plural));

/**
 * The event where the row already names the subject, without a full stop: the caller closes the
 * sentence.
 */
function aloneSentence(event: ChangeEvent): FeedbackTextSegment[] {
	const { alone, seenOn } = wordingOf(event);
	return sentence(alone(event), onWork(event, seenOn));
}

/** The overview's changes in one group, in the order they came. */
const changesIn = (overview: OverviewInput, groupSlug: string): ProfileChange[] =>
	overview.changes.filter((change) => change.groupSlug === groupSlug);

/** The slugs of every group the overview's changes mention, in order of appearance. */
const groupSlugsOf = (overview: OverviewInput): string[] => [
	...new Set(overview.changes.map((entry) => entry.groupSlug).filter((slug) => hasText(slug))),
];

/**
 * The transition an event records: its kind and the predicate it composes, word for word and
 * reference for reference. Two practices that moved the same way share one key, so the table can
 * say it once.
 */
const transitionKey = (event: ChangeEvent): string =>
	[event.kind, ...predicate(event).map(segmentKey)].join("\u0000");

function composeGroupSentences(changes: ProfileChange[]): FeedbackTextSegment[][] | undefined {
	const deduped = changeEventsOf(changes);
	if (deduped.length === 0) {
		return undefined;
	}
	return SENTENCE_ORDER.flatMap((kind) => {
		const ofKind = deduped.filter((candidate) => candidate.kind === kind);
		// The bullets in the order the events came, each holding the practices that share it.
		const bullets: GroupBullet[] = [];
		const shared = new Map<string, GroupBullet>();
		for (const [key, practices] of groupByTransition(
			ofKind.filter((candidate) => candidate.level === "practice"),
			transitionKey,
		)) {
			const bullet: GroupBullet = { practices };
			bullets.push(bullet);
			shared.set(key, bullet);
		}
		for (const event of ofKind.filter((candidate) => candidate.level === "group")) {
			const together = shared.get(transitionKey(event));
			if (together) {
				together.group = event;
			} else {
				bullets.push({ practices: [], group: event });
			}
		}
		return bullets.flatMap(groupBullet);
	});
}

/** One bullet of a group's cell: the practices that share a move, and the group if it made it. */
interface GroupBullet {
	practices: ChangeEvent[];
	group?: ChangeEvent;
}

/**
 * A bullet as its sentence: the practices' pills and what they did, with the group carried along
 * when it moved with them — "and the group with it" for one practice, "with them" for several.
 * A move only the group made is the group's own sentence.
 */
function groupBullet({ practices, group }: GroupBullet): FeedbackTextSegment[][] {
	const [lead] = practices;
	if (!lead) {
		return group ? [sentence(subjectInGroup(group), " ", predicate(group), ".")] : [];
	}
	return [
		sentence(
			practiceSegments(practices),
			" ",
			predicate(lead, practices.length > 1),
			group ? `, and the group with ${practices.length > 1 ? "them" : "it"}` : "",
			".",
		),
	];
}

/**
 * What moved per practice in a group, one sentence per bullet as the group table has them, keyed by
 * slug; the row names the practice, so the sentences do not.
 */
function composePracticeSentences(
	changes: ProfileChange[],
): Record<string, FeedbackTextSegment[][] | undefined> {
	const sentences: Record<string, FeedbackTextSegment[][] | undefined> = {};
	for (const event of changeEventsOf(changes)) {
		if (event.level !== "practice") {
			continue;
		}
		const own = sentence(aloneSentence(event), ".");
		sentences[event.slug] = [...(sentences[event.slug] ?? []), own];
	}
	return sentences;
}

/**
 * The subject as itself: a practice as its grey pill, a group as its own icon and colour. A group
 * written as words would read as a practice whose pill went missing.
 */
const subject = (event: ProfileEvent): FeedbackTextSegment =>
	event.level === "practice"
		? practiceSegment(event.slug, event.name)
		: groupSegment(event.slug, event.name);

/**
 * What changed, one sentence per subject, in the order the "All practice groups" table reads: the
 * weekly digest's list. The same events, precedence and words as the profile, so the profile and
 * the digest cannot tell one change two ways.
 */
export function composeChangeSentences(changes: ProfileChange[]): FeedbackTextSegment[][] {
	const events = changeEventsOf(changes);
	return SENTENCE_ORDER.flatMap((kind) =>
		events
			.filter((event) => event.kind === kind)
			.map((event) =>
				sentence(
					subject(event),
					" ",
					predicate(event),
					onWork(event, wordingOf(event).seenOn),
					".",
				),
			),
	);
}

/** What the page itself shows from one overview: the latest-run chip and the table's sentences. */
export function composeOverview(overview: OverviewInput): ComposedOverview {
	return {
		latestReview: overview.latestReview ?? undefined,
		groupSentences: Object.fromEntries(
			groupSlugsOf(overview).map((slug) => [
				slug,
				composeGroupSentences(changesIn(overview, slug)),
			]),
		),
	};
}

/**
 * What a group's level says: a sentence per practice in its table, from the changes in that group.
 */
export function composeGroupOverview(
	overview: OverviewInput,
	groupSlug: string,
): ComposedGroupOverview {
	return { practiceSentences: composePracticeSentences(changesIn(overview, groupSlug)) };
}

/** One segment's identity for a transition key: its words, or the work or practice it names. */
function segmentKey(segment: FeedbackTextSegment): string {
	if (segment.type === "text") {
		return segment.text;
	}
	return segment.type === "work" ? `work:${segment.ref.id}` : `${segment.type}:${segment.slug}`;
}
