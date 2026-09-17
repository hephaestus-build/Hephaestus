// ── Vocabularies shared with Java ────────────────────────────────────────────
// Each list mirrors an enum on the server. They are hand-maintained on both sides, so
// AgentVocabularySyncTest parses these literals and asserts equality with the Java enum's
// values(), preventing the runtime and persistence contracts from accepting different labels.
export const ASSESSMENT_STATUS_VALUES = ["ASSESSED", "NOT_APPLICABLE", "UNDETERMINED"] as const;
export const PRESENCE_VALUES = ["PRESENT", "ABSENT"] as const;
export const ASSESSMENT_VALUES = ["GOOD", "BAD"] as const;
export const SEVERITY_VALUES = ["CRITICAL", "MAJOR", "MINOR", "INFO"] as const;

// The vocabularies above are the values; these are the types every consumer spells them with. They are
// derived from the arrays rather than written twice, so the arrays stay the single thing Java is synced
// against and a value cannot be added to one without being added to the other.
export type AssessmentStatus = (typeof ASSESSMENT_STATUS_VALUES)[number];
export type Presence = (typeof PRESENCE_VALUES)[number];
export type Assessment = (typeof ASSESSMENT_VALUES)[number];
export type Severity = (typeof SEVERITY_VALUES)[number];

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
	startLine: number;
	endLine: number;
	quote: string;
}

/** The recorded scope of a search that came up empty — the warrant an ABSENT observation owes. */
export interface RecordedSearch {
	consulted: string[];
	lookedFor: string;
	boundary: string;
}

/** Why this practice has no subject here — the warrant a NOT_APPLICABLE observation owes. */
export interface RecordedInapplicability {
	consulted: string[];
	subject: string;
	ruledOutBy: string;
}

/** What the evidence left open — the warrant an UNDETERMINED observation owes. */
export interface RecordedUndecidability {
	openQuestion: string;
	wouldSettleIt: string;
}

/**
 * Citations plus, at most, the one extra warrant this observation requires. Which branch is
 * present is decided by assessment status and presence and enforced in {@link normalizeEvidence}; the optionality here is the
 * shape, not the rule.
 */
export interface NormalizedEvidence {
	citations: NormalizedCitation[];
	search?: RecordedSearch;
	inapplicability?: RecordedInapplicability;
	undecidability?: RecordedUndecidability;
}

/** One measurement, checked. This is what reaches result.json and, from there, Java. */
export type NormalizedObservation = ObservationAssessment & {
	practiceSlug: string;
	summary: string;
	evidence: NormalizedEvidence;
	evidenceRationale: string;
};

/**
 * The narrowing every reader of model-authored or file-authored JSON in this runtime starts from.
 *
 * <p>Exported because pi-runner.ts parses the same class of input — a task envelope, a composition
 * request, an admission response — and one guard both modules share cannot drift from itself.
 */
export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

// Tool descriptions distinguish status, target presence and judgment at the point of annotation.
export const ASSESSMENT_STATUS_DESCRIPTIONS: Record<AssessmentStatus, string> = {
	ASSESSED:
		"The evidence settles the result. Supply presence and assessment; severity only for a NEGATIVE outcome.",
	NOT_APPLICABLE:
		"A concrete fact rules out the practice's prerequisite occasion. Name it in evidence.inapplicability. Not a missing target: avoiding a harmful target in an applicable corpus is ASSESSED/ABSENT/BAD.",
	UNDETERMINED:
		"Relevant evidence was captured and read but does not settle the question. Record the open question and what would settle it in evidence.undecidability. Missing or failed capture is a review readiness failure, not an observation.",
};
export const PRESENCE_DESCRIPTIONS: Record<Presence, string> = {
	PRESENT:
		"The specific behavior named in the observation occurred. Cite it and assess its desirability in context. Partial or misleading guidance is present; name a missing component precisely if assessing its absence.",
	ABSENT:
		"The practice applies and the specified behavior is absent from the bounded searched corpus. Record that same behavior in evidence.search. Missing desirable behavior yields NEGATIVE; absent undesirable behavior yields POSITIVE only with an applicable opportunity and complete coverage.",
};

