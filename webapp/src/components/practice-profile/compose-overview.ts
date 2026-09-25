/**
 * The practice profile's sentences, composed from the overview the server reports: what held,
 * what changed and what the latest run looked at, for the Heph card on the page, the group level's
 * card, the "All practice groups" table and the latest-run chip. Everything here is deterministic
 * prose over structured events; no model writes any of it.
 *
 * Which event a practice is shown by, and when a held practice is dropped, is the precedence in
 * `docs/contributor/practice-review-glossary.mdx` § Practice profile overview; the good block
 * (held, resolved) and the change block apply it separately. How a count is written is
 * `feedback-text.ts`'s. The rest are rules rather than wording choices:
 *
 * 1. A bundled paragraph names at most a few subjects, then counts the rest in one sentence: three
 *    held rows, two attention rows and one slip in the paragraph, four per kind in the unfolded
 *    rest. Bad news is never a run of count sentences.
 * 2. A negative event gets a row of its own only where the reader can act on it today: a piece of
 *    feedback the work fell back on, then new feedback, at most two rows, each with the card it
 *    belongs to one link away. Two rows keep the block a shortlist rather than a second list of the
 *    cards below it. A standing that slipped stays a sentence in the paragraph on purpose — a
 *    standing is a balance over several runs rather than a thing to do, and it owns no feedback card
 *    to send anyone to.
 * 3. Subjects that made the same move are one sentence, whichever surface says it: a column of
 *    sentences repeating one predicate reads as more news than there is. So every limit above
 *    counts subjects, not sentences, and the count that closes a paragraph is the subjects it
 *    left unnamed.
 * 4. A standing or a trend is named with the registry's own label, verbatim, so the prose and the
 *    badges say the same words.
 */
import type {
	HeldPractice,
	PracticeProfileOverview,
	ProfileChange,
	ReviewedWorkRef,
	ReviewRunRef,
} from "@/api/types.gen";
import { ATTENTION_DEFS } from "@/components/practice-vocabulary/attention-defs";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import {
	countedWork,
	countsTogether,
	type FeedbackTextSegment,
	group as groupSegment,
	list,
	practice as practiceSegment,
	practices as practiceSegments,
	refs,
	text,
} from "@/components/practice-vocabulary/feedback-text";
import type {
	AttentionPracticeRow,
	HeldPracticeRow,
	ReviewedWorkGroup,
} from "@/components/practice-vocabulary/HephFeedbackCard";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { artifactKindRank } from "@/lib/artifact-kinds";
import { asDate, formatDay } from "@/lib/dates";
import { capitalise, hasText } from "@/lib/text";

import { newestFirst } from "./practice-feedback-cards";
import { ALL_PRACTICE_GROUPS } from "./practice-profile-search";

type OverviewInput = Pick<
	PracticeProfileOverview,
	"latestRun" | "holdingUp" | "changes" | "reviewedWork"
>;

/** The overview with nothing in it: what the page composes before the first run. */
export const EMPTY_OVERVIEW: OverviewInput = { holdingUp: [], changes: [], reviewedWork: [] };

/** One paragraph of the unfolded rest: the kind as its title, then one sentence per item. */
interface RestParagraph {
	title: string;
	segments: FeedbackTextSegment[];
}

export interface ComposedOverview {
	latestRun?: ReviewRunRef;
	/** Resolved feedback first, then the practices that held longest; at most three. */
	holdingUp: HeldPracticeRow[];
	/** "Another two practices held too." when the rows could not show every row they composed. */
	holdingUpNote?: string;
	/**
	 * What needs the developer next — feedback the work fell back on, then new feedback, at most
	 * two rows. Empty when the run raised nothing to act on, and then the block is not drawn.
	 */
	needsAttention: AttentionPracticeRow[];
	/** The paragraph on what moved since the latest run; empty when there was no run to compare. */
	changed: FeedbackTextSegment[];
	/** What the paragraph only counted, by kind; empty when it named everything. */
	rest: RestParagraph[];
	/** How many events `rest` unfolds: the count the paragraph's last sentence gives. */
	restCount: number;
	reviewedWork: ReviewedWorkGroup[];
	/**
	 * What moved in each group, for the "All practice groups" table: one sentence per bullet, keyed
	 * by group slug. Practices that made the same move share a bullet.
	 */
	groupSentences: Record<string, FeedbackTextSegment[][] | undefined>;
}

