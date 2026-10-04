// ── Vocabularies shared with Java ────────────────────────────────────────────
// Each list mirrors an enum on the server. They are hand-maintained on both sides, so
// AgentVocabularySyncTest parses these literals and asserts equality with the Java enum's
// values(), preventing the runtime and persistence contracts from accepting different labels.
export const OUTCOME_VALUES = ["MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED"] as const;
export const SEVERITY_VALUES = ["CRITICAL", "MAJOR", "MINOR"] as const;
export const ANSWER_VALUES = ["YES", "NO", "UNDETERMINED"] as const;

// The vocabularies above are the values; these are the types every consumer spells them with. They are
// derived from the arrays rather than written twice, so the arrays stay the single thing Java is synced
// against and a value cannot be added to one without being added to the other.
export type Outcome = (typeof OUTCOME_VALUES)[number];
export type Severity = (typeof SEVERITY_VALUES)[number];
export type Answer = (typeof ANSWER_VALUES)[number];

/** Which side of a diff hunk a citation quotes; absent on every non-diff source. */
export type DiffSide = "OLD" | "NEW";

// ── The observation vocabulary, as shapes ────────────────────────────────────
// Everything below is what an observation looks like AFTER this module has checked it. pi-runner.ts
// hands the model's raw output in as `unknown` and gets one of these back, so these interfaces are the
// boundary between what a model claimed and what the server is willing to record.

/** One quote, and where it was taken from. Every field is present and checked by the time you see it. */
export interface NormalizedCitation {
	sourceKind: string;
	artifactPath: string;
	path: string;
	side?: DiffSide;
	revision?: string;
	startLine: number;
	endLine: number;
	quote: string;
	/**
	 * A few words of the first cited line, as the model wrote them: what finds the line when its number is
	 * off. The runner reads it while verifying the citation and never records it.
	 */
	anchor?: string;
}

/** The recorded scope of a search that came up empty — the warrant an answer resting on absence owes. */
export interface RecordedSearch {
	consulted: string[];
	lookedFor: string;
	boundary: string;
}

/** Another question's answer under which a question cannot change the outcome, so it is not asked. */
export interface SkipCondition {
	question: string;
	answer: "YES" | "NO";
}

/** One question of a practice, as the task-declared practice index stages it. */
export interface PracticeQuestion {
	key: string;
	title: string;
	question: string;
	yes: string;
	no: string;
	/** Any one of these, answered so, makes this question moot: it may be left unanswered. */
	skipWhen?: readonly SkipCondition[];
	/** Its answer can change only the severity: left unanswered, it is open, and the lower band holds. */
	gradesSeverityOnly?: true;
}

/** Whether these answers make the question moot, so it need not be answered. */
export function isSkipped(
	question: PracticeQuestion,
	answers: ReadonlyMap<string, string>,
): boolean {
	return (question.skipWhen ?? []).some((skip) => answers.get(skip.question) === skip.answer);
}

/** One answer, checked: the reviewer's answer, the fact that decides it, and the lines it rests on. */
export interface NormalizedAnswer {
	question: string;
	answer: Answer;
	because: string;
	citations: NormalizedCitation[];
	search?: RecordedSearch;
	wouldSettleIt?: string;
}

/**
 * One practice's answers, checked. This is what reaches result.json and admission; the server derives the
 * outcome and severity from the answers, so neither is part of what the reviewer records.
 */
export interface NormalizedObservation {
	practiceSlug: string;
	summary: string;
	answers: NormalizedAnswer[];
}

export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

export const ANSWER_DESCRIPTIONS: Record<Answer, string> = {
	YES: "The cited lines show what the question's YES describes.",
	NO: "The cited lines show what the question's NO describes. A NO that rests on something being absent carries a search: where you looked, for what, and what the search did not cover.",
	UNDETERMINED:
		"The captured evidence was read and genuinely leaves this question open. Name in wouldSettleIt the existing evidence that would decide it. Never for evidence you did not read.",
};

/** Require a model-facing description for each enum value. */
export function describeVocabulary<T extends string>(
	values: readonly T[],
	descriptions: Record<T, string>,
): string {
	return values
		.map((value) => {
			const description = descriptions[value];
			if (!description) {
				throw new Error(`vocabulary value '${value}' has no description`);
			}
			return `${value} — ${description}`;
		})
		.join("\n");
}

function nullish(value: unknown): boolean {
	return value == null;
}

function trimmedText(value: unknown): string {
	return typeof value === "string" ? value.trim() : "";
}

/**
 * The non-empty trimmed strings in a value the model sent as a list, and [] for anything that is not
 * one. Both warrants below name the sources they consulted this way, and neither may assume the model
 * sent an array of strings just because the tool schema asked for one.
 */
function trimmedStrings(value: unknown): string[] {
	return Array.isArray(value)
		? value.map((entry: unknown) => trimmedText(entry)).filter(Boolean)
		: [];
}

/** Requires the source list, named behavior and boundary of an absence claim. */
export function normalizeSearch(search: unknown): RecordedSearch {
	if (!isRecord(search)) {
		throw new Error("search must be an object with consulted, lookedFor and boundary");
	}
	const consulted = trimmedStrings(search.consulted);
	const lookedFor = trimmedText(search.lookedFor);
	const boundary = trimmedText(search.boundary);
	if (consulted.length === 0) {
		throw new Error("search.consulted must name at least one source you searched");
	}
	if (!lookedFor) {
		throw new Error("search.lookedFor is required");
	}
	if (!boundary) {
		throw new Error("search.boundary is required");
	}
	return { consulted: [...new Set(consulted)].toSorted(), lookedFor, boundary };
}

/**
 * How a citation is completed when it leaves out what the run already knows, and where the repairs made
 * on the way in are reported, one line each.
 */
export interface CitationRepairs {
	/** The source kind the manifest staged an artifact under; undefined when it staged no such artifact. */
	sourceOf?: (artifactPath: string) => string | undefined;
	/**
	 * Where a cited path that is no staged artifact was read: a line of the change, or a file of the
	 * checkout. Undefined when the run staged no source it could be.
	 */
	sourceFor?: (citation: { path: string; side: string | null }) => CitationSource | undefined;
	notes?: string[];
}

/** The staged artifact and source a citation is recorded against. */
export interface CitationSource {
	artifactPath: string;
	sourceKind: string;
}

/**
 * A line number as the session writes it: an integer, or the `[L<n>]`, `L<n>` or `"<n>"` it copied from
 * a view. NaN when it is none of these, so the range checks name what was received.
 */
function citedLine(value: unknown): number {
	if (typeof value === "number") {
		return value;
	}
	const written = typeof value === "string" ? /^\s*\[?L?(?<line>\d+)\]?\s*$/iu.exec(value) : null;
	return written?.groups?.line === undefined ? Number.NaN : Number(written.groups.line);
}

/**
 * What the manifest already knows is filled in, not asked for: the artifact a staged record is its own
 * path, and the source an artifact was staged by. Each fill is noted.
 */
function completedFromManifest(
	fields: Record<string, unknown>,
	path: string,
	{ sourceOf, sourceFor }: CitationRepairs,
): CitationSource {
	let artifactPath = typeof fields.artifactPath === "string" ? fields.artifactPath : "";
	let sourceKind = trimmedText(fields.sourceKind);
	// Naming the path is how a citation names its source: a staged record is its own artifact, and any other
	// path is a line of the change or a file of the checkout. Neither is a repair, so neither is echoed; an
	// artifact named that was never staged is read the same way rather than refused for a field not asked for.
	const named = artifactPath.trim() !== "" && sourceOf?.(artifactPath) !== undefined;
	if (!named && path.trim()) {
		if (sourceOf?.(path) === undefined) {
			const side = fields.side == null ? null : trimmedText(fields.side).toUpperCase();
			const read = sourceFor?.({ path, side });
			if (read !== undefined) {
				return read;
			}
		} else {
			artifactPath = path;
		}
	}
	const staged = artifactPath.trim() ? sourceOf?.(artifactPath) : undefined;
	if (!sourceKind && staged !== undefined) {
		sourceKind = staged;
	}
	return { artifactPath, sourceKind };
}