/** Contextual desirability of the specified behavior, independent of its presence. */
export const ASSESSMENT_DESCRIPTIONS: Record<Assessment, string> = {
	GOOD: "The specified behavior is desirable in the evidenced context. PRESENT yields POSITIVE; ABSENT yields NEGATIVE. Explain why the behavior is desirable here.",
	BAD: "The specified behavior is undesirable in the evidenced context. PRESENT yields NEGATIVE; ABSENT yields POSITIVE. Optional or unnecessary behavior is not automatically undesirable.",
};

/**
 * Severity is read off the practice's own severity table, keyed to the fact that was quoted — these
 * descriptions calibrate the bands so the same fact lands in the same band every run. They are not an
 * invitation to grade by feel.
 */
export const SEVERITY_DESCRIPTIONS: Record<Severity, string> = {
	CRITICAL:
		"The consequence is expensive or impossible to undo once this merges — a leaked credential, data loss, " +
		"a security hole. Differs from MAJOR by whether the damage can still be taken back.",
	MAJOR:
		"A real defect to fix before merging, whose consequence is contained and correctable. Differs from " +
		"MINOR by whether a reader of this change would be wrong about how it behaves.",
	MINOR:
		"A craft-level improvement worth making that nobody would block a merge on. Differs from INFO by " +
		"whether there is a specific edit to make.",
	INFO: "An advisory, low-impact problem. Still a NEGATIVE outcome; strengths and unassessed observations require null severity.",
};

/**
 * Renders a vocabulary as the `description` of its enum field, one line per value.
 *
 * <p>Throws on a value with no description, so a value added to the vocabulary without being described
 * fails here rather than reaching the model as an undifferentiated word — the same structural guard, one
 * level down, that AgentVocabularySyncTest applies across the language boundary.
 */
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

/**
 * The trimmed text of a value the model sent, and "" for anything that has no text of its own.
 *
 * <p>An object or an array has none. Coercing one yields "[object Object]", or its elements run
 * together, and either is a non-empty string — so a required-field check downstream reads a field the
 * model filled in with the wrong kind of value as one it filled in correctly. Here that value reads as
 * absent instead, which is the case those checks already answer.
 */
function trimmedText(value: unknown): string {
	if (typeof value === "string") {
		return value.trim();
	}
	if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
		return String(value);
	}
	return "";
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
		throw new Error("search is required");
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

/** A citation alone cannot establish inapplicability: require the prerequisite and its exclusion. */
export function normalizeInapplicability(inapplicability: unknown): RecordedInapplicability {
	if (!isRecord(inapplicability)) {
		throw new Error("inapplicability is required");
	}
	const consulted = trimmedStrings(inapplicability.consulted);
	const subject = trimmedText(inapplicability.subject);
	const ruledOutBy = trimmedText(inapplicability.ruledOutBy);
	if (consulted.length === 0) {
		throw new Error(
			"inapplicability.consulted must name at least one source you read to conclude this",
		);
	}
	if (!subject) {
		throw new Error("inapplicability.subject is required: name what this practice looks for");
	}
	if (!ruledOutBy) {
		throw new Error(
			"inapplicability.ruledOutBy is required: state the fact about THIS work that means the subject " +
				"cannot occur in it. If you are merely unsure, the answer is UNDETERMINED, not NOT_APPLICABLE",
		);
	}
	return { consulted: [...new Set(consulted)].toSorted(), subject, ruledOutBy };
}

/** The side a citation may carry: OLD or NEW for diff evidence, and none for anything else. */
function citationSide(sourceKind: string, declared: unknown): DiffSide | null {
	const side = declared == null ? null : trimmedText(declared).toUpperCase();
	if (sourceKind === "scm.pull-request.diff") {
		if (side !== "OLD" && side !== "NEW") {
			throw new Error("diff evidence citation side must be OLD or NEW");
		}
		return side;
	}
	if (side !== null) {
		throw new Error("non-diff evidence citation must not specify side");
	}
	return null;
}

function citationLines(fields: Record<string, unknown>): { startLine: number; endLine: number } {
	const startLine = Number(fields.startLine);
	const endLine = fields.endLine == null ? startLine : Number(fields.endLine);
	if (!Number.isSafeInteger(startLine) || startLine <= 0 || startLine > 2_147_483_647) {
		throw new Error("evidence citation startLine must be a positive integer");
	}
	if (!Number.isSafeInteger(endLine) || endLine < startLine || endLine > 2_147_483_647) {
		throw new Error("evidence citation endLine must be >= startLine");
	}
	return { startLine, endLine };
}

