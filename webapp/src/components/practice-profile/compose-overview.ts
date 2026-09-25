/**
 * The practice profile's sentences, composed from the overview the server reports: what held,
 * what changed and what the latest run looked at, for the Heph card on the page, the group level's
 * card, the practices table and the latest-run chip. Everything here is deterministic prose over
 * structured events; no model writes any of it.
 *
 * Each of these is a rule rather than a wording choice:
 *
 * 1. One event per practice per block, by a fixed precedence — resolved, fell back, new, slipped,
 *    moved up, first seen, trend, held. Holding and moving are not a contradiction, so the good
 *    block and the change block dedupe separately; holding and slipping are, so a practice that
 *    slipped, fell back or got new feedback is never also shown as holding.
 * 2. Counts read as words below ten and as digits from ten, and never both inside one clause.
 * 3. A bundled paragraph names at most a few subjects, then counts the rest in one sentence: three
 *    held rows, two attention rows and one slip in the paragraph, four per kind in the unfolded
 *    rest. Bad news is never a run of count sentences.
 * 3a. A negative event gets a row of its own only where the reader can act on it today: a piece of
 *    feedback the work fell back on, then new feedback, at most two rows, each with the card it
 *    belongs to one link away. A standing that slipped stays a sentence in the paragraph on
 *    purpose — a standing is a balance over several runs rather than a thing to do, and it owns no
 *    feedback card to send anyone to.
 * 4. Subjects that made the same move are one sentence, whichever surface says it: a column of
 *    sentences repeating one predicate reads as more news than there is. So every limit above
 *    counts subjects, not sentences, and the count that closes a paragraph is the subjects it
 *    left unnamed.
 * 5. Every sentence opens with a capital, unless it opens with a digit.
 * 6. A standing or a trend is named with the registry's own label, verbatim, so the prose and the
 *    badges say the same words.
 */
import type {
	HeldPractice,
	PracticeProfileOverview,
	ProfileChange,
	ReviewedWorkRef,
	ReviewRunRef,
} from "@/api/types.gen";
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
} from "@/components/common/feedback-text";
import { statusValues } from "@/components/common/status-def";
import { ATTENTION_DEFS } from "@/components/practice-vocabulary/attention-defs";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import type {
	AttentionPracticeRow,
	HeldPracticeRow,
	ReviewedWorkGroup,
} from "@/components/practice-vocabulary/HephFeedbackCard";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import {
	newestFirst,
	type PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { artifactKindRank } from "@/lib/artifact-kinds";
import { asDate, formatDay } from "@/lib/dates";
import { capitalise, hasText } from "@/lib/text";

/**
 * The overview with nothing in it: what the page composes before the first run. Its window has
 * no moments — an Invalid Date is what `asDate` reads as none.
 */
export const EMPTY_OVERVIEW: PracticeProfileOverview = {
	window: { since: new Date(Number.NaN), until: new Date(Number.NaN) },
	holdingUp: [],
	changes: [],
	reviewedWork: [],
};

/** One paragraph of the unfolded rest: the kind as its title, then one sentence per item. */
export interface RestParagraph {
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
	 * What moved in each group, for the practices table: one sentence per bullet, keyed by group
	 * slug. Practices that made the same move share a bullet.
	 */
	groupSentences: Record<string, FeedbackTextSegment[][] | undefined>;
	/**
	 * The card narrowed to each group the overview mentions, keyed by group slug; `groupOverviewOf`
	 * for any group.
	 */
	groups: Record<string, ComposedGroupOverview | undefined>;
}

/** What a group's level says: the page's card narrowed to that group. */
export interface ComposedGroupOverview {
	holdingUp: HeldPracticeRow[];
	holdingUpNote?: string;
	reviewedWork: ReviewedWorkGroup[];
	/** One sentence per practice for the group's table, keyed by practice slug. */
	practiceSentences: Record<string, FeedbackTextSegment[] | undefined>;
}

type EventKind = "resolved" | "reset" | "new" | "down" | "up" | "first" | "trend" | "held";
type EventLevel = "practice" | "group";

interface ProfileEvent {
	kind: EventKind;
	level: EventLevel;
	slug: string;
	name: string;
	groupSlug?: string;
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
/**
 * Rule 3a's cap. Two rows is what keeps the block a shortlist rather than the second list of
 * feedback the cards below already are; everything past it stays counted in the paragraph.
 */
const ATTENTION_ROW_LIMIT = 2;
const REST_NAMED_LIMIT = 4;
/** The table's reading order: what needs the reader first, then what it can be glad about. */
const SENTENCE_ORDER: readonly EventKind[] = [
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
] as const satisfies readonly EventKind[];
type RestKind = (typeof REST_KINDS)[number];
const REST_TITLES: Record<RestKind, string> = {
	reset: "Back to no clean work",
	new: "New feedback",
	down: "Moved down",
	up: "Moved up",
	first: "Seen for the first time",
	trend: "Trends turned",
};

/**
 * The settled standings in the registry's order, which is worst first; a standing no review has
 * settled has no rank, so a move in or out of one is not a slip.
 */
const STANDING_RANK = new Map<string, number>(
	statusValues(PRACTICE_GROUP_STANDING_DEFS)
		.filter(isSettledStanding)
		.map((standing, index) => [standing, index]),
);

const isVerdictSlip = (from: string | undefined, to: string | undefined) => {
	const before = from === undefined ? undefined : STANDING_RANK.get(from);
	const after = to === undefined ? undefined : STANDING_RANK.get(to);
	return before !== undefined && after !== undefined && after < before;
};

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
		groupSlug: held.groupSlug,
		evidence: [],
		at: held.since,
		holdsAs: held.holdsAs,
		cleanWork: held.cleanWork,
		workKind: held.workKind,
		workProvider: held.workProvider,
	};
}