/** What a group's level says: the page's card narrowed to that group. */
interface ComposedGroupOverview {
	holdingUp: HeldPracticeRow[];
	holdingUpNote?: string;
	reviewedWork: ReviewedWorkGroup[];
	/** One sentence per practice for the group's table, keyed by practice slug. */
	practiceSentences: Record<string, FeedbackTextSegment[] | undefined>;
}

type ChangeKind = "resolved" | "reset" | "new" | "down" | "up" | "first" | "trend";
type EventKind = ChangeKind | "held";
type EventLevel = "practice" | "group";

interface ProfileEvent {
	kind: EventKind;
	level: EventLevel;
	slug: string;
	name: string;
	/** The registry's label for the standing or trend the subject moved to. */
	label?: string;
	/**
	 * For resolved feedback: the developer's work coming back clean, or the developer marking it
	 * addressed.
	 */
	resolvedBy?: ProfileChange["resolvedBy"];
	/** The piece of feedback the event is about, for the kinds that have one. */
	feedbackId?: string;
	/** For a fall back: the clean run the count fell back from, as the wire reports it. */
	cleanNeeded?: number;
	evidence: ReviewedWorkRef[];
	at?: Date;
	holdsAs?: string;
	cleanWork: number;
	workKind?: string;
	workProvider?: HeldPractice["workProvider"];
}

type ChangeEvent = ProfileEvent & { kind: ChangeKind };

const isChange = (event: ProfileEvent): event is ChangeEvent => event.kind !== "held";

const PRECEDENCE: readonly EventKind[] = [
	"resolved",
	"reset",
	"new",
	"down",
	"up",
	"first",
	"trend",
	"held",
];
const rank = (event: ProfileEvent) => PRECEDENCE.indexOf(event.kind);

const HELD_ROW_LIMIT = 3;
const ATTENTION_ROW_LIMIT = 2;
const REST_NAMED_LIMIT = 4;
/** The table's reading order: what needs the reader first, then what it can be glad about. */
const SENTENCE_ORDER: readonly ChangeKind[] = [
	"reset",
	"new",
	"resolved",
	"down",
	"up",
	"first",
	"trend",
];

/** The kinds the paragraph counts rather than names, in the order the unfolded rest lists them. */
const REST_KINDS = [
	"reset",
	"new",
	"down",
	"up",
	"first",
	"trend",
] as const satisfies readonly ChangeKind[];
const isRestKind = (event: ChangeEvent): boolean =>
	(REST_KINDS as readonly ChangeKind[]).includes(event.kind);

/** The registries' words, keyed by the enum values the wire carries. */
const STANDING_LABELS: Record<string, string | undefined> = Object.fromEntries(
	Object.entries(PRACTICE_GROUP_STANDING_DEFS).map(([value, def]) => [value, def.label]),
);
const TREND_LABELS: Record<string, string | undefined> = Object.fromEntries(
	Object.entries(PRACTICE_TREND_DEFS).map(([value, def]) => [value, def.label]),
);

/**
 * The registry's word for a standing or a trend; an enum value the build does not know is shown as
 * it came.
 */
const standingLabel = (value: string | undefined) =>
	value === undefined ? "" : (STANDING_LABELS[value] ?? value);
const trendLabel = (value: string | undefined) =>
	value === undefined ? "" : (TREND_LABELS[value] ?? value);

function heldEvent(held: HeldPractice): ProfileEvent {
	return {
		kind: "held",
		level: "practice",
		slug: held.practiceSlug,
		name: held.practiceName,
		evidence: [],
		at: held.since,
		holdsAs: held.holdsAs,
		cleanWork: held.cleanWork,
		workKind: held.workKind,
		workProvider: held.workProvider,
	};
}

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
		cleanWork: 0,
	} satisfies Partial<ProfileEvent>;
	switch (change.type) {
		case "FEEDBACK_NEW": {
			return { ...base, kind: "new", feedbackId: change.feedbackId };
		}
		case "FEEDBACK_RESET": {
			return {
				...base,
				kind: "reset",
				feedbackId: change.feedbackId,
				cleanNeeded: change.cleanNeeded,
			};
		}
		case "FEEDBACK_RESOLVED": {
			return { ...base, kind: "resolved", resolvedBy: change.resolvedBy };
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
		case "FIRST_OBSERVED": {
			return { ...base, kind: "first" };
		}
		case "TREND_TURNED": {
			return { ...base, kind: "trend", label: trendLabel(change.to) };
		}
	}
}