function normalizeCitation(citation: unknown): NormalizedCitation {
	// A citation that is not an object reads as one with every field missing, which is what the
	// required-field checks below already reject by name.
	const fields: Record<string, unknown> = isRecord(citation) ? citation : {};
	const sourceKind = trimmedText(fields.sourceKind);
	const artifactPath = typeof fields.artifactPath === "string" ? fields.artifactPath : "";
	const path = typeof fields.path === "string" ? fields.path : "";
	const quote = typeof fields.quote === "string" ? fields.quote : "";
	if (!sourceKind) {
		throw new Error("evidence citation sourceKind is required");
	}
	if (!artifactPath.trim()) {
		throw new Error("evidence citation artifactPath is required");
	}
	if (!path.trim()) {
		throw new Error("evidence citation path is required");
	}
	const side = citationSide(sourceKind, fields.side);
	const { startLine, endLine } = citationLines(fields);
	if (
		!quote
			.replaceAll("\u001C", "")
			.replaceAll("\u001D", "")
			.replaceAll("\u001E", "")
			.replaceAll("\u001F", "")
			.trim()
	) {
		throw new Error("evidence citation quote is required");
	}
	return {
		sourceKind,
		artifactPath,
		path,
		...(side == null ? {} : { side }),
		startLine,
		endLine,
		quote,
	};
}

export function normalizeEvidence(
	evidence: unknown,
	assessmentStatus: AssessmentStatus,
	presence: Presence | null,
): NormalizedEvidence {
	if (
		!isRecord(evidence) ||
		!Array.isArray(evidence.citations) ||
		evidence.citations.length === 0
	) {
		throw new Error("evidence citations are required");
	}
	const citations = evidence.citations.map(normalizeCitation);
	// Absence needs a bounded search, not just a citation to something else.
	if (presence === "ABSENT") {
		if (evidence.search == null) {
			throw new Error(
				"an ABSENT observation must record its search: evidence.search with consulted, lookedFor and boundary",
			);
		}
		return { citations, search: normalizeSearch(evidence.search) };
	}
	// Inapplicability is a positive claim about scope, not an uncertain assessment.
	if (assessmentStatus === "NOT_APPLICABLE") {
		if (evidence.inapplicability == null) {
			throw new Error(
				"a NOT_APPLICABLE observation must say why the practice does not apply: " +
					"evidence.inapplicability with consulted, subject and ruledOutBy. If you looked and could " +
					"not tell, say UNDETERMINED instead",
			);
		}
		return { citations, inapplicability: normalizeInapplicability(evidence.inapplicability) };
	}
	// Make unresolved evidence explicit so uncertainty cannot silently become a verdict.
	if (assessmentStatus === "UNDETERMINED") {
		if (evidence.undecidability == null) {
			throw new Error(
				"an UNDETERMINED observation must say what it could not settle: evidence.undecidability with " +
					"openQuestion and wouldSettleIt",
			);
		}
		return { citations, undecidability: normalizeUndecidability(evidence.undecidability) };
	}
	return evidence.search == null
		? { citations }
		: { citations, search: normalizeSearch(evidence.search) };
}

/**
 * The recorded shape of a question the evidence left open. Sibling of {@link normalizeSearch} and
 * {@link normalizeInapplicability}: each presence that makes a claim beyond its citations has to ground it.
 */
export function normalizeUndecidability(undecidability: unknown): RecordedUndecidability {
	if (!isRecord(undecidability)) {
		throw new Error("undecidability is required");
	}
	const openQuestion = trimmedText(undecidability.openQuestion);
	const wouldSettleIt = trimmedText(undecidability.wouldSettleIt);
	if (!openQuestion) {
		throw new Error("undecidability.openQuestion is required");
	}
	if (!wouldSettleIt) {
		throw new Error("undecidability.wouldSettleIt is required");
	}
	return { openQuestion, wouldSettleIt };
}