/** A checkout file named by its workspace path, as `ls` shows it, is the file at its path inside the checkout. */
function insideCheckout(
	path: string,
	sourceKind: string,
	artifactPath: string,
	noteRead: (read: string) => void,
): string {
	const checkout =
		sourceKind === "scm.repository.tree"
			? /^(?<root>repos\/[^/]+\/)\.git\/HEAD$/u.exec(artifactPath)?.groups?.root
			: undefined;
	if (checkout === undefined || !path.startsWith(checkout)) {
		return path;
	}
	const read = path.slice(checkout.length);
	noteRead(read);
	return read;
}

/** Every citation of one answer, completed from the manifest where it left something out, and checked. */
export function normalizeCitations(
	citations: unknown,
	repairs: CitationRepairs = {},
): NormalizedCitation[] {
	const { notes = [] } = repairs;
	if (!Array.isArray(citations) || citations.length === 0) {
		throw new Error("citations are required: the lines that decide this answer");
	}
	const many = citations.length > 1;
	const normalized = citations.map((citation: unknown, index): NormalizedCitation => {
		// With several citations, a problem names the one it is about.
		const which = many ? `citation ${index + 1}: ` : "";
		// A citation that is not an object reads as one with every field missing, which is what the
		// required-field checks below already reject by name.
		const fields: Record<string, unknown> = isRecord(citation) ? citation : {};
		const given = typeof fields.path === "string" ? fields.path : "";
		const { artifactPath, sourceKind } = completedFromManifest(fields, given, repairs);
		const path = insideCheckout(given, sourceKind, artifactPath, (read) => {
			notes.push(`${which}path ${given} read as ${read}, its path inside the checkout`);
		});
		const declaredSide = fields.side == null ? null : trimmedText(fields.side).toUpperCase();
		const revision = fields.revision == null ? null : trimmedText(fields.revision);
		// Whether a revision applies is settled once the source is (the caller drops it elsewhere); its
		// form is not the caller's to guess.
		if (revision !== null && !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/u.test(revision)) {
			throw new Error(
				`${which}revision must be a full commit SHA, received ${JSON.stringify(fields.revision)}`,
			);
		}
		const startLine = citedLine(fields.startLine);
		const endLine = nullish(fields.endLine) ? startLine : citedLine(fields.endLine);
		for (const [name, value, read] of [
			["startLine", fields.startLine, startLine],
			["endLine", fields.endLine, endLine],
		] as const) {
			if (typeof value === "string" && Number.isSafeInteger(read)) {
				notes.push(`${which}${name} ${JSON.stringify(value)} read as ${read}`);
			}
		}
		const quote = withoutCoordinates(
			typeof fields.quote === "string" ? fields.quote : "",
			startLine,
		);
		if (!sourceKind) {
			throw new Error(
				path.startsWith("work/")
					? `${which}${path} is a view derived here, not evidence: cite a changed line of the file itself (path and side, lines from diff.patch), or, for which files a commit changed, its "path" lines in commits.json`
					: `${which}${path || "an entry without a path"} is neither a staged record nor a file the change or the checkout holds: name the record (context/…) or the changed file as the brief shows it`,
			);
		}
		if (!artifactPath.trim()) {
			throw new Error(
				`${which}evidence citation artifactPath is required: the staged artifact the lines are in`,
			);
		}
		if (sourceKind === "scm.repository.tree" && !artifactPath.endsWith("/.git/HEAD")) {
			throw new Error(
				"repository citations must use the captured .git/HEAD artifact and a repository-relative path",
			);
		}
		if (!path.trim()) {
			throw new Error(`${which}evidence citation path is required`);
		}
		if (sourceKind === "scm.pull-request.diff" && /(?:^|\/)change\.json$/u.test(path)) {
			throw new Error(
				"change.json pins the reviewed range and is not quotable: quote a changed line from " +
					"work/change/diff.patch with its repository path and OLD/NEW side, or cite metadata.json " +
					"(scm.pull-request.core) for the pull request's own facts",
			);
		}
		if (
			sourceKind === "scm.pull-request.diff" &&
			declaredSide !== null &&
			declaredSide !== "OLD" &&
			declaredSide !== "NEW"
		) {
			throw new Error("diff evidence citation side must be OLD or NEW");
		}
		// A side on anything but a quote of the change says nothing; surplus, dropped rather than refused.
		// Number(undefined) is NaN and Number(null) is 0: an omitted line must be named as omitted, not
		// as a bad integer, or the session cannot tell which of the two it did.
		if (nullish(fields.startLine) || fields.startLine === "") {
			throw new Error(
				`${which}evidence citation startLine is required:` +
					" the 1-based line of the quoted text in the artifact " +
					"(for a quote of the change, the [L<n>] coordinate of work/change/diff.patch)",
			);
		}
		if (!Number.isSafeInteger(startLine) || startLine <= 0 || startLine > 2_147_483_647) {
			throw new Error(
				`${which}evidence citation startLine must be a positive integer, received ${JSON.stringify(fields.startLine)}; lines are 1-based`,
			);
		}
		if (!Number.isSafeInteger(endLine) || endLine < startLine || endLine > 2_147_483_647) {
			throw new Error(
				`${which}evidence citation endLine must be an integer >= startLine, received ${JSON.stringify(fields.endLine)} with startLine ${startLine}`,
			);
		}
		// An empty quote is a citation by coordinates alone; resolveQuote fills it from the artifact.
		const side: DiffSide | null =
			sourceKind === "scm.pull-request.diff" && (declaredSide === "OLD" || declaredSide === "NEW")
				? declaredSide
				: null;
		const anchor = trimmedText(fields.anchor);
		return {
			sourceKind,
			artifactPath,
			path,
			...(side == null ? {} : { side }),
			...(revision == null ? {} : { revision }),
			startLine,
			endLine,
			quote,
			...(anchor ? { anchor } : {}),
		};
	});
	return normalized;
}

export function normalizeUndecided(value: unknown): string | undefined {
	const text = trimmedText(value);
	return text || undefined;
}

/** The summary heads the developer's practice page; a phrase, not the rationale. Mirrored by admission. */
export const MAX_SUMMARY_CHARS = 160;

/** Shorten at a sentence boundary, or a clause boundary past half the limit; otherwise refuse. */
export function boundedAtSentenceEnd(text: string, max: number): string | undefined {
	if (text.length <= max) {
		return text;
	}
	const prefix = text.slice(0, max + 1);
	let end = -1;
	for (const match of prefix.matchAll(/[.!?](?=\s|$)/gu)) {
		end = match.index;
	}
	if (end >= 0) {
		return prefix.slice(0, end + 1).trim();
	}
	let clause = -1;
	for (const match of prefix.matchAll(/[,;:](?=\s)|\s[—–-](?=\s)/gu)) {
		clause = match.index;
	}
	return clause < max / 2 ? undefined : prefix.slice(0, clause).trim();
}

/** An answer's own fields: a remark is left out with a note, any other unknown field is a problem. */
function checkAnswerFields(
	at: string,
	raw: Record<string, unknown>,
	repairs: CitationRepairs,
	problems: string[],
): void {
	const remarks = Object.keys(raw).filter((field) => isRemark(field, raw[field]));
	if (remarks.length > 0) {
		repairs.notes?.push(
			`${at}: ${remarks.join(", ")} not recorded: an answer has only cites, because, answer, search and wouldSettleIt`,
		);
	}
	const unknown = Object.keys(raw).filter(
		(field) => !ANSWER_FIELDS.has(field) && !remarks.includes(field),
	);
	if (unknown.length > 0) {
		problems.push(
			`${at} has unknown field(s) ${unknown.join(", ")}; an answer has only cites, because, answer, search and wouldSettleIt`,
		);
	}
}

/**
 * An observation's own fields: a remark is left out with a note; an outcome, severity or rationale is refused
 * by name, since the server derives them; any other unknown field is refused.
 */