const subjectKey = (event: ProfileEvent) => `${event.level}:${event.slug}`;
const isGood = (event: ProfileEvent) => event.kind === "held" || event.kind === "resolved";
const isSlip = (event: ProfileEvent) =>
	event.kind === "down" || event.kind === "new" || event.kind === "reset";

/**
 * One event per subject within a block. The good block (held, resolved) and the change block
 * dedupe separately, so a practice may hold and have its trend turn; it may not hold and slip,
 * and it may not move up and down in the same run.
 */
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
 * The subject as itself: a practice as its grey pill, a group as its own icon and colour. A group
 * written as words would read as a practice whose pill went missing.
 */
const subject = (event: ProfileEvent): FeedbackTextSegment =>
	event.level === "practice"
		? practiceSegment(event.slug, event.name)
		: groupSegment(event.slug, event.name);

/**
 * The subjects of one move listed under the one rule every run of items here follows: "A",
 * "A and B", "A, B and C".
 */
const subjects = (events: ProfileEvent[]): FeedbackTextSegment[] =>
	list(events.map((event) => [subject(event)]));

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

type Part = string | FeedbackTextSegment | FeedbackTextSegment[];

function asSegments(part: Part): FeedbackTextSegment[] {
	if (typeof part === "string") {
		return [text(part)];
	}
	return Array.isArray(part) ? part : [part];
}

const sentence = (...parts: Part[]) => capitaliseSegments(fold(parts.flatMap(asSegments)));

/** Sentences joined by a space into one paragraph. */
const paragraph = (sentences: FeedbackTextSegment[][]) =>
	fold(sentences.flatMap((parts, index) => (index === 0 ? parts : [text(" "), ...parts])));

function eventsOf(overview: Pick<OverviewInput, "holdingUp" | "changes">) {
	return dedupe([
		...overview.holdingUp.map(heldEvent),
		...overview.changes.map(changeEvent).filter((event) => event !== undefined),
	]);
}

const byAtDesc = (a: ProfileEvent, b: ProfileEvent) =>
	(b.at?.getTime() ?? 0) - (a.at?.getTime() ?? 0);

/** The work the event was seen on, linked and led in by `joiner`; nothing when it names none. */
const onWork = (event: ProfileEvent, joiner: string | undefined): FeedbackTextSegment[] =>
	joiner !== undefined && event.evidence.length > 0 ? [text(joiner), ...refs(event.evidence)] : [];

/**
 * "Back to 0 of 3 clean": where the clean run that resolves a piece of feedback now stands. The
 * meter's own reading rather than prose, so the counting rule does not apply — the card below
 * shows the same two numbers as a meter, and spelling them out here would say something the meter
 * does not. A change the wire sent without the run's length says only that the run is empty.
 */
const backToClean = (event: ProfileEvent): string =>
	event.cleanNeeded === undefined
		? "back to no clean work"
		: `back to 0 of ${event.cleanNeeded} clean`;

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
	predicate: (event: ChangeEvent, plural: boolean) => Part;
	/** The same news where the row already names the subject. */
	alone: (event: ChangeEvent) => Part;
	/** What leads into the work the event was seen on; absent where the predicate names it. */
	seenOn?: string;
	/** For a kind the paragraph counts, the unfolded rest's title over it. */
	title?: string;
	/**
	 * For a kind the paragraph counts, what the subjects its rest only counted did. Several of them
	 * may have moved to different places, so it names none: only a standing moves, a trend turns,
	 * and the rest are news about the subject rather than a move it made.
	 */
	overflow?: (plural: boolean) => string;
}

const MOVED = {
	predicate: (event) => `moved to ${event.label}`,
	alone: (event) => `moved to ${event.label}`,
	seenOn: " after ",
	overflow: () => "moved the same way",
} satisfies Wording;