function parseVocabulary<T extends string>(values: readonly T[], value: unknown, field: string): T {
	const admitted = values.find(
		(candidate) => candidate === (typeof value === "string" ? value.toUpperCase() : value),
	);
	if (admitted === undefined) {
		throw new Error(`invalid ${field} '${String(value)}'`);
	}
	return admitted;
}

export type Outcome = "POSITIVE" | "NEGATIVE";

export function deriveOutcome(
	presence: Presence | null,
	assessment: Assessment | null,
): Outcome | null {
	if (presence === null && assessment === null) {
		return null;
	}
	if (presence === null || assessment === null) {
		throw new Error("Presence and assessment must agree on whether the observation is assessed");
	}
	return (presence === "PRESENT") === (assessment === "GOOD") ? "POSITIVE" : "NEGATIVE";
}

type ObservationAssessment =
	| {
			assessmentStatus: "ASSESSED";
			presence: Presence;
			assessment: Assessment;
			severity: Severity | null;
	  }
	| {
			assessmentStatus: "NOT_APPLICABLE" | "UNDETERMINED";
			presence: null;
			assessment: null;
			severity: null;
	  };

function parseAssessment(fields: Record<string, unknown>): ObservationAssessment {
	const assessmentStatus = parseVocabulary(
		ASSESSMENT_STATUS_VALUES,
		fields.assessmentStatus,
		"assessmentStatus",
	);
	if (assessmentStatus !== "ASSESSED") {
		if (fields.presence !== null || fields.assessment !== null || fields.severity !== null) {
			throw new Error(
				"Unassessed observations require explicit null presence, assessment and severity",
			);
		}
		return { assessmentStatus, presence: null, assessment: null, severity: null };
	}
	const presence = parseVocabulary(PRESENCE_VALUES, fields.presence, "presence");
	const assessment = parseVocabulary(ASSESSMENT_VALUES, fields.assessment, "assessment");
	if (deriveOutcome(presence, assessment) === "POSITIVE") {
		if (fields.severity !== null) {
			throw new Error("POSITIVE outcome requires null severity");
		}
		return { assessmentStatus, presence, assessment, severity: null };
	}
	const severity = parseVocabulary(SEVERITY_VALUES, fields.severity, "severity");
	return { assessmentStatus, presence, assessment, severity };
}

/** The one evidence branch an observation of this status must carry, or none for an assessed presence. */
function expectedEvidenceBranch(
	presence: Presence | null,
	assessmentStatus: AssessmentStatus,
): string | null {
	if (presence === "ABSENT") {
		return "search";
	}
	if (assessmentStatus === "NOT_APPLICABLE") {
		return "inapplicability";
	}
	if (assessmentStatus === "UNDETERMINED") {
		return "undecidability";
	}
	return null;
}

export function normalizeObservation(observation: unknown): NormalizedObservation {
	if (!isRecord(observation)) {
		throw new Error("observation must be an object");
	}
	const allowed = new Set([
		"practiceSlug",
		"summary",
		"assessmentStatus",
		"presence",
		"assessment",
		"severity",
		"evidence",
		"evidenceRationale",
	]);
	const unknownFields = Object.keys(observation).filter((key) => !allowed.has(key));
	if (unknownFields.length > 0) {
		throw new Error(`unknown observation field(s): ${unknownFields.join(", ")}`);
	}
	const practiceSlug = trimmedText(observation.practiceSlug).toLowerCase().replaceAll("_", "-");
	const title = trimmedText(observation.summary);
	const reasoning = trimmedText(observation.evidenceRationale);
	const result = parseAssessment(observation);
	const { assessmentStatus, presence } = result;
	if (!practiceSlug) {
		throw new Error("practiceSlug is required");
	}
	if (!title) {
		throw new Error("summary is required");
	}
	// The summary is what the developer reads on their practice page, above the practice's own name and
	// with no evidence beside it, so a single word there ("Test") names nothing the practice did not.
	if (!/\S\s+\S/u.test(title)) {
		throw new Error(
			"summary must say what was observed as a short phrase, not one word — e.g. " +
				"'Debug print left in the request handler'",
		);
	}
	if (!reasoning) {
		throw new Error("evidenceRationale is required");
	}
	const externalEvidence: Record<string, unknown> = isRecord(observation.evidence)
		? observation.evidence
		: {};
	const evidenceFields = new Set(["citations", "search", "inapplicability", "undecidability"]);
	const unknownEvidence = Object.keys(externalEvidence).filter((key) => !evidenceFields.has(key));
	if (unknownEvidence.length > 0) {
		throw new Error(`unknown evidence field(s): ${unknownEvidence.join(", ")}`);
	}
	const branchCount = ["search", "inapplicability", "undecidability"].filter(
		(key) => externalEvidence[key] != null,
	).length;
	const expectedBranch = expectedEvidenceBranch(presence, assessmentStatus);
	if (
		(expectedBranch == null && branchCount !== 0) ||
		(expectedBranch != null && (branchCount !== 1 || externalEvidence[expectedBranch] == null))
	) {
		throw new Error(
			`evidence must carry exactly ${expectedBranch ?? "citations"} for this outcome`,
		);
	}
	const evidence = normalizeEvidence(externalEvidence, assessmentStatus, presence);
	const out: NormalizedObservation = {
		practiceSlug,
		summary: title,
		...result,
		evidence,
		evidenceRationale: reasoning,
	};
	return out;
}