function checkObservationFields(
	observation: Record<string, unknown>,
	allowed: ReadonlySet<string>,
	notes: string[],
): void {
	const derived = new Set(["outcome", "severity", "evidenceRationale"]);
	const remarks = Object.keys(observation).filter((key) => isRemark(key, observation[key]));
	if (remarks.length > 0) {
		notes.push(
			`${remarks.join(", ")} not recorded: an observation has only practiceSlug, scan, evidence, answers and summary`,
		);
	}
	const unknownFields = Object.keys(observation).filter(
		(key) => !allowed.has(key) && !remarks.includes(key),
	);
	if (unknownFields.length > 0) {
		const stated = unknownFields.filter((key) => derived.has(key));
		const other = unknownFields.filter((key) => !derived.has(key));
		throw new Error(
			[
				...(stated.length > 0
					? [
							`${stated.join(", ")} is not recorded: answer every question in answers, and Hephaestus derives the outcome and severity from the answers`,
						]
					: []),
				...(other.length > 0
					? [
							`unknown observation field(s): ${other.join(", ")}; an observation has only practiceSlug, scan, evidence, answers and summary`,
						]
					: []),
			].join("; also: "),
		);
	}
}

/**
 * A text field named as a note, comment or remark (`note`, `evidence_note`) decides nothing: it is left out
 * with a note rather than refused. Any other unknown field is refused, since it may state a judgment or carry
 * what belongs in a field of the contract.
 */
function isRemark(field: string, value: unknown): boolean {
	return typeof value === "string" && /(?:^|_)(?:note|comment|remark)s?$/iu.test(field);
}

/** The fields one answer has. */
const ANSWER_FIELDS = new Set([
	"question",
	"cites",
	"because",
	"answer",
	"citations",
	"search",
	"wouldSettleIt",
]);

/** An evidence entry that names a derived list of the change's files: not recorded, since the change's lines show what it lists. */
const FILE_LIST = Symbol("a derived list of the change's files");

/** The views derived here that only list the change's files. */
const DERIVED_FILE_LISTS = new Set(["work/change/files.json", "work/change/diff_stat.txt"]);

const FILE_LIST_REFUSAL =
	'a list of the change\'s files derived here, which is not evidence: cite a changed line of the file itself (path and side, lines from diff.patch), or, for which files a commit changed, its "path" lines in commits.json';

/** An observation's evidence list by entry number; an entry naming a derived list of files holds its place. */
type Evidence = readonly (NormalizedCitation | typeof FILE_LIST)[];

/**
 * The evidence list. An entry that names a derived list of the change's files is the commonest wrong entry
 * and has one reading: the answers rest on the change, which their other entries cite. It is left out with a
 * note rather than refused; an answer that cites nothing else, or a list with nothing else, is refused, saying
 * what to cite.
 */
function normalizeEvidence(sent: unknown, repairs: CitationRepairs): Evidence {
	if (!Array.isArray(sent)) {
		return normalizeCitations(sent, repairs);
	}
	const lists = sent.flatMap((entry: unknown, index) =>
		isRecord(entry) && typeof entry.path === "string" && DERIVED_FILE_LISTS.has(entry.path.trim())
			? [index]
			: [],
	);
	// With nothing else listed there is nothing to rest on: the list itself is refused, once.
	if (lists.length === 0 || lists.length === sent.length) {
		return normalizeCitations(sent, repairs);
	}
	repairs.notes?.push(
		`entries ${lists.map((index) => index + 1).join(", ")} name a list of the change's files derived here, so they are not recorded; the change's own lines show what it lists`,
	);
	return sent.map((entry: unknown, index) => {
		if (lists.includes(index)) {
			return FILE_LIST;
		}
		const entryNotes: string[] = [];
		try {
			const [citation] = normalizeCitations([entry], { ...repairs, notes: entryNotes });
			repairs.notes?.push(...entryNotes.map((note) => `citation ${index + 1}: ${note}`));
			if (citation === undefined) {
				throw new Error("evidence citation is required");
			}
			return citation;
		} catch (error) {
			throw new Error(
				`citation ${index + 1}: ${error instanceof Error ? error.message : String(error)}`,
				{
					cause: error,
				},
			);
		}
	});
}

/**
 * The evidence entries an answer cites, as the numbers the model wrote — 1, "1", "[1]" or "E1" — each
 * checked against the observation's evidence list.
 */
function citedEntries(value: unknown, evidence: number): { cited: number[] } | { problem: string } {
	let sent: unknown[] = [];
	if (Array.isArray(value)) {
		sent = value;
	} else if (typeof value === "string") {
		// "1, 2" or "[1] [3]": every number the text names.
		sent = value.split(/[\s,;]+/u).filter(Boolean);
	} else if (!nullish(value)) {
		sent = [value];
	}
	const cited: number[] = [];
	for (const entry of sent) {
		const written =
			typeof entry === "number"
				? entry
				: Number(/^\s*\[?E?(?<n>\d+)\]?\s*$/iu.exec(String(entry))?.groups?.n ?? Number.NaN);
		if (!Number.isInteger(written) || written < 1 || written > evidence) {
			return {
				problem:
					evidence === 0
						? "cites an evidence entry, but the observation lists no evidence"
						: `cites ${JSON.stringify(entry)}, but the evidence entries are numbered 1 to ${evidence}`,
			};
		}
		if (!cited.includes(written)) {
			cited.push(written);
		}
	}
	return { cited };
}
/** The most an answer's reason may say: one sentence naming the deciding fact, not a rationale. */
export const MAX_BECAUSE_CHARS = 600;
/** The most what would settle an open answer may say: the evidence, named, not an explanation. */
export const MAX_WOULD_SETTLE_IT_CHARS = 600;

/** The answers as sent — keyed by question, or a list naming each question — as one list. */
function sentAnswers(value: unknown, notes: string[]): Map<string, unknown> {
	const sent = new Map<string, unknown>();
	if (Array.isArray(value)) {
		for (const item of value) {
			const key = isRecord(item) ? trimmedText(item.question) : "";
			if (!key) {
				throw new Error("each answer in a list names its question in `question`");
			}
			if (sent.has(key)) {
				throw new Error(`question '${key}' is answered twice; send one answer per question`);
			}
			sent.set(key, item);
		}
		notes.push("answers read from a list; nothing to resend");
		return sent;
	}
	if (!isRecord(value)) {
		throw new Error("answers is required: an object with one answer per question of the practice");
	}
	for (const [key, item] of Object.entries(value)) {
		sent.set(key, isRecord(item) ? { ...item, question: key } : item);
	}
	return sent;
}

/**
 * An answer object left open takes the next answer inside it: a question of the practice nested in another
 * answer, and not answered at its own place, is read as its own answer, with a note.
 */
function hoistNestedAnswers(
	sentByKey: Map<string, unknown>,
	known: ReadonlySet<string>,
	notes: string[],
): void {
	// Read before any is moved, since moving one adds an answer at its own place.
	const nestedIn = [...sentByKey].flatMap(([key, sent]) =>
		isRecord(sent)
			? Object.keys(sent)
					.filter(
						(inner) =>
							inner !== key && known.has(inner) && !sentByKey.has(inner) && isRecord(sent[inner]),
					)
					.map((inner) => ({ key, sent, inner }))
			: [],
	);
	for (const { key, sent, inner } of nestedIn) {
		const answer = sent[inner];
		const outer = sentByKey.get(key);
		sentByKey.set(inner, isRecord(answer) ? { ...answer, question: inner } : answer);
		if (isRecord(outer)) {
			const { [inner]: _moved, ...rest } = outer;
			sentByKey.set(key, rest);
		}
		notes.push(`answers.${key}.${inner} read as answers.${inner}; nothing to resend`);
	}
}