const WORDING = {
	resolved: {
		predicate: resolvedPredicate,
		alone: (event) => [text("feedback "), ...resolvedPredicate(event)],
	},
	reset: {
		title: "Back to no clean work",
		predicate: (event, plural) => `${plural ? "are" : "is"} ${backToClean(event)}`,
		alone: backToClean,
		seenOn: " after ",
		overflow: (plural) => `${plural ? "are" : "is"} back to no clean work`,
	},
	new: {
		title: "New feedback",
		predicate: (_event, plural) => `${plural ? "have" : "has"} new feedback`,
		alone: () => "there is new feedback",
		seenOn: ", seen on ",
		overflow: (plural) => `${plural ? "have" : "has"} new feedback`,
	},
	down: { title: "Moved down", ...MOVED },
	up: { title: "Moved up", ...MOVED },
	first: {
		title: "Seen for the first time",
		predicate: (_event, plural) => `${plural ? "were" : "was"} seen for the first time`,
		alone: () => "seen for the first time",
		seenOn: " on ",
		overflow: (plural) => `${plural ? "were" : "was"} seen for the first time`,
	},
	trend: {
		title: "Trends turned",
		predicate: (event, plural) => `now show${plural ? "" : "s"} ${event.label}`,
		alone: (event) => `now shows ${event.label}`,
		seenOn: " over ",
		overflow: (plural) => `saw ${plural ? "their trends" : "its trend"} turn`,
	},
} satisfies Record<ChangeKind, Wording>;

const wordingOf = (event: ChangeEvent): Wording => WORDING[event.kind];

const predicate = (event: ChangeEvent, plural = false): FeedbackTextSegment[] =>
	asSegments(wordingOf(event).predicate(event, plural));

/** Several subjects that made one move, as one sentence naming them all. */
function movedSentence(events: [ChangeEvent, ...ChangeEvent[]]): FeedbackTextSegment[] {
	const [lead] = events;
	return sentence(
		subjects(events),
		" ",
		predicate(lead, events.length > 1),
		onWork(lead, wordingOf(lead).seenOn),
		".",
	);
}

/**
 * The event where the row already names the subject, without a full stop: an attention row ends
 * in a link, and a stop between the two would read as the link's own sentence starting.
 */
function aloneSentence(event: ChangeEvent): FeedbackTextSegment[] {
	const { alone, seenOn } = wordingOf(event);
	return sentence(alone(event), onWork(event, seenOn));
}

/** Resolved feedback first, newest first, then the practices that held across the most work. */
function composeHeldRows(deduped: ProfileEvent[]): {
	rows: HeldPracticeRow[];
	note?: string;
} {
	const resolved = deduped.filter((event) => event.kind === "resolved").sort(byAtDesc);
	const held = deduped
		.filter((event) => event.kind === "held")
		.sort((a, b) => b.cleanWork - a.cleanWork || a.name.localeCompare(b.name));
	const rows = [
		...resolved.map<HeldPracticeRow>((event) => ({
			practiceSlug: event.slug,
			practiceName: event.name,
			statement: resolvedStatement(event.resolvedBy),
			resolved: true,
			note: resolvedNote(event),
		})),
		// The statement is the catalog's own sentence for the practice; a practice the catalog does
		// not ship has none, and the row says only what the work showed.
		...held.map<HeldPracticeRow>((event) => {
			const heldAcross =
				event.cleanWork > 0
					? `held across ${countedWork(event.workKind, event.cleanWork, event.workProvider)}`
					: "";
			return event.holdsAs === undefined
				? {
						practiceSlug: event.slug,
						practiceName: event.name,
						note: heldAcross ? sentence(heldAcross) : [],
					}
				: {
						practiceSlug: event.slug,
						practiceName: event.name,
						statement: capitalise(event.holdsAs),
						note: heldAcross ? [text(heldAcross)] : [],
					};
		}),
	];
	// The rows are one list under one limit, but the note counts what it hid by kind: resolved
	// feedback and a practice that held are different news, and a note naming the wrong one claims
	// something that did not happen.
	const resolvedShown = Math.min(resolved.length, HELD_ROW_LIMIT);
	const note = heldRowsNote(
		resolved.length - resolvedShown,
		Math.max(held.length - (HELD_ROW_LIMIT - resolvedShown), 0),
	);
	return { rows: rows.slice(0, HELD_ROW_LIMIT), note };
}