export function dedupeKeyForObservation(observation: NormalizedObservation): string {
	const citations = observation.evidence.citations
		.map((citation) => `${citation.path}:${citation.startLine}-${citation.endLine}`)
		.join(",");
	return `${observation.practiceSlug}|${observation.summary}|${citations}`;
}

/** Requires each citation to name an artifact staged by its declared source. */
export function validateEvidenceSources(
	observation: NormalizedObservation,
	availableSourceKinds: ReadonlySet<string>,
	artifactSources: ReadonlyMap<string, string> = new Map(),
): void {
	for (const citation of observation.evidence.citations) {
		const { sourceKind } = citation;
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`evidence source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`the task-declared manifest: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
		const artifactSource = artifactSources.get(citation.artifactPath);
		if (artifactSource !== sourceKind) {
			throw new Error(
				artifactSource === undefined
					? `artifact '${citation.artifactPath}' was not staged; copy an artifact path from the task-declared manifest`
					: `artifact '${citation.artifactPath}' belongs to evidence source '${artifactSource}', not '${sourceKind}'`,
			);
		}
	}
}

function describeAvailableSources(sourceKinds: ReadonlySet<string>): string {
	return sourceKinds.size === 0 ? "(none)" : [...sourceKinds].toSorted().join(", ");
}

/**
 * Requires searched sources to be staged and every declared exhaustive source to be consulted.
 * ABSENT/BAD additionally requires an exhaustive source policy: a positive absence claim needs a
 * closed search boundary. These checks validate the declared search, not whether the model read it.
 */
export function validateSearchScope(
	observation: NormalizedObservation,
	exhaustiveSourceKinds: ReadonlySet<string>,
	availableSourceKinds: ReadonlySet<string>,
): void {
	if (observation.presence !== "ABSENT") {
		return;
	}
	const { search } = observation.evidence;
	if (!search) {
		throw new Error("an ABSENT observation must record its search");
	}
	if (
		deriveOutcome(observation.presence, observation.assessment) === "POSITIVE" &&
		exhaustiveSourceKinds.size === 0
	) {
		throw new Error(
			`cannot conclude ABSENT + BAD for '${observation.practiceSlug}': it declares no source it searches ` +
				`exhaustively, so "this is not anywhere in the work" ranges over a corpus it has not bounded — ` +
				`say UNDETERMINED instead`,
		);
	}
	const consulted = new Set(search.consulted);
	for (const sourceKind of consulted) {
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`searched source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`the task-declared manifest: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
	}
	const unsearched = [...exhaustiveSourceKinds]
		.filter((sourceKind) => !consulted.has(sourceKind))
		.toSorted();
	if (unsearched.length > 0) {
		throw new Error(
			`cannot conclude ABSENT for '${observation.practiceSlug}' without searching ${unsearched.join(", ")} — ` +
				`say UNDETERMINED instead, or record the search`,
		);
	}
}

/**
 * Holds a NOT_APPLICABLE claim to sources this run actually staged.
 *
 * The same boundary the citations and the recorded search answer to: bytes that were never there cannot
 * have been read, so claiming to have read them is the inapplicability-shaped version of citing evidence
 * we never had.
 */