function normalizeAnswer(
	key: string,
	raw: unknown,
	evidence: Evidence | null,
	used: Set<number>,
	repairs: CitationRepairs,
	problems: string[],
): NormalizedAnswer | undefined {
	const at = `answers.${key}`;
	if (!isRecord(raw)) {
		problems.push(`${at} must be an object with cites, because and answer`);
		return undefined;
	}
	checkAnswerFields(at, raw, repairs, problems);
	const answer = ANSWER_VALUES.find((value) => value === trimmedText(raw.answer).toUpperCase());
	if (answer === undefined) {
		problems.push(`${at}.answer must be one of ${ANSWER_VALUES.join(", ")}`);
	}
	const sentBecause = trimmedText(raw.because).replaceAll(/\s+/gu, " ");
	// Too long, it keeps the sentences that fit: the deciding fact comes first, and a resend costs a whole call.
	const because = boundedAtSentenceEnd(sentBecause, MAX_BECAUSE_CHARS);
	if (!sentBecause) {
		problems.push(
			`${at}.because is required: one sentence naming the fact in the cited lines that decides it`,
		);
	} else if (because === undefined) {
		problems.push(
			`${at}.because must be at most ${MAX_BECAUSE_CHARS} characters; name the deciding fact only`,
		);
	} else if (because !== sentBecause) {
		repairs.notes?.push(
			`${at}.because kept to its sentences within ${MAX_BECAUSE_CHARS} characters`,
		);
	}
	let citations: NormalizedCitation[] | undefined;
	// Evidence that could not be read is refused once, above; what an answer cites of it is not checked again.
	const cites = evidence === null ? { cited: [] } : citedEntries(raw.cites, evidence.length);
	if ("problem" in cites) {
		problems.push(`${at} ${cites.problem}`);
	} else if (evidence !== null) {
		citations = cites.cited.flatMap((entry) => {
			used.add(entry);
			const cited = evidence[entry - 1];
			return cited === undefined || cited === FILE_LIST ? [] : [{ ...cited }];
		});
		if (citations.length === 0 && cites.cited.some((entry) => evidence[entry - 1] === FILE_LIST)) {
			problems.push(`${at} cites only ${FILE_LIST_REFUSAL}`);
			citations = undefined;
		}
	}
	// Citations written into the answer itself, as an earlier contract had them, are read as its own.
	if (!nullish(raw.citations)) {
		const citationNotes: string[] = [];
		try {
			citations = [
				...(citations ?? []),
				...normalizeCitations(raw.citations, { ...repairs, notes: citationNotes }),
			];
			repairs.notes?.push(
				`${at}: citations read from inside the answer; list them once under evidence instead`,
			);
		} catch (error) {
			problems.push(`${at}: ${error instanceof Error ? error.message : String(error)}`);
		}
		repairs.notes?.push(...citationNotes.map((note) => `${at}: ${note}`));
	}
	if (citations !== undefined && citations.length === 0 && evidence !== null) {
		problems.push(
			`${at}.cites is required: the numbers of the evidence entries that decide it, e.g. [1] for the first`,
		);
		citations = undefined;
	}
	let search: RecordedSearch | undefined;
	if (!nullish(raw.search)) {
		try {
			search = normalizeSearch(raw.search);
		} catch (error) {
			problems.push(`${at}: ${error instanceof Error ? error.message : String(error)}`);
		}
	}
	const wouldSettleIt = normalizeUndecided(raw.wouldSettleIt);
	if (answer === "UNDETERMINED" && wouldSettleIt === undefined) {
		problems.push(
			`${at} is UNDETERMINED and needs wouldSettleIt: the existing evidence that would decide it, e.g. 'the body of issue #7'`,
		);
	}
	if (wouldSettleIt !== undefined && wouldSettleIt.length > MAX_WOULD_SETTLE_IT_CHARS) {
		problems.push(
			`${at}.wouldSettleIt must be at most ${MAX_WOULD_SETTLE_IT_CHARS} characters; name the evidence only`,
		);
	}
	if (answer !== undefined && answer !== "UNDETERMINED" && wouldSettleIt !== undefined) {
		problems.push(
			`${at}.wouldSettleIt is only for an UNDETERMINED answer; remove it or answer UNDETERMINED`,
		);
	}
	if (answer === undefined || because === undefined || because === "" || citations === undefined) {
		return undefined;
	}
	return {
		question: key,
		answer,
		because,
		citations,
		...(search === undefined ? {} : { search }),
		...(wouldSettleIt === undefined || answer !== "UNDETERMINED" ? {} : { wouldSettleIt }),
	};
}

/**
 * Validate one practice's answers before recording them.
 * @param questionsOf the questions of a practice admitted to this review, by slug; undefined for any other
 * @param notes receives one line per correction made on the way in — a value filled in from the manifest,
 *   a list read as answers — so the caller can echo what was recorded.
 */
export function normalizeObservation(
	raw: unknown,
	questionsOf: (practiceSlug: string) => readonly PracticeQuestion[] | undefined,
	notes: string[] = [],
	sourceOf?: (artifactPath: string) => string | undefined,
	sourceFor?: CitationRepairs["sourceFor"],
): NormalizedObservation {
	if (!isRecord(raw)) {
		throw new Error("observation must be an object");
	}
	// The scan is the model's working before it answers: read, never recorded.
	const allowed = new Set(["practiceSlug", "scan", "summary", "evidence", "answers"]);
	// A stray key with no value (`practiceSlug2: null`) carries nothing: dropped, and said so.
	const observation = { ...raw };
	const empty = Object.keys(observation).filter(
		(key) => !allowed.has(key) && (nullish(observation[key]) || observation[key] === ""),
	);
	for (const key of empty) {
		Reflect.deleteProperty(observation, key);
	}
	if (empty.length > 0) {
		notes.push(`empty field(s) ${empty.join(", ")} dropped`);
	}
	// Answers sent beside summary instead of inside answers: each is named by one of this practice's
	// questions, so where it belongs is not in doubt. One already inside answers stays where it is and the
	// stray copy is refused below, because which of the two was meant is.
	const questionKeys = new Set(
		questionsOf(normalizePracticeSlug(observation.practiceSlug))?.map((question) => question.key),
	);
	const nested = isRecord(observation.answers) ? { ...observation.answers } : undefined;
	const beside = Object.keys(observation).filter(
		(key) =>
			questionKeys.has(key) &&
			(nested === undefined ? observation.answers === undefined : !(key in nested)),
	);
	if (beside.length > 0) {
		const answers: Record<string, unknown> = nested ?? {};
		for (const key of beside) {
			answers[key] = observation[key];
			Reflect.deleteProperty(observation, key);
		}
		observation.answers = answers;
		notes.push(
			`answers ${beside.join(", ")} read from beside summary, into answers; nothing to resend`,
		);
	}
	checkObservationFields(observation, allowed, notes);
	const practiceSlug = normalizePracticeSlug(observation.practiceSlug);
	if (!practiceSlug) {
		throw new Error(
			`practiceSlug is required: each item of observations is one practice's answers (received keys: ${Object.keys(observation).join(", ") || "none"})`,
		);
	}
	const questions = questionsOf(practiceSlug);
	if (questions === undefined) {
		throw new Error(`practice '${practiceSlug}' is not one of this review's practices`);
	}
	const sent = trimmedText(observation.summary).replaceAll(/\s+/gu, " ");
	// Every problem of the observation is named at once: one per resend costs a model call each.
	const problems: string[] = [];
	if (!sent) {
		problems.push("summary is required");
	} else if (!/\S\s+\S/u.test(sent)) {
		// The summary is what the developer reads on their practice page, above the practice's own name
		// and with no evidence beside it, so a single word there ("Test") names nothing the practice did not.
		problems.push(
			"summary must say what was observed as a short phrase, not one word — e.g. " +
				"'Debug print left in the request handler'",
		);
	} else if (sent.length > MAX_SUMMARY_CHARS) {
		// Refused, never shortened: a cut the runner chooses changes what the observation says.
		problems.push(
			`summary must be at most ${MAX_SUMMARY_CHARS} characters; this one is ${sent.length}. Resend it ` +
				"as a complete phrase that names the behavior; the reasons belong in each answer's because",
		);
	}
	let sentByKey = new Map<string, unknown>();
	let answersUnreadable = false;
	try {
		sentByKey = sentAnswers(observation.answers, notes);
	} catch (error) {
		answersUnreadable = true;
		problems.push(error instanceof Error ? error.message : String(error));
	}
	const known = new Set(questions.map((question) => question.key));
	hoistNestedAnswers(sentByKey, known, notes);
	const foreign = [...sentByKey.keys()].filter((key) => !known.has(key));
	const misplaced = foreign.filter((key) => ANSWER_FIELDS.has(key));
	if (misplaced.length > 0) {
		problems.push(
			`answers has ${misplaced.join(", ")} beside the questions; it belongs inside one answer, e.g. answers.${questions[0]?.key ?? "<question>"}.${misplaced[0] ?? "search"}`,
		);
	} else if (foreign.length > 0) {
		problems.push(
			`answers has question(s) ${foreign.join(", ")} that '${practiceSlug}' does not ask; its questions are ${questions.map((question) => question.key).join(", ")}`,
		);
	}
	// The evidence list: every line the answers rest on, once, cited by its number.
	let evidence: Evidence | null = [];
	if (!nullish(observation.evidence)) {
		const evidenceNotes: string[] = [];
		try {
			evidence = normalizeEvidence(observation.evidence, {
				sourceOf,
				sourceFor,
				notes: evidenceNotes,
			});
		} catch (error) {
			evidence = null;
			problems.push(
				`evidence: ${(error instanceof Error ? error.message : String(error)).replace(/^citation (?=\d)/u, "entry ")}`,
			);
		}
		notes.push(
			...evidenceNotes.map((note) => `evidence: ${note.replace(/^citation (?=\d)/u, "entry ")}`),
		);
	}
	const given = new Map<string, string>();
	for (const [key, sentAnswer] of sentByKey) {
		if (isRecord(sentAnswer)) {
			given.set(key, trimmedText(sentAnswer.answer).toUpperCase());
		}
	}
	const unanswered = questions.filter(
		(question) => !sentByKey.has(question.key) && !isSkipped(question, given),
	);
	const leftOpen = unanswered.filter((question) => question.gradesSeverityOnly === true);
	if (leftOpen.length > 0 && !answersUnreadable) {
		notes.push(
			`${leftOpen.map((question) => question.key).join(", ")} left out, so read as open: it only grades severity, and the lower band holds`,
		);
	}
	const missing = unanswered.filter((question) => question.gradesSeverityOnly !== true);
	if (missing.length > 0 && !answersUnreadable) {
		problems.push(
			`answer every question of '${practiceSlug}' that its answers do not skip; missing: ${missing.map((question) => `${question.key} (${question.title})`).join(", ")}`,
		);
	}
	const answers: NormalizedAnswer[] = [];
	const used = new Set<number>();
	for (const question of questions) {
		if (!sentByKey.has(question.key)) {
			continue;
		}
		const answer = normalizeAnswer(
			question.key,
			sentByKey.get(question.key),
			evidence,
			used,
			{ sourceOf, sourceFor, notes },
			problems,
		);
		if (answer !== undefined) {
			answers.push(answer);
		}
	}
	const uncited = (evidence ?? []).flatMap((entry, index) =>
		entry === FILE_LIST || used.has(index + 1) ? [] : [index + 1],
	);
	if (uncited.length > 0 && problems.length === 0) {
		notes.push(`evidence ${uncited.join(", ")} cited by no answer, so not recorded`);
	}
	if (problems.length > 0) {
		throw new Error(problems.join("; also: "));
	}
	return { practiceSlug, summary: sent, answers };
}