/**
 * The sentence under the rows: what the limit left out, counted by kind and said in one sentence.
 * Feedback is uncountable, so it is counted in pieces. The first count of one is named rather than
 * numbered — "Another practice held too.", never "another one practice".
 */
function heldRowsNote(resolvedHidden: number, heldHidden: number): string | undefined {
	const [feedbackClause, practiceClause] = countsTogether([
		{ n: resolvedHidden, one: "piece of feedback", many: "pieces of feedback" },
		{ n: heldHidden, one: "practice", many: "practices" },
	]);
	const clauses: string[] = [];
	if (resolvedHidden === 1) {
		clauses.push("piece of feedback resolved");
	} else if (resolvedHidden > 1 && feedbackClause !== undefined) {
		clauses.push(`${feedbackClause} resolved`);
	}
	if (heldHidden === 1 && clauses.length === 0) {
		clauses.push("practice held");
	} else if (heldHidden > 0 && practiceClause !== undefined) {
		clauses.push(`${practiceClause} held`);
	}
	return clauses.length === 0 ? undefined : capitalise(`another ${clauses.join(" and ")} too.`);
}

/**
 * Rule 2's block: the negatives the reader can act on today, each with the card that says what to
 * do one link away. Feedback the work fell back on first, then new feedback, each newest first,
 * and at most {@link ATTENTION_ROW_LIMIT} rows. `named` is what the block took off the paragraph's
 * hands, so the paragraph counts the rest instead of saying it twice.
 */
function composeAttentionRows(changes: ChangeEvent[]): {
	rows: AttentionPracticeRow[];
	named: ChangeEvent[];
} {
	// A row exists to open a card, so an event the wire sent without one has nothing to open and
	// stays in the paragraph.
	const withCard = (kind: ChangeKind) =>
		changes.filter((event) => event.kind === kind && hasText(event.feedbackId)).sort(byAtDesc);
	const named = [...withCard("reset"), ...withCard("new")].slice(0, ATTENTION_ROW_LIMIT);
	return {
		named,
		rows: named.map((event) => ({
			feedbackId: event.feedbackId ?? "",
			practiceSlug: event.slug,
			practiceName: event.name,
			def: event.kind === "reset" ? ATTENTION_DEFS.reset : ATTENTION_DEFS.new,
			sentence: aloneSentence(event),
		})),
	};
}

/**
 * Practices and groups counted in one clause, on the same rule for both: "three other practices and
 * one group".
 */
function counted(practices: number, groups: number, adjective = ""): string {
	const [practiceClause, groupClause] = countsTogether([
		{ n: practices, one: "practice", many: "practices" },
		{ n: groups, one: "group", many: "groups" },
	]);
	const parts = [
		...(practices > 0 && practiceClause !== undefined ? [practiceClause] : []),
		...(groups > 0 && groupClause !== undefined ? [groupClause] : []),
	];
	const [first, ...rest] = parts;
	if (first === undefined) {
		return "";
	}
	return [adjective ? first.replace(" ", ` ${adjective}`) : first, ...rest].join(" and ");
}

const countByLevel = (items: ProfileEvent[]) => ({
	practices: items.filter((event) => event.level === "practice").length,
	groups: items.filter((event) => event.level === "group").length,
});

/**
 * The paragraph on what moved: one slip named, then one sentence counting everything else, so the
 * bad news is never a run of counts. What the attention block took — feedback the work fell back
 * on, new feedback — is `inRows` and is not said twice; a standing that slipped stays here by rule
 * 2. The count names no kind — it covers every kind the unfolded rest lists under it — and
 * `hadRun` decides whether nothing moving is a sentence or silence.
 */