export function validateInapplicabilityScope(
	observation: NormalizedObservation,
	availableSourceKinds: ReadonlySet<string>,
): void {
	if (observation.assessmentStatus !== "NOT_APPLICABLE") {
		return;
	}
	const { inapplicability } = observation.evidence;
	if (!inapplicability) {
		throw new Error("a NOT_APPLICABLE observation must say why the practice does not apply");
	}
	for (const sourceKind of inapplicability.consulted) {
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`consulted source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`the task-declared manifest: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
	}
}

/** How much of a diff line a refusal quotes back; enough to see the difference, not the whole line. */
const MISMATCH_EXCERPT_CHARS = 160;

/** Whether an observation's citation is really in the artifact it names. */
export function citationMatchesArtifact(citation: NormalizedCitation, content: string): boolean {
	return describeCitationMismatch(citation, content) === null;
}

/** The stored diff's lines on the cited side of the cited path, by line number. */
function citedDiffLines(
	citation: NormalizedCitation,
	content: string,
): { lines: Map<number, string> } | { mismatch: string } {
	let oldPath: string | null = null;
	let newPath: string | null = null;
	const lines = new Map<number, string>();
	for (const storedLine of content.split("\n")) {
		// Both groups are mandatory, so binding them here is what lets the annotated branch below turn
		// on a value the compiler has seen rather than on the match object being non-null.
		const annotated = /^\[L(?<lineNumber>\d+)\] (?<text>[\s\S]*)$/u.exec(storedLine)?.groups;
		const annotatedLineNumber = annotated?.lineNumber;
		const line = annotated?.text ?? storedLine;
		if (annotatedLineNumber === undefined) {
			if (line.startsWith("--- ")) {
				oldPath = diffPath(line.slice(4));
			} else if (line.startsWith("+++ ")) {
				newPath = diffPath(line.slice(4));
			}
			continue;
		}
		const lineNumber = Number(annotatedLineNumber);
		if (!Number.isSafeInteger(lineNumber) || lineNumber > 2_147_483_647) {
			return { mismatch: "invalid annotated line number" };
		}
		const side: DiffSide = line.startsWith("-") ? "OLD" : "NEW";
		const path = side === "OLD" ? oldPath : newPath;
		if (side === citation.side && path === citation.path) {
			lines.set(lineNumber, line);
		}
	}
	return { lines };
}

/**
 * Why a citation does not match, in one phrase, or null when it does. A refusal that only says "does
 * not match" leaves the session guessing at which of the coordinate, the side and the text was wrong,
 * and leaves a reader of the transcript guessing at the same thing. The rule itself is unchanged:
 * every quote is still read out of the artifact it names.
 */
export function describeCitationMismatch(
	citation: NormalizedCitation,
	content: string,
): string | null {
	if (citation.sourceKind !== "scm.pull-request.diff") {
		const found = content.includes(citation.quote);
		return found ? null : "that text is not in the artifact";
	}
	const cited = citedDiffLines(citation, content);
	if ("mismatch" in cited) {
		return cited.mismatch;
	}
	// Match Java String.lines(): CR/LF delimiters, without the final terminator's empty item.
	const quoteLines = citation.quote.split(/\r\n|\r|\n/u);
	if (quoteLines.at(-1) === "") {
		quoteLines.pop();
	}
	const citedLineCount = citation.endLine - citation.startLine + 1;
	if (quoteLines.length !== citedLineCount) {
		return `the quote is ${quoteLines.length} line(s) and the citation covers ${citedLineCount}`;
	}
	for (const [index, quoteLine] of quoteLines.entries()) {
		const lineNumber = citation.startLine + index;
		const diffLine = cited.lines.get(lineNumber);
		if (diffLine === undefined) {
			return `the diff has no [L${lineNumber}] on the ${citation.side ?? "NEW"} side of ${citation.path}`;
		}
		if (!quotesDiffLine(diffLine, quoteLine)) {
			return `[L${lineNumber}] reads ${excerpt(diffLine)}, not ${excerpt(quoteLine)}`;
		}
	}
	return null;
}

/** Match server admission: exact displayed diff text or its text without the single diff marker. */
function quotesDiffLine(diffLine: string, quoted: string): boolean {
	return diffLine.length > 0 && (diffLine === quoted || diffLine.slice(1) === quoted);
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