export function normalizePracticeSlug(value: unknown): string {
	return trimmedText(value).toLowerCase().replaceAll("_", "-");
}

/** Every citation of an observation, across its answers. */
export function citationsOf(observation: NormalizedObservation): NormalizedCitation[] {
	return observation.answers.flatMap((answer) => answer.citations);
}

/** Requires each citation to name an artifact staged by its declared source. */
export function validateEvidenceSources(
	observation: NormalizedObservation,
	availableSourceKinds: ReadonlySet<string>,
	artifactSources: ReadonlyMap<string, string> = new Map(),
): void {
	for (const citation of citationsOf(observation)) {
		const { sourceKind } = citation;
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`evidence source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`the task-declared manifest: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
		const artifactSource = artifactSources.get(citation.artifactPath);
		if (artifactSource !== sourceKind) {
			if (artifactSource === undefined) {
				// The change view under work/ is derived in the container from the checkout, so a quote
				// of it is a quote of the change: the pinned range is the artifact, the diff its lines.
				const derived = citation.artifactPath.startsWith("work/")
					? " The change view under work/ is derived here and is not an artifact: quote a changed line " +
						"from work/change/diff.patch as scm.pull-request.diff with the pinned change.json as " +
						"artifactPath, or the pull request record (metadata.json) as scm.pull-request.core."
					: "";
				throw new Error(
					`artifact '${citation.artifactPath}' was not staged; the staged artifacts are: ` +
						`${[...artifactSources.keys()].toSorted().join(", ")}.${derived}`,
				);
			}
			throw new Error(
				`artifact '${citation.artifactPath}' belongs to evidence source '${artifactSource}', not '${sourceKind}'`,
			);
		}
	}
}

function describeAvailableSources(sourceKinds: ReadonlySet<string>): string {
	return sourceKinds.size === 0 ? "(none)" : [...sourceKinds].toSorted().join(", ");
}

/**
 * An answer resting on absence searched staged sources, and every source the practice holds exhaustive: the
 * search is what bounds the claim that something is missing, whichever outcome the answer leads to.
 * These checks validate the declared search, not whether the model read it.
 */
export function validateSearchScope(
	observation: NormalizedObservation,
	exhaustiveSourceKinds: ReadonlySet<string>,
	availableSourceKinds: ReadonlySet<string>,
	shownWhole: ReadonlySet<string> = new Set(),
	notes: string[] = [],
): void {
	for (const answer of observation.answers) {
		const { search } = answer;
		if (!search) {
			continue;
		}
		const consulted = new Set(search.consulted);
		for (const sourceKind of consulted) {
			if (!availableSourceKinds.has(sourceKind)) {
				throw new Error(
					`answers.${answer.question}: searched source '${sourceKind}' was not available; copy one of these ` +
						`source kinds from the task-declared manifest: ${describeAvailableSources(availableSourceKinds)}`,
				);
			}
		}
		if (exhaustiveSourceKinds.size === 0) {
			throw new Error(
				`answers.${answer.question} has a search, and this practice reads no source exhaustively, so no search ` +
					"can bound an absence: drop search and cite the lines that show the answer, or answer UNDETERMINED " +
					"with wouldSettleIt",
			);
		}
		// A source the practice must search but this review never staged cannot bound an absence: the
		// review cannot say what is missing from what it did not read.
		const unstaged = [...exhaustiveSourceKinds]
			.filter((sourceKind) => !availableSourceKinds.has(sourceKind))
			.toSorted();
		if (unstaged.length > 0) {
			throw new Error(
				`answers.${answer.question} rests on an absence, and this review did not stage ${unstaged.join(", ")}, ` +
					"which the practice must search for one: an absence there cannot be shown. Answer from lines you can " +
					"cite, or answer UNDETERMINED with wouldSettleIt naming what was not staged",
			);
		}
		// A source the brief showed whole was read in full: an absence in it is bounded by that reading.
		const read = [...exhaustiveSourceKinds]
			.filter((sourceKind) => !consulted.has(sourceKind) && shownWhole.has(sourceKind))
			.toSorted();
		if (read.length > 0) {
			search.consulted = [...search.consulted, ...read].toSorted();
			for (const sourceKind of read) {
				consulted.add(sourceKind);
			}
			notes.push(
				`answers.${answer.question}: ${read.join(", ")} counted as searched — the brief shows it whole`,
			);
		}
		const unsearched = [...exhaustiveSourceKinds]
			.filter((sourceKind) => !consulted.has(sourceKind))
			.toSorted();
		if (unsearched.length > 0) {
			const them = unsearched.length > 1 ? "them" : "it";
			throw new Error(
				`answers.${answer.question} rests on an absence in ${unsearched.join(", ")} as well: add ${them} ` +
					`to its search.consulted once you have searched ${them}`,
			);
		}
	}
}

/** How much of a diff line a refusal quotes back; enough to see the difference, not the whole line. */
const MISMATCH_EXCERPT_CHARS = 160;

/** The most a citation by coordinates alone may record; beyond it, the model names a fragment. */
const COORDINATE_QUOTE_MAX_CHARS = 4000;

/**
 * A range too long to record whole, recorded as its leading lines that fit, so one long range does not cost the
 * observation a resend; a single line too long for it is still refused.
 */
function leadingLinesThatFit(
	cited: readonly string[],
	citation: NormalizedCitation,
	where: string,
	length: number,
): ResolvedQuote | { mismatch: string } {
	let kept = 0;
	let recorded = 0;
	for (const line of cited) {
		if (recorded + line.length > COORDINATE_QUOTE_MAX_CHARS) {
			break;
		}
		recorded += line.length;
		kept += 1;
	}
	if (kept === 0) {
		return {
			mismatch: `${where} is ${length} characters, over the ${COORDINATE_QUOTE_MAX_CHARS} one entry may record; cite the few lines that show the fact`,
		};
	}
	return {
		quote: cited.slice(0, kept).join("").replace(/\n$/u, ""),
		startLine: citation.startLine,
		endLine: citation.startLine + kept - 1,
		shortened: true,
	};
}