function composeChange(
	changes: ChangeEvent[],
	hadRun: boolean,
	inRows: ChangeEvent[],
): { changed: FeedbackTextSegment[]; named: ChangeEvent[]; restCount: number } {
	const rest = changes.filter(isRestKind);
	if (rest.length === 0) {
		return {
			changed: hadRun ? sentence("Nothing moved since the latest run.") : [],
			named: [],
			restCount: 0,
		};
	}
	const slip = changes.find((event) => event.kind === "down");
	const named = slip ? [...inRows, slip] : inRows;
	const sentences = slip ? [movedSentence([slip])] : [];
	const hidden = rest.filter((event) => !named.includes(event));
	if (hidden.length > 0) {
		const counts = countByLevel(hidden);
		sentences.push(
			named.length > 0
				? sentence(`${counted(counts.practices, counts.groups, "more ")} changed as well.`)
				: sentence(`${counted(counts.practices, counts.groups)} changed since the latest run.`),
		);
	}
	return { changed: paragraph(sentences), named, restCount: hidden.length };
}

/**
 * What the paragraph only counted, unfolded by kind: at most four subjects named per kind, then a
 * count that points at the table listing every one. The events the paragraph named are left out,
 * so the unfolded rest is exactly what it counted.
 */
function composeRest(changes: ChangeEvent[], named: ChangeEvent[]): RestParagraph[] {
	return REST_KINDS.flatMap((kind) => {
		const items = changes.filter((event) => event.kind === kind && !named.includes(event));
		if (items.length === 0) {
			return [];
		}
		// The limit counts the subjects the paragraph names, not the sentences it takes to name
		// them, so the count that closes the paragraph stays the number of subjects left over.
		const sentences = [
			...groupByTransition(items.slice(0, REST_NAMED_LIMIT), movedKey).values(),
		].map(movedSentence);
		const hidden = items.slice(REST_NAMED_LIMIT);
		const { title, overflow } = WORDING[kind];
		if (hidden.length > 0) {
			const counts = countByLevel(hidden);
			const plural = hidden.length > 1;
			sentences.push(
				sentence(
					`${counted(counts.practices, counts.groups, "further ")} ${overflow(plural)}; `,
					`the "${ALL_PRACTICE_GROUPS}" table lists ${plural ? "them" : "it"}.`,
				),
			);
		}
		return [{ title, segments: paragraph(sentences) }];
	});
}

/**
 * The work by kind and provider, in the registry's order of kinds and then by appearance; a kind
 * this build does not know follows them all.
 */
function composeReviewedWork(refsOnWire: ReviewedWorkRef[]): ReviewedWorkGroup[] {
	const groups: ReviewedWorkGroup[] = [];
	for (const ref of refsOnWire) {
		const group = groups.find(
			(candidate) => candidate.kind === ref.kind && candidate.provider === ref.provider,
		);
		if (group) {
			group.items.push(ref);
		} else {
			groups.push({ kind: ref.kind, provider: ref.provider, items: [ref] });
		}
	}
	return groups.sort((a, b) => artifactKindRank(a.kind) - artifactKindRank(b.kind));
}

/** The overview's changes in one group, in the order they came. */
const changesIn = (overview: OverviewInput, groupSlug: string): ProfileChange[] =>
	overview.changes.filter((change) => change.groupSlug === groupSlug);

/** The slugs of every group the overview mentions, held or changed, in order of appearance. */
const groupSlugsOf = (overview: OverviewInput): string[] => [
	...new Set(
		[...overview.holdingUp, ...overview.changes]
			.map((entry) => entry.groupSlug)
			.filter((slug) => hasText(slug)),
	),
];

/**
 * The transition an event records: its kind and the predicate it composes, word for word and
 * reference for reference. Two practices that moved the same way share one key, so the table can
 * say it once.
 */
const transitionKey = (event: ChangeEvent): string =>
	[event.kind, ...predicate(event).map(segmentKey)].join("\u0000");

/**
 * The transition as the card's own sentences tell it, which is the table's bullet plus the two
 * things those sentences add: the work they link, and the level — a practice and a group are
 * counted apart, so they are never listed together either.
 */
const movedKey = (event: ChangeEvent): string =>
	[event.level, transitionKey(event), ...event.evidence.map((ref) => ref.id)].join("\u0000");

/**
 * What happened in the group since the latest run, for the "All practice groups" table to list as
 * bullets in precedence order. Practices that made the same move are one bullet — their pills
 * listed, then what happened said once. The group's own move rides on that bullet as a tail when
 * practices in it made the same move, and is a bullet of its own only when none did; either way
 * the row already names the group, so the sentence says "the group". Nothing when nothing changed,
 * so the row shows only its badge.
 */
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
 * One sentence per practice in a group, keyed by slug; the row names the practice, so the
 * sentence does not.
 */