function changeEvent(change: ProfileChange): ProfileEvent | undefined {
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
		groupSlug: change.groupSlug,
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
		case "STANDING_MOVED":
		case "GROUP_MOVED": {
			return {
				...base,
				kind: isVerdictSlip(change.from, change.to) ? "down" : "up",
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
function dedupe(events: ProfileEvent[]): ProfileEvent[] {
	const best = new Map<string, ProfileEvent>();
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

/**
 * The events that say the same thing collected together, in the order the first of each came: a
 * `Map` keeps insertion order, so a run of events that moved alike becomes one sentence where the
 * first of them stood. The one home for the rule that a practices table's bullet and the card's
 * paragraph both follow; what counts as the same thing is the key each of them passes.
 */
function groupByTransition(
	events: ProfileEvent[],
	keyOf: (event: ProfileEvent) => string,
): Map<string, [ProfileEvent, ...ProfileEvent[]]> {
	const groups = new Map<string, [ProfileEvent, ...ProfileEvent[]]>();
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
 * event says "the group" rather than repeating the name it sits under, which reads as a practice
 * whose pill went missing.
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

function asSegments(
	part: string | FeedbackTextSegment | FeedbackTextSegment[],
): FeedbackTextSegment[] {
	if (typeof part === "string") {
		return [text(part)];
	}
	return Array.isArray(part) ? part : [part];
}

const sentence = (...parts: (string | FeedbackTextSegment | FeedbackTextSegment[])[]) =>
	capitaliseSegments(fold(parts.flatMap(asSegments)));

/** Sentences joined by a space into one paragraph. */
const paragraph = (sentences: FeedbackTextSegment[][]) =>
	fold(sentences.flatMap((parts, index) => (index === 0 ? parts : [text(" "), ...parts])));

function eventsOf(overview: Pick<PracticeProfileOverview, "holdingUp" | "changes">) {
	return dedupe([
		...overview.holdingUp.map(heldEvent),
		...overview.changes.map(changeEvent).filter((event) => event !== undefined),
	]);
}

const byAtDesc = (a: ProfileEvent, b: ProfileEvent) =>
	(b.at?.getTime() ?? 0) - (a.at?.getTime() ?? 0);

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
 * Rule 3a's block: the negatives the reader can act on today, each with the card that says what to
 * do one link away. Feedback the work fell back on first, then new feedback, each newest first,
 * and at most {@link ATTENTION_ROW_LIMIT} rows. `named` is what the block took off the paragraph's
 * hands, so the paragraph counts the rest instead of saying it twice.
 */
function composeAttentionRows(deduped: ProfileEvent[]): {
	rows: AttentionPracticeRow[];
	named: ProfileEvent[];
} {
	// A row exists to open a card, so an event the wire sent without one has nothing to open and
	// stays in the paragraph.
	const withCard = (kind: EventKind) =>
		deduped.filter((event) => event.kind === kind && hasText(event.feedbackId)).sort(byAtDesc);
	const named = [...withCard("reset"), ...withCard("new")].slice(0, ATTENTION_ROW_LIMIT);
	return {
		named,
		rows: named.map((event) => ({
			feedbackId: event.feedbackId ?? "",
			practiceSlug: event.slug,
			practiceName: event.name,
			def: event.kind === "reset" ? ATTENTION_DEFS.reset : ATTENTION_DEFS.new,
			sentence: attentionSentence(event),
		})),
	};
}

/**
 * What the row says beside the pill, without the full stop the paragraph's sentences carry: the
 * row ends in a link, and a stop between the two would read as the link's own sentence starting.
 */
function attentionSentence(event: ProfileEvent): FeedbackTextSegment[] {
	return event.kind === "reset"
		? sentence(capitalise(backToClean(event)), seenOn(event, " after "))
		: sentence("There is new feedback", seenOn(event, ", seen on "));
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

/** The work the event was seen on, linked and led in by `joiner`; nothing when it names none. */
const seenOn = (event: ProfileEvent, joiner: string): FeedbackTextSegment[] =>
	event.evidence.length > 0 ? [text(joiner), ...refs(event.evidence)] : [];

/**
 * "Back to 0 of 3 clean": where the clean run that resolves a piece of feedback now stands. The
 * meter's own reading rather than prose, so rule 2 does not apply — the card below shows the same
 * two numbers as a meter, and spelling them out here would say something the meter does not.
 * A change the wire sent without the run's length says only that the run is empty.
 */
const backToClean = (event: ProfileEvent): string =>
	event.cleanNeeded === undefined
		? "back to no clean work"
		: `back to 0 of ${event.cleanNeeded} clean`;

/**
 * One sentence about the subjects that made one move, in that kind's own words: a practice is not
 * "moved" the first time it is seen, and new feedback is news about the subject rather than a
 * move it made. Several subjects that moved the same way are one sentence, so a paragraph says a
 * move once however many practices made it, and the predicate agrees with them.
 */
function movedSentence(
	kind: RestKind,
	events: [ProfileEvent, ...ProfileEvent[]],
): FeedbackTextSegment[] {
	const [lead] = events;
	const many = events.length > 1;
	switch (kind) {
		case "new": {
			return sentence(
				"There is new feedback on ",
				subjects(events),
				seenOn(lead, ", seen on "),
				".",
			);
		}
		case "reset": {
			return sentence(
				subjects(events),
				` ${many ? "are" : "is"} ${backToClean(lead)}`,
				seenOn(lead, " after "),
				".",
			);
		}
		case "first": {
			return sentence(
				subjects(events),
				` ${many ? "were" : "was"} seen for the first time`,
				seenOn(lead, " on "),
				".",
			);
		}
		case "trend": {
			return sentence(
				subjects(events),
				` now show${many ? "" : "s"} ${lead.label}`,
				seenOn(lead, " over "),
				".",
			);
		}
		case "down":
		case "up": {
			return sentence(
				subjects(events),
				` ${many ? "are" : "is"} now ${lead.label}`,
				seenOn(lead, " after "),
				".",
			);
		}
	}
}

/**
 * The paragraph on what moved: one slip named, then one sentence counting everything else, so the
 * bad news is never a run of counts. What the attention block took — feedback the work fell back
 * on, new feedback — is `inRows` and is not said twice; a standing that slipped stays here by rule
 * 3a. `hadRun` decides whether nothing moving is a sentence or silence.
 */
function composeChange(
	deduped: ProfileEvent[],
	hadRun: boolean,
	inRows: ProfileEvent[],
): { changed: FeedbackTextSegment[]; named: ProfileEvent[]; restCount: number } {
	const down = deduped.filter((event) => event.kind === "down");
	const rest = deduped.filter((event) => (REST_KINDS as readonly EventKind[]).includes(event.kind));
	if (rest.length === 0) {
		return {
			changed: hadRun ? sentence("Nothing moved since the latest run.") : [],
			named: [],
			restCount: 0,
		};
	}
	const named = [...inRows, ...down.slice(0, 1)];
	const sentences: FeedbackTextSegment[][] = [];
	const [slip] = down;
	if (slip) {
		sentences.push(
			sentence(subject(slip), ` moved to ${slip.label}`, seenOn(slip, " after "), "."),
		);
	}
	const hidden = rest.filter((event) => !named.includes(event));
	if (hidden.length > 0) {
		const counts = countByLevel(hidden);
		sentences.push(
			named.length > 0
				? sentence(
						`${counted(counts.practices, counts.groups, "more ")} changed as well; the practices table lists them.`,
					)
				: sentence(`${counted(counts.practices, counts.groups)} moved since the latest run.`),
		);
	}
	return { changed: paragraph(sentences), named, restCount: hidden.length };
}

/**
 * The sentence that closes a kind's paragraph when it named fewer than it counted, in that kind's
 * own words and agreeing with what it counted: a practice is not "moved" the first time it is seen,
 * new feedback is news about the subject rather than a move it made, and a trend turns. Only a
 * standing moves, so only a standing's paragraph says so.
 */
function restOverflow(kind: RestKind, hiddenSubjects: string, hidden: number): string {
	const many = hidden > 1;
	const lists = "the practices table lists them.";
	switch (kind) {
		case "new": {
			return `${hiddenSubjects} ${many ? "have" : "has"} new feedback; ${lists}`;
		}
		case "reset": {
			return `${hiddenSubjects} ${many ? "are" : "is"} back to no clean work; ${lists}`;
		}
		case "first": {
			return `${hiddenSubjects} ${many ? "were" : "was"} seen for the first time; ${lists}`;
		}
		case "trend": {
			return `${hiddenSubjects} saw ${many ? "their trends" : "its trend"} turn; ${lists}`;
		}
		case "down":
		case "up": {
			return `${hiddenSubjects} moved the same way; ${lists}`;
		}
	}
}

/**
 * What the paragraph only counted, unfolded by kind: at most four subjects named per kind, then a
 * count. The events the paragraph named are left out, so the unfolded rest is exactly what it
 * counted.
 */
function composeRest(deduped: ProfileEvent[], named: ProfileEvent[]): RestParagraph[] {
	return REST_KINDS.flatMap((kind) => {
		const items = deduped.filter((event) => event.kind === kind && !named.includes(event));
		if (items.length === 0) {
			return [];
		}
		// The limit counts the subjects the paragraph names, not the sentences it takes to name
		// them, so the count that closes the paragraph stays the number of subjects left over.
		const shown = items.slice(0, REST_NAMED_LIMIT);
		const sentences = [...groupByTransition(shown, movedKey).values()].map((together) =>
			movedSentence(kind, together),
		);
		const hidden = items.slice(REST_NAMED_LIMIT);
		if (hidden.length > 0) {
			const counts = countByLevel(hidden);
			sentences.push(
				sentence(
					restOverflow(kind, counted(counts.practices, counts.groups, "further "), hidden.length),
				),
			);
		}
		return [{ title: REST_TITLES[kind], segments: paragraph(sentences) }];
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

/**
 * "after !425, !427 and !428 came back clean": the work that resolved a piece of feedback, linked.
 */
const cameBackClean = (event: ProfileEvent): FeedbackTextSegment[] =>
	event.evidence.length > 0
		? fold([text("after "), ...refs(event.evidence), text(" came back clean")])
		: [];

/**
 * What happened to the subject, as the predicate of a sentence: a piece of feedback the work
 * resolved says on what, one the developer resolved says when, a move says where to, a first
 * sighting says so, a trend says what it shows. `plural` is for the bullet that names several
 * practices at once, which is the only place the subject is more than one thing.
 */
function predicate(event: ProfileEvent, plural = false): FeedbackTextSegment[] {
	switch (event.kind) {
		case "resolved": {
			return resolvedPredicate(event);
		}
		case "down":
		case "up": {
			return [text(`moved to ${event.label}`)];
		}
		case "first": {
			return [text(`${plural ? "were" : "was"} seen for the first time`)];
		}
		case "trend": {
			return [text(`now show${plural ? "" : "s"} ${event.label}`)];
		}
		case "held": {
			return [text("held")];
		}
		case "new": {
			return [text(`${plural ? "have" : "has"} new feedback`)];
		}
		case "reset": {
			return [text(`${plural ? "are" : "is"} ${backToClean(event)}`)];
		}
	}
}

/** The overview's changes in one group, in the order they came. */
const changesIn = (overview: PracticeProfileOverview, groupSlug: string): ProfileChange[] =>
	overview.changes.filter((change) => change.groupSlug === groupSlug);

/** The slugs of every group the overview mentions, held or changed, in order of appearance. */
const groupSlugsOf = (overview: PracticeProfileOverview): string[] => [
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
const transitionKey = (event: ProfileEvent): string =>
	[event.kind, ...predicate(event).map(segmentKey)].join("\u0000");

/**
 * The transition as the card's own sentences tell it, which is the table's bullet plus the two
 * things those sentences add: the work they link, and the level — a practice and a group are
 * counted apart, so they are never listed together either.
 */
const movedKey = (event: ProfileEvent): string =>
	[event.level, transitionKey(event), ...event.evidence.map((ref) => ref.id)].join("\u0000");

/**
 * What happened in the group since the latest run, for the practices table to list as bullets in
 * precedence order. Practices that made the same move are one bullet — their pills listed, then
 * what happened said once — because a column of bullets repeating one predicate reads as more
 * news than there is. The group's own move rides on that bullet as a tail when practices in it
 * made the same move, and is a bullet of its own only when none did; either way the row already
 * names the group, so the sentence says "the group". Nothing when nothing changed, so the row
 * shows only its badge.
 */
function composeGroupSentences(changes: ProfileChange[]): FeedbackTextSegment[][] | undefined {
	const deduped = dedupe(changes.map(changeEvent).filter((event) => event !== undefined));
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
	practices: ProfileEvent[];
	group?: ProfileEvent;
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
	const deduped = dedupe(changes.map(changeEvent).filter((event) => event !== undefined));
	const sentences: Record<string, FeedbackTextSegment[] | undefined> = {};
	for (const event of deduped) {
		if (event.level !== "practice") {
			continue;
		}
		const own = practiceSentence(event);
		const before = sentences[event.slug];
		sentences[event.slug] = before ? paragraph([before, own]) : own;
	}
	return sentences;
}

/** One event as a practice's own sentence: the row names the practice, so the sentence does not. */
function practiceSentence(event: ProfileEvent): FeedbackTextSegment[] {
	switch (event.kind) {
		case "new": {
			return sentence("There is new feedback", seenOn(event, ", seen on "), ".");
		}
		case "reset": {
			return sentence(capitalise(backToClean(event)), seenOn(event, " after "), ".");
		}
		case "resolved": {
			return sentence("Feedback ", predicate(event), ".");
		}
		case "first": {
			return sentence("Seen for the first time", seenOn(event, " on "), ".");
		}
		case "up":
		case "down":
		case "held":
		case "trend": {
			return sentence(predicate(event), seenOn(event, " after "), ".");
		}
	}
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

/** Everything the page shows from one overview, and each group's level from the same events. */
export function composeOverview(overview: PracticeProfileOverview): ComposedOverview {
	const deduped = eventsOf(overview);
	const held = composeHeldRows(deduped);
	const attention = composeAttentionRows(deduped);
	const { changed, named, restCount } = composeChange(
		deduped,
		overview.latestRun !== undefined,
		attention.named,
	);
	const reviewedWork = composeReviewedWork(overview.reviewedWork);
	const groupSlugs = groupSlugsOf(overview);
	return {
		latestRun: overview.latestRun,
		holdingUp: held.rows,
		holdingUpNote: held.note,
		needsAttention: attention.rows,
		changed,
		rest: composeRest(deduped, named),
		restCount,
		reviewedWork,
		groupSentences: Object.fromEntries(
			groupSlugs.map((slug) => [slug, composeGroupSentences(changesIn(overview, slug))]),
		),
		groups: Object.fromEntries(
			groupSlugs.map((slug) => [slug, composeGroup(overview, slug, reviewedWork)]),
		),
	};
}

/**
 * The page's card narrowed to one group: its held rows, the same reviewed work over the same
 * window, and a sentence per practice.
 */
function composeGroup(
	overview: PracticeProfileOverview,
	groupSlug: string,
	reviewedWork: ReviewedWorkGroup[],
): ComposedGroupOverview {
	const changes = changesIn(overview, groupSlug);
	const deduped = eventsOf({
		holdingUp: overview.holdingUp.filter((held) => held.groupSlug === groupSlug),
		changes,
	});
	const held = composeHeldRows(deduped);
	return {
		holdingUp: held.rows,
		holdingUpNote: held.note,
		reviewedWork,
		practiceSentences: composePracticeSentences(changes),
	};
}

/**
 * A group's level from the composed overview; a group it does not mention shows the reviewed
 * work alone.
 */
export function groupOverviewOf(
	composed: ComposedOverview,
	groupSlug: string,
): ComposedGroupOverview {
	return (
		composed.groups[groupSlug] ?? {
			holdingUp: [],
			reviewedWork: composed.reviewedWork,
			practiceSentences: {},
		}
	);
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

/** One segment's identity for a transition key: its words, or the work or practice it names. */
function segmentKey(segment: FeedbackTextSegment): string {
	if (segment.type === "text") {
		return segment.text;
	}
	return segment.type === "work" ? `work:${segment.ref.id}` : `${segment.type}:${segment.slug}`;
}