/** The same for lines of the change: the most leading lines of the cited range whose text fits. */
function leadingDiffLinesThatFit(
	citedLines: ReadonlyMap<number, string>,
	citation: NormalizedCitation,
	citedLineCount: number,
	length: number,
): ResolvedQuote | { mismatch: string } {
	for (let kept = citedLineCount - 1; kept >= 1; kept -= 1) {
		const leading = diffLinesMatch(citedLines, citation.startLine, kept, null);
		if ("text" in leading && leading.text.length <= COORDINATE_QUOTE_MAX_CHARS) {
			return {
				quote: leading.text,
				startLine: citation.startLine,
				endLine: citation.startLine + kept - 1,
				shortened: true,
			};
		}
	}
	return {
		mismatch: `[L${citation.startLine}]-[L${citation.endLine}] is ${length} characters, over the ${COORDINATE_QUOTE_MAX_CHARS} one entry may record; cite the few lines that show the fact`,
	};
}

/** Whether an observation's citation is really in the artifact it names. */
export function citationMatchesArtifact(citation: NormalizedCitation, content: string): boolean {
	return describeCitationMismatch(citation, content) === null;
}

/** Why a citation does not match, in one phrase, or null when it does. */
export function describeCitationMismatch(
	citation: NormalizedCitation,
	content: string,
): string | null {
	const resolved = resolveQuote(citation, content);
	return "mismatch" in resolved ? resolved.mismatch : null;
}

/**
 * Resolve a citation to the artifact's exact text for admission. Accepted formatting differences
 * are replaced with source text; an omitted quote is filled from the supplied coordinates.
 */
export function resolveQuote(
	citation: NormalizedCitation,
	content: string,
): ResolvedQuote | { mismatch: string } {
	const resolved = resolveQuoteText(citation, content);
	// Admission refuses a blank quote, so a citation of blank lines is refused here, with the reason.
	if ("quote" in resolved && resolved.quote.trim() === "") {
		return { mismatch: "the cited lines are blank; cite a line that has text" };
	}
	return resolved;
}

function resolveQuoteText(
	citation: NormalizedCitation,
	content: string,
): ResolvedQuote | { mismatch: string } {
	const quote = citation.quote.replace(/\r?\n$/u, "");
	if (citation.sourceKind !== "scm.pull-request.diff") {
		const lines = content.split(/(?<=\n)/u);
		if (citation.startLine > lines.length) {
			return {
				mismatch: `the artifact has ${lines.length} line(s), so there is no [L${citation.startLine}]`,
			};
		}
		const citedText = lines
			.slice(citation.startLine - 1, citation.endLine)
			.join("")
			.replace(/\n$/u, "");
		const where = `[L${citation.startLine}]${citation.endLine === citation.startLine ? "" : `-[L${citation.endLine}]`}`;
		if (quote === "") {
			if (citedText.length <= COORDINATE_QUOTE_MAX_CHARS) {
				return { quote: citedText };
			}
			return leadingLinesThatFit(
				lines.slice(citation.startLine - 1, citation.endLine),
				citation,
				where,
				citedText.length,
			);
		}
		const found = findAsWritten(citedText, quote);
		if (found !== null) {
			return { quote: found };
		}
		// The text is real but the coordinates are not: a file read without line numbers is cited by
		// a guess. When the quote occurs exactly once in the artifact, that is where it is recorded.
		const elsewhere = locateAsWritten(content, quote);
		if (elsewhere.length === 1 && elsewhere[0] !== undefined) {
			return elsewhere[0];
		}
		if (elsewhere.length > 1) {
			return {
				mismatch: `${where} does not hold that text; it occurs at ${elsewhere.map((hit) => `[L${hit.startLine}]`).join(", ")} — cite the one you mean`,
			};
		}
		// A body serialized into one JSON line reads as one line, escapes and all: the excerpt shows
		// it, and the hint says how to quote it, since a quote spanning its line breaks is never found.
		const hint = citedText.includes(String.raw`\n`)
			? String.raw`; this is a JSON string whose line breaks are the two characters \n, so quote a fragment from between two of them, or spell them as the line does`
			: "";
		return { mismatch: `${where} reads ${excerpt(citedText)}, not ${excerpt(quote)}${hint}` };
	}
	const lines = annotatedDiff(content);
	if (typeof lines === "string") {
		return { mismatch: lines };
	}
	if (citation.path === DIFF_VIEW) {
		return placedInDiff(lines, citation, quote);
	}
	const citedLines = lines.get(diffKey(citation.path, citation.side));
	if (citedLines === undefined || citedLines.size === 0) {
		return { mismatch: noLinesToCite(lines, citation.path, citation.side ?? "NEW") };
	}
	const citedLineCount = citation.endLine - citation.startLine + 1;
	const quoteLines = quote === "" ? null : quote.split(/\r\n|\r|\n/u);
	const spanMatches = quoteLines === null || quoteLines.length === citedLineCount;
	const atCited = spanMatches
		? diffLinesMatch(citedLines, citation.startLine, citedLineCount, quoteLines)
		: {
				mismatch: `the quote is ${quoteLines.length} line(s) and the citation covers ${citedLineCount}`,
			};
	// The coordinate may be the line of diff.patch itself, as a numbered view prints it: when that line
	// of the view is a line of the cited file and side and holds the text, it is the one meant.
	if (!("text" in atCited)) {
		const atView =
			readAtViewLines(lines, citedLines, citation, quoteLines) ??
			(quoteLines === null ? shownPartOf(citedLines, citation) : null);
		if (atView !== null) {
			return atView;
		}
	}
	if ("text" in atCited) {
		if (quoteLines !== null || atCited.text.length <= COORDINATE_QUOTE_MAX_CHARS) {
			return { quote: atCited.text };
		}
		return leadingDiffLinesThatFit(citedLines, citation, citedLineCount, atCited.text.length);
	}
	// The text may be real with the coordinates guessed, or the span miscounted: when the quoted
	// block occurs exactly once on that side of that path, it is recorded there.
	if (quoteLines !== null) {
		const found = [...citedLines.keys()].flatMap((start) => {
			const match = diffLinesMatch(citedLines, start, quoteLines.length, quoteLines);
			return "text" in match ? [{ start, text: match.text }] : [];
		});
		const only = found[0];
		if (found.length === 1 && only !== undefined) {
			return {
				quote: only.text,
				startLine: only.start,
				endLine: only.start + quoteLines.length - 1,
			};
		}
		if (found.length > 1) {
			return {
				mismatch: `[L${citation.startLine}] does not hold that text on the ${citation.side ?? "NEW"} side of ${citation.path}; it occurs at ${found.map((hit) => `[L${hit.start}]`).join(", ")} — cite the one you mean`,
			};
		}
	}
	return atCited;
}

/**
 * Why a changed file has no line to cite on the side named, and what to cite instead: its other side,
 * the commit that touches it when the diff shows its header alone, or the file the change does touch.
 */
function noLinesToCite(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	path: string,
	side: DiffSide,
): string {
	const other: DiffSide = side === "OLD" ? "NEW" : "OLD";
	if ((lines.get(diffKey(path, other))?.size ?? 0) > 0) {
		return `${path} has no lines on the ${side} side of the change; its changed lines are on the ${other} side`;
	}
	if (lines.has(diffKey(path, side)) || lines.has(diffKey(path, other))) {
		return (
			`${path} has no numbered lines in the diff — a binary file, a rename without edits or a mode ` +
			'change shows only its header — so no [L<n>] of it can be cited: cite the "path" line of its ' +
			"entry in the files of the commit that touches it, in commits.json"
		);
	}
	return `the change does not touch ${path}: name the changed file as the \`+++ b/\` header of its hunk does`;
}

/**
 * The coordinates may be lines of diff.patch itself, as a numbered view prints them. With a quote, the view's
 * line names the cited file's line that must hold it. With coordinates alone, the range is a stretch of the view
 * — a file's header and hunks included — and the cited file's lines among its lines, from the first to the last,
 * are what it cites: the view's lines are not the file's, since headers and the other side's lines interleave.
 * Null when the view names no line of the cited file there.
 */