function composePracticeSentences(
	changes: ProfileChange[],
): Record<string, FeedbackTextSegment[] | undefined> {
	const sentences: Record<string, FeedbackTextSegment[] | undefined> = {};
	for (const event of changeEventsOf(changes)) {
		if (event.level !== "practice") {
			continue;
		}
		const own = sentence(aloneSentence(event), ".");
		const before = sentences[event.slug];
		sentences[event.slug] = before ? paragraph([before, own]) : own;
	}
	return sentences;
}

/**
 * The next step of the group's newest open card that has one, then which practice it belongs
 * to and the work it was seen on, by name: the group level's "Next step". The card's clean work
 * is not what it was seen on, so it is left out.
 */
export function composeNextStep(
	cards: PracticeFeedbackCardEntry[],
	groupSlug: string,
): FeedbackTextSegment[] | undefined {
	const card = newestFirst(cards).find(
		(candidate) =>
			candidate.group?.slug === groupSlug &&
			isOpenFeedback(candidate.state) &&
			hasText(candidate.nextStep),
	);
	if (!card) {
		return undefined;
	}
	const evidence = card.reviewedWork.map((work) => work.ref);
	const seen = evidence.length === 0 ? [] : [text(", seen on "), ...refs(evidence)];
	return sentence(
		`${card.nextStep} That is the open next step on `,
		practiceSegment(card.practiceSlug, card.practiceName),
		seen,
		".",
	);
}

/** Everything the page shows from one overview. */
export function composeOverview(overview: OverviewInput): ComposedOverview {
	const deduped = eventsOf(overview);
	const changes = deduped.filter(isChange);
	const held = composeHeldRows(deduped);
	const attention = composeAttentionRows(changes);
	const { changed, named, restCount } = composeChange(
		changes,
		overview.latestRun !== undefined,
		attention.named,
	);
	return {
		latestRun: overview.latestRun,
		holdingUp: held.rows,
		holdingUpNote: held.note,
		needsAttention: attention.rows,
		changed,
		rest: composeRest(changes, named),
		restCount,
		reviewedWork: composeReviewedWork(overview.reviewedWork),
		groupSentences: Object.fromEntries(
			groupSlugsOf(overview).map((slug) => [
				slug,
				composeGroupSentences(changesIn(overview, slug)),
			]),
		),
	};
}

/**
 * The page's card narrowed to one group: its held rows, the same reviewed work over the same
 * window, and a sentence per practice. A group the overview does not mention shows the reviewed
 * work alone.
 */
export function composeGroupOverview(
	overview: OverviewInput,
	groupSlug: string,
): ComposedGroupOverview {
	const changes = changesIn(overview, groupSlug);
	const held = composeHeldRows(
		eventsOf({
			holdingUp: overview.holdingUp.filter((entry) => entry.groupSlug === groupSlug),
			changes,
		}),
	);
	return {
		holdingUp: held.rows,
		holdingUpNote: held.note,
		reviewedWork: composeReviewedWork(overview.reviewedWork),
		practiceSentences: composePracticeSentences(changes),
	};
}

/** The glossary's two phrases for how feedback resolves, and no other. */
function resolvedStatement(resolvedBy: ProfileEvent["resolvedBy"]): string {
	if (resolvedBy === "WORK") {
		return "Resolved by the work";
	}
	return resolvedBy === "DEVELOPER" ? "Marked as addressed" : "Resolved";
}

/**
 * The evidence on a piece of feedback the developer resolved is the work it was seen on, not work
 * that met the next step, so the note names the day instead.
 */
function resolvedNote(event: ProfileEvent): FeedbackTextSegment[] {
	if (event.resolvedBy === "WORK") {
		return cameBackClean(event);
	}
	return event.resolvedBy === "DEVELOPER" && event.at ? [text(`on ${formatDay(event.at)}`)] : [];
}

/** One segment's identity for a transition key: its words, or the work or practice it names. */
function segmentKey(segment: FeedbackTextSegment): string {
	if (segment.type === "text") {
		return segment.text;
	}
	return segment.type === "work" ? `work:${segment.ref.id}` : `${segment.type}:${segment.slug}`;
}