function readAtViewLines(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	citedLines: ReadonlyMap<number, string>,
	citation: NormalizedCitation,
	quoteLines: readonly string[] | null,
): ResolvedQuote | null {
	const view = viewLines.get(lines);
	const key = diffKey(citation.path, citation.side);
	if (quoteLines !== null) {
		const viewed = view?.get(citation.startLine);
		if (viewed?.key !== key) {
			return null;
		}
		const atView = diffLinesMatch(citedLines, viewed.line, quoteLines.length, quoteLines);
		return "text" in atView
			? { quote: atView.text, startLine: viewed.line, endLine: viewed.line + quoteLines.length - 1 }
			: null;
	}
	const inRange: number[] = [];
	for (let at = citation.startLine; at <= citation.endLine; at += 1) {
		const viewed = view?.get(at);
		if (viewed?.key === key) {
			inRange.push(viewed.line);
		}
	}
	const [first] = inRange;
	const last = inRange.at(-1);
	if (first === undefined || last === undefined) {
		return null;
	}
	const count = last - first + 1;
	const atView = diffLinesMatch(citedLines, first, count, null);
	if ("text" in atView && atView.text.length <= COORDINATE_QUOTE_MAX_CHARS) {
		return { quote: atView.text, startLine: first, endLine: last };
	}
	// Too long to record whole, or across a hunk boundary where the file's lines are not contiguous: its
	// leading run that fits is what is recorded.
	const leading = leadingDiffLinesThatFit(
		citedLines,
		{ ...citation, startLine: first, endLine: last },
		count,
		"text" in atView ? atView.text.length : 0,
	);
	return "quote" in leading ? leading : null;
}

/**
 * A range by coordinates alone that the change shows only in part, such as a whole file of which a hunk shows
 * some lines: the cited file's lines the change shows inside it, from the first, as far as one run goes.
 */
function shownPartOf(
	citedLines: ReadonlyMap<number, string>,
	citation: NormalizedCitation,
): ResolvedQuote | null {
	const shown = [...citedLines.keys()]
		.filter((line) => line >= citation.startLine && line <= citation.endLine)
		.toSorted((a, b) => a - b);
	const [first] = shown;
	const last = shown.at(-1);
	if (first === undefined || last === undefined) {
		return null;
	}
	const leading = leadingDiffLinesThatFit(
		citedLines,
		{ ...citation, startLine: first, endLine: last },
		last - first + 2,
		0,
	);
	return "quote" in leading ? leading : null;
}

/** A quote as recorded, with corrected coordinates when the text was found elsewhere than cited. */
export interface ResolvedQuote {
	quote: string;
	startLine?: number;
	endLine?: number;
	/** The changed file and side the quote is on, when the citation named the diff view itself. */
	path?: string;
	side?: DiffSide;
	/** The cited range was too long to record whole: these are its leading lines that fit. */
	shortened?: true;
}

/** The derived view of the change; its own line numbers are not the coordinates a citation needs. */
const DIFF_VIEW = "work/change/diff.patch";

/**
 * The diff's lines from `start` matched against the quote's lines, one each: the content they carry
 * when every line matches, or why not. With no quote, the lines are simply read.
 */
function diffLinesMatch(
	citedLines: ReadonlyMap<number, string>,
	start: number,
	count: number,
	quoteLines: readonly string[] | null,
): { text: string } | { mismatch: string } {
	const recorded: string[] = [];
	for (let index = 0; index < count; index += 1) {
		const lineNumber = start + index;
		const diffLine = citedLines.get(lineNumber);
		if (diffLine === undefined) {
			return { mismatch: `the diff has no [L${lineNumber}] on that side of that path` };
		}
		const quoteLine = quoteLines?.[index];
		if (
			quoteLine !== undefined &&
			!quotesDiffLine(diffLine, withoutOwnCoordinate(quoteLine, lineNumber))
		) {
			return { mismatch: `[L${lineNumber}] reads ${excerpt(diffLine)}, not ${excerpt(quoteLine)}` };
		}
		recorded.push(diffLine.slice(1));
	}
	return { text: recorded.join("\n") };
}

/**
 * The text as the artifact writes it, when the quote is a reading of it: verbatim, or with the
 * escapes a JSON string uses, or with a non-breaking space where the quote has a space. Null when
 * the text holds no such reading.
 */
/** Every place the quote occurs in the whole artifact, as written there, with its line range. */
function locateAsWritten(content: string, quote: string): ResolvedQuote[] {
	const hits: ResolvedQuote[] = [];
	for (const candidate of [quote, JSON.stringify(quote).slice(1, -1)]) {
		for (const match of content.matchAll(asWrittenPattern(candidate, "g"))) {
			const startLine = content.slice(0, match.index).split("\n").length;
			const endLine = startLine + match[0].split("\n").length - 1;
			if (!hits.some((hit) => hit.startLine === startLine)) {
				hits.push({ quote: match[0], startLine, endLine });
			}
		}
	}
	return hits;
}

function findAsWritten(text: string, quote: string): string | null {
	for (const candidate of [quote, JSON.stringify(quote).slice(1, -1)]) {
		if (text.includes(candidate)) {
			return candidate;
		}
		const match = asWrittenPattern(candidate).exec(text);
		if (match) {
			return match[0];
		}
	}
	return null;
}

function squeezed(value: string): string {
	return value.replaceAll(/\s+/gu, " ").trim();
}

/** Whether a text holds these words, however the spaces between them ran. */
export function containsWords(text: string, words: string): boolean {
	const wanted = squeezed(words);
	return wanted !== "" && squeezed(text).includes(wanted);
}

/** Where an anchor's words lie: one line of a file of the change or of an artifact. */
export interface AnchorLine {
	path: string;
	side?: DiffSide;
	line: number;
}

/**
 * The one line an anchor's words are on, matched however their spaces ran: for a citation of the change, among
 * the lines the diff shows of its file on either side; otherwise among the artifact's lines. Undefined when the
 * words are on no line or on several, so an anchor never picks between candidates.
 */
export function locateAnchor(
	citation: NormalizedCitation,
	content: string,
	anchor: string,
): AnchorLine | undefined {
	const wanted = squeezed(anchor);
	if (!wanted) {
		return undefined;
	}
	const hits: AnchorLine[] = [];
	if (citation.sourceKind === "scm.pull-request.diff") {
		const lines = annotatedDiff(content);
		if (typeof lines === "string") {
			return undefined;
		}
		for (const side of ["NEW", "OLD"] as const) {
			for (const [line, text] of lines.get(diffKey(citation.path, side)) ?? []) {
				// The first character is the diff's own marker, not the author's text.
				if (squeezed(text.slice(1)).includes(wanted)) {
					hits.push({ path: citation.path, side, line });
				}
			}
		}
	} else {
		for (const [index, text] of content.split("\n").entries()) {
			if (squeezed(text).includes(wanted)) {
				hits.push({ path: citation.path, line: index + 1 });
			}
		}
	}
	return hits.length === 1 ? hits[0] : undefined;
}

function diffKey(path: string | null, side: DiffSide | undefined): string {
	return `${side ?? "NEW"} ${path ?? ""}`;
}

/**
 * For each parsed diff, which [L<n>] of which file and side each line of the diff view itself carries:
 * a coordinate copied from `sed -n` or `grep -n` over diff.patch names a line of the view.
 */
const viewLines = new WeakMap<
	ReadonlyMap<string, ReadonlyMap<number, string>>,
	ReadonlyMap<number, { key: string; line: number }>
>();

/** Whether the change has a line a citation can quote: one that is only binary files, renames or mode changes has none. */
export function changeHasCitableLines(diffPatch: string): boolean {
	const lines = annotatedDiff(diffPatch);
	return typeof lines !== "string" && [...lines.values()].some((byLine) => byLine.size > 0);
}

/**
 * The annotated diff's lines by file and side (keyed by {@link diffKey}), each by its line number, or
 * why they could not be read.
 */
function annotatedDiff(content: string): Map<string, Map<number, string>> | string {
	let oldPath: string | null = null;
	let newPath: string | null = null;
	const byFile = new Map<string, Map<number, string>>();
	const atViewLine = new Map<number, { key: string; line: number }>();
	viewLines.set(byFile, atViewLine);
	// A file the diff names without a hunk — binary, renamed without edits, mode only — is known with no
	// lines, so a citation of it is told why rather than that the change does not touch it.
	const known = (side: DiffSide, filePath: string) => {
		const key = diffKey(filePath, side);
		if (!byFile.has(key)) {
			byFile.set(key, new Map());
		}
	};
	for (const [index, storedLine] of content.split("\n").entries()) {
		// Both groups are mandatory, so binding them here is what lets the annotated branch below turn
		// on a value the compiler has seen rather than on the match object being non-null.
		const [, annotatedLineNumber, annotatedText] =
			/^\[L(?<line>\d+)\] (?<text>[\s\S]*)$/u.exec(storedLine) ?? [];
		const line = annotatedText ?? storedLine;
		if (annotatedLineNumber === undefined && line.startsWith("diff --git ")) {
			// `a/<path> b/<path>`: only an unrenamed header splits unambiguously, and a rename says its
			// paths on the lines below.
			const header = line.slice("diff --git ".length);
			const middle = (header.length - 1) / 2;
			const filePath = header.slice(2, middle);
			if (header[middle] === " " && header === `a/${filePath} b/${filePath}`) {
				known("OLD", filePath);
				known("NEW", filePath);
			}
			continue;
		}
		if (annotatedLineNumber === undefined && line.startsWith("rename from ")) {
			known("OLD", line.slice("rename from ".length));
			continue;
		}
		if (annotatedLineNumber === undefined && line.startsWith("rename to ")) {
			known("NEW", line.slice("rename to ".length));
			continue;
		}
		if (annotatedLineNumber === undefined && line.startsWith("--- ")) {
			oldPath = diffPath(line.slice(4));
			continue;
		}
		if (annotatedLineNumber === undefined && line.startsWith("+++ ")) {
			newPath = diffPath(line.slice(4));
			continue;
		}
		if (annotatedLineNumber !== undefined) {
			const lineNumber = Number(annotatedLineNumber);
			if (!Number.isSafeInteger(lineNumber) || lineNumber > 2_147_483_647) {
				return "invalid annotated line number";
			}
			const side: DiffSide = line.startsWith("-") ? "OLD" : "NEW";
			const key = diffKey(side === "OLD" ? oldPath : newPath, side);
			const lines = byFile.get(key) ?? new Map<number, string>();
			lines.set(lineNumber, line);
			byFile.set(key, lines);
			atViewLine.set(index + 1, { key, line: lineNumber });
		}
	}
	return byFile;
}

/**
 * A range of the diff view by coordinates alone, when the view's lines in it are lines of one changed file
 * and side: what it cites is those lines, recorded as that file's. A range across files names no one file.
 */
function viewRangeOfOneFile(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	citation: NormalizedCitation,
): ResolvedQuote | null {
	const view = viewLines.get(lines);
	const keys = new Set<string>();
	for (let at = citation.startLine; at <= citation.endLine; at += 1) {
		const viewed = view?.get(at);
		if (viewed !== undefined) {
			keys.add(viewed.key);
		}
	}
	const [key] = keys;
	const fileLines = key === undefined ? undefined : lines.get(key);
	if (keys.size !== 1 || key === undefined || fileLines === undefined) {
		return null;
	}
	const [side, ...path] = key.split(" ");
	const asFile = {
		...citation,
		path: path.join(" "),
		side: side === "OLD" ? "OLD" : "NEW",
	} as const;
	const read = readAtViewLines(lines, fileLines, asFile, null);
	return read === null ? null : { ...read, path: asFile.path, side: asFile.side };
}

/**
 * A citation that names the diff view is a citation of a changed file: when its quote occurs once in
 * the change, the file, side and [L<n>] it occurs at are what it cites. Otherwise the session is told
 * what a citation of the change names.
 */
function placedInDiff(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	citation: NormalizedCitation,
	quote: string,
): ResolvedQuote | { mismatch: string } {
	const how =
		`${DIFF_VIEW} is the view of the change, not a file in it: cite the changed file's path (the ` +
		"`+++ b/<path>` above its hunk) and the [L<n>] that prefixes the line";
	if (quote === "") {
		return viewRangeOfOneFile(lines, citation) ?? { mismatch: `${how}, with the quoted text` };
	}
	const quoteLines = quote.split(/\r\n|\r|\n/u);
	const found = [...lines].flatMap(([key, fileLines]) =>
		[...fileLines.keys()].flatMap((start) => {
			const match = diffLinesMatch(fileLines, start, quoteLines.length, quoteLines);
			return "text" in match ? [{ key, start, text: match.text }] : [];
		}),
	);
	const only = found[0];
	if (found.length === 1 && only !== undefined) {
		const [side, ...path] = only.key.split(" ");
		return {
			quote: only.text,
			startLine: only.start,
			endLine: only.start + quoteLines.length - 1,
			path: path.join(" "),
			side: side === "OLD" ? "OLD" : "NEW",
		};
	}
	return {
		mismatch:
			found.length === 0
				? `${how}; the quoted text is not a line of the change`
				: `${how}; the quoted text occurs ${found.length} times in the change, so name the one you mean`,
	};
}

/** Compare text at pinned coordinates, allowing diff markers and horizontal whitespace variants. */
function quotesDiffLine(diffLine: string, quoted: string): boolean {
	if (diffLine.length === 0) {
		return false;
	}
	const content = squash(diffLine.slice(1));
	const quote = squash(quoted);
	return (
		squash(diffLine) === quote ||
		content === quote ||
		// A marker the quote carries that is not the line's own: a blank added line read as "+" where
		// the diff shows a blank context line, for instance. The coordinate pins the line; markers are
		// presentation.
		(/^[+\- ]/u.test(quoted) && content === squash(quoted.slice(1)))
	);
}

/** Horizontal whitespace, including the non-breaking kinds, is presentation. */
const HORIZONTAL_SPACE = String.raw`[ \t\u00a0\u2007\u202f]`;

function squash(text: string): string {
	return text.replaceAll(new RegExp(`${HORIZONTAL_SPACE}+`, "gu"), "");
}

/** A regex that finds the quote as the artifact may write it: the same characters, spacing aside. */
function asWrittenPattern(candidate: string, flags = ""): RegExp {
	const escaped = candidate.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
	return new RegExp(
		escaped
			.replaceAll(new RegExp(`${HORIZONTAL_SPACE}+`, "gu"), `${HORIZONTAL_SPACE}*`)
			.replaceAll(/\r?\n/gu, `${HORIZONTAL_SPACE}*\\r?\\n${HORIZONTAL_SPACE}*`),
		`${flags}u`,
	);
}

/**
 * The quote without the `[L<n>] ` coordinates the brief and the diff view print in front of every
 * line. A coordinate that names the line it sits on is presentation, copied along with the text;
 * one that does not is left in place, so a mismatch is reported as the text it is.
 */
export function withoutCoordinates(quote: string, startLine: number): string {
	if (!Number.isSafeInteger(startLine)) {
		return quote;
	}
	return quote
		.split(/(?<=\r\n|\r|\n)/u)
		.map((line, index) => {
			const ending = /\r\n|\r|\n$/u.exec(line)?.[0] ?? "";
			const text = ending ? line.slice(0, -ending.length) : line;
			return withoutOwnCoordinate(text, startLine + index) + ending;
		})
		.join("");
}

/** A copied annotation must agree with the cited coordinate. */
function withoutOwnCoordinate(quoteLine: string, lineNumber: number): string {
	const [, quotedNumber, quotedText] =
		/^\[L(?<line>\d+)\] (?<text>[\s\S]*)$/u.exec(quoteLine) ?? [];
	return quotedText !== undefined && quotedNumber === String(lineNumber) ? quotedText : quoteLine;
}

/** One line as evidence in a refusal: quoted, and cut where a reader has already seen the difference. */
function excerpt(line: string): string {
	const cut =
		line.length > MISMATCH_EXCERPT_CHARS ? `${line.slice(0, MISMATCH_EXCERPT_CHARS)}…` : line;
	return JSON.stringify(cut);
}

function diffPath(rawPath: string): string | null {
	let value = rawPath.trim();
	if (value === "/dev/null") {
		return null;
	}
	if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
		value = value.slice(1, -1).replaceAll(String.raw`\"`, '"');
	}
	return value.startsWith("a/") || value.startsWith("b/") ? value.slice(2) : value;
}
