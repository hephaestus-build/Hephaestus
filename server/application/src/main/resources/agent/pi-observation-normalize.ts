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
	revision?: string;
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
	const citations = evidence.citations.map((citation: unknown): NormalizedCitation => {
		// A citation that is not an object reads as one with every field missing, which is what the
		// required-field checks below already reject by name.
		const fields: Record<string, unknown> = isRecord(citation) ? citation : {};
		const sourceKind = trimmedText(fields.sourceKind);
		const artifactPath = typeof fields.artifactPath === "string" ? fields.artifactPath : "";
		const path = typeof fields.path === "string" ? fields.path : "";
		const declaredSide = fields.side == null ? null : trimmedText(fields.side).toUpperCase();
		const revision = fields.revision == null ? null : trimmedText(fields.revision);
		if (
			revision !== null &&
			(sourceKind !== "scm.repository.tree" || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/u.test(revision))
		) {
			throw new Error("historical citations require scm.repository.tree and a full commit SHA");
		}
		const startLine = Number(fields.startLine);
		const endLine = fields.endLine == null ? startLine : Number(fields.endLine);
		const quote = withoutCoordinates(
			typeof fields.quote === "string" ? fields.quote : "",
			startLine,
		);
		if (!sourceKind) {
			throw new Error("evidence citation sourceKind is required");
		}
		if (!artifactPath.trim()) {
			throw new Error("evidence citation artifactPath is required");
		}
		if (sourceKind === "scm.repository.tree" && !artifactPath.endsWith("/.git/HEAD")) {
			throw new Error(
				"repository citations must use the captured .git/HEAD artifact and a repository-relative path",
			);
		}
		if (!path.trim()) {
			throw new Error("evidence citation path is required");
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
				"evidence citation startLine is required: the 1-based line of the quoted text in the artifact " +
					"(for a quote of the change, the [L<n>] coordinate of work/change/diff.patch)",
			);
		}
		if (!Number.isSafeInteger(startLine) || startLine <= 0 || startLine > 2_147_483_647) {
			throw new Error(
				`evidence citation startLine must be a positive integer, received ${JSON.stringify(fields.startLine)}; lines are 1-based`,
			);
		}
		if (!Number.isSafeInteger(endLine) || endLine < startLine || endLine > 2_147_483_647) {
			throw new Error(
				`evidence citation endLine must be an integer >= startLine, received ${JSON.stringify(fields.endLine)} with startLine ${startLine}`,
			);
		}
		// An empty quote is a citation by coordinates alone; resolveQuote fills it from the artifact.
		const side: DiffSide | null =
			sourceKind === "scm.pull-request.diff" && (declaredSide === "OLD" || declaredSide === "NEW")
				? declaredSide
				: null;
		return {
			sourceKind,
			artifactPath,
			path,
			...(side == null ? {} : { side }),
			...(revision == null ? {} : { revision }),
			startLine,
			endLine,
			quote,
		};
	});
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

/**
 * A word of a closed vocabulary as the session wrote it — case, and a space or hyphen for the
 * underscore, are not what the vocabulary is about. A word outside it, or none, is answered with the
 * whole list, so the session corrects the field rather than guessing at it.
 */
function parseVocabulary<T extends string>(values: readonly T[], value: unknown, field: string): T {
	const word =
		typeof value === "string"
			? value
					.trim()
					.toUpperCase()
					.replaceAll(/[-\s]+/gu, "_")
			: value;
	const admitted = values.find((candidate) => candidate === word);
	if (admitted === undefined) {
		const missing = nullish(value) ? " (missing)" : "";
		throw new Error(`invalid ${field} '${String(value)}'${missing}: one of ${values.join(", ")}`);
	}
	return admitted;
}

/** A field left out, or written as the word "null", says the same as null. */
function nullish(value: unknown): boolean {
	return value == null || (typeof value === "string" && /^(?:null|none)?$/iu.test(value.trim()));
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

function parseAssessment(
	sent: Record<string, unknown>,
	practiceSlug: string,
	ruledOut: ReadonlySet<string>,
): ObservationAssessment {
	// A presence value written as the status names a presence and nothing else; it is read as such
	// unless the presence field says otherwise.
	const status =
		typeof sent.assessmentStatus === "string" ? sent.assessmentStatus.toUpperCase() : null;
	const statusAsPresence = PRESENCE_VALUES.find((value) => value === status);
	const fields =
		statusAsPresence !== undefined && (nullish(sent.presence) || sent.presence === statusAsPresence)
			? { ...sent, assessmentStatus: "ASSESSED", presence: statusAsPresence }
			: sent;
	const assessmentStatus = parseVocabulary(
		ASSESSMENT_STATUS_VALUES,
		fields.assessmentStatus,
		"assessmentStatus",
	);
	if (assessmentStatus !== "ASSESSED") {
		if (!nullish(fields.presence) || !nullish(fields.assessment) || !nullish(fields.severity)) {
			throw new Error(
				"Unassessed observations require explicit null presence, assessment and severity",
			);
		}
		return { assessmentStatus, presence: null, assessment: null, severity: null };
	}
	const presence = parseVocabulary(PRESENCE_VALUES, fields.presence, "presence");
	const assessment = parseVocabulary(ASSESSMENT_VALUES, fields.assessment, "assessment");
	// Before the severity is asked for: a cell the practice rules out is a wrong cell, and asking for
	// a severity first would have the session decorate the wrong cell rather than leave it.
	refuseRuledOutCell(practiceSlug, presence, assessment, ruledOut);
	// A severity beside a POSITIVE outcome says nothing wrong; it is surplus and dropped, not refused.
	if (deriveOutcome(presence, assessment) === "POSITIVE") {
		return { assessmentStatus, presence, assessment, severity: null };
	}
	if (nullish(fields.severity)) {
		throw new Error(
			`${presence}/${assessment} is a NEGATIVE outcome and needs a severity: one of ${SEVERITY_VALUES.join(", ")}`,
		);
	}
	const severity = parseVocabulary(SEVERITY_VALUES, fields.severity, "severity");
	return { assessmentStatus, presence, assessment, severity };
}

/** The evidence branch an outcome calls for: the search behind an absence, the warrant behind a non-verdict. */
function evidenceBranchOf(
	assessmentStatus: AssessmentStatus,
	presence: Presence | null,
): string | null {
	if (presence === "ABSENT") {
		return "search";
	}
	if (assessmentStatus === "NOT_APPLICABLE") {
		return "inapplicability";
	}
	return assessmentStatus === "UNDETERMINED" ? "undecidability" : null;
}

/** The summary heads the developer's practice page; a phrase, not the rationale. */
export const MAX_SUMMARY_CHARS = 160;

/**
 * The text, or as much of it as ends a sentence within `max` characters — failing that, a clause,
 * when what is kept is at least half the bound; undefined otherwise. A bound on a headline is what
 * the reader's page can show, and a text that runs past it by one clause is worth keeping up to the
 * clause before — the session is told what was kept. A summary refused for a clause too many was
 * refused again at the same length as often as not, so the clause is the cut a headline can take.
 */
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

/** The evidence fields, in the order the session tends to put them beside the observation instead. */
const EVIDENCE_FIELDS = ["citations", "search", "inapplicability", "undecidability"] as const;
/** The search fields, which arrive beside the observation when the session forgets `search` wraps them. */
const SEARCH_FIELDS = ["consulted", "lookedFor", "boundary"] as const;

/**
 * The observation with each field in its home: what belongs under `evidence` and arrived beside it is
 * moved there, and the rationale that arrived under `evidence` is moved beside it. A field present in
 * both places is left for the unknown-field check to name. What moved is echoed in `notes`, so the
 * session sees the shape it should have sent.
 */
function rehomed(observation: Record<string, unknown>, notes: string[]): Record<string, unknown> {
	const beside = new Map(Object.entries(observation));
	const measured = isRecord(observation.evidence) ? observation.evidence : {};
	const evidence = new Map(Object.entries(measured));
	const moved: string[] = [];
	for (const key of EVIDENCE_FIELDS) {
		if (beside.has(key) && !evidence.has(key)) {
			evidence.set(key, beside.get(key));
			beside.delete(key);
			moved.push(key);
		}
	}
	if (!evidence.has("search") && SEARCH_FIELDS.some((key) => beside.has(key))) {
		const search: Record<string, unknown> = {};
		for (const key of SEARCH_FIELDS) {
			if (beside.has(key)) {
				search[key] = beside.get(key);
				beside.delete(key);
			}
		}
		evidence.set("search", search);
		moved.push("search{consulted, lookedFor, boundary}");
	}
	if (evidence.has("evidenceRationale") && !beside.has("evidenceRationale")) {
		beside.set("evidenceRationale", evidence.get("evidenceRationale"));
		evidence.delete("evidenceRationale");
		notes.push("evidenceRationale read from under evidence; it belongs beside evidence, not in it");
	}
	if (moved.length > 0) {
		notes.push(`${moved.join(", ")} read from beside the observation; they belong under evidence`);
	}
	if (moved.length > 0 || beside.has("evidence")) {
		beside.set("evidence", Object.fromEntries(evidence));
	}
	return Object.fromEntries(beside);
}

/**
 * @param ruledOut the cells the practice's Judge section rules out, from {@link cellsRuledOut}; an
 *   assessed observation in one of them is refused before anything else about it is asked for.
 * @param notes receives one line per correction made on the way in — a field moved to its home, a
 *   summary cut at a sentence end — so the caller can echo what was recorded.
 */
export function normalizeObservation(
	raw: unknown,
	ruledOut: ReadonlySet<string> = new Set(),
	notes: string[] = [],
): NormalizedObservation {
	if (!isRecord(raw)) {
		throw new Error("observation must be an object");
	}
	const observation = rehomed(raw, notes);
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
	// Named before the cell is parsed: an item with no slug is usually not an observation at all (a
	// wrapper, a fragment), and "invalid presence" would send the session looking at the wrong field.
	if (!practiceSlug) {
		throw new Error(
			`practiceSlug is required: each item of observations is one observation object (received keys: ${Object.keys(observation).join(", ") || "none"})`,
		);
	}
	const sent = trimmedText(observation.summary).replaceAll(/\s+/gu, " ");
	const reasoning = trimmedText(observation.evidenceRationale);
	const result = parseAssessment(observation, practiceSlug, ruledOut);
	const { assessmentStatus, presence } = result;
	if (!sent) {
		throw new Error("summary is required");
	}
	// The summary is what the developer reads on their practice page, above the practice's own name and
	// with no evidence beside it, so a single word there ("Test") names nothing the practice did not.
	if (!/\S\s+\S/u.test(sent)) {
		throw new Error(
			"summary must say what was observed as a short phrase, not one word — e.g. " +
				"'Debug print left in the request handler'",
		);
	}
	// A summary a clause too long is kept up to its last sentence or clause end within the bound: on
	// the cohort one refusal in five was this, at a median of 175 characters, and each cost the turn a
	// model call.
	const title = boundedAtSentenceEnd(sent, MAX_SUMMARY_CHARS);
	if (title === undefined) {
		throw new Error(
			`summary must be at most ${MAX_SUMMARY_CHARS} characters; this one is ${sent.length} with no sentence or clause end inside the bound. Name the behavior, and keep the reasons for the rationale`,
		);
	}
	if (title !== sent) {
		notes.push(
			`summary was ${sent.length} characters; recorded up to its last sentence or clause end within ${MAX_SUMMARY_CHARS}: "${title}"`,
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
	const expectedBranch = evidenceBranchOf(assessmentStatus, presence);
	// A branch the outcome does not call for is surplus, not a contradiction: a search recorded beside
	// a PRESENT claim says nothing wrong, so it is dropped rather than refused. The branch the outcome
	// does call for is checked by normalizeEvidence, which names what is missing.
	const evidence = normalizeEvidence(
		{
			citations: externalEvidence.citations,
			...(expectedBranch == null ? {} : { [expectedBranch]: externalEvidence[expectedBranch] }),
		},
		assessmentStatus,
		presence,
	);
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
		.map(
			(citation) =>
				`${citation.revision ?? ""}:${citation.path}:${citation.startLine}-${citation.endLine}`,
		)
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
const CELL = String.raw`(?:PRESENT|ABSENT)\/(?:GOOD|BAD)`;
const RULED_OUT_LINE = new RegExp(
	String.raw`^-\s*(${CELL}(?:\s*(?:and|,|or)\s*${CELL})*)\s*(?:\([A-Z]+\))?\s*:\s*no ordinary case\b`,
	"iu",
);

/**
 * The cells a practice's own Judge section rules out, as "- PRESENT/GOOD and ABSENT/GOOD: no
 * ordinary case". A practice whose behaviour in focus is undesirable has no GOOD cell: its absence is
 * ABSENT/BAD, the positive outcome, and a session that reads GOOD as "the outcome is good" records the
 * clean bill as a lapse. The criteria are the one home of that decision, so the guard is read from
 * them; a practice whose Judge section names no such line is not guarded.
 */
export function cellsRuledOut(criteria: string): Set<string> {
	const judge = criteria.split(/^## Judge\s*$/mu)[1]?.split(/^## /mu)[0] ?? "";
	const cells = new Set<string>();
	for (const line of judge.split("\n")) {
		const match = RULED_OUT_LINE.exec(line.trim());
		if (!match) {
			continue;
		}
		for (const cell of match[1]?.match(new RegExp(CELL, "gu")) ?? []) {
			cells.add(cell.toUpperCase());
		}
	}
	return cells;
}

/** An assessed observation lands in a cell its practice names; the ruled-out ones are refused. */
function refuseRuledOutCell(
	practiceSlug: string,
	presence: Presence,
	assessment: Assessment,
	ruledOut: ReadonlySet<string>,
): void {
	const cell = `${presence}/${assessment}`;
	if (!ruledOut.has(cell)) {
		return;
	}
	const all = ["PRESENT/GOOD", "PRESENT/BAD", "ABSENT/GOOD", "ABSENT/BAD"];
	const ordinary = all.filter((candidate) => !ruledOut.has(candidate));
	throw new Error(
		`${cell} is no ordinary case for '${practiceSlug}' — its Judge section names ` +
			`${ordinary.join(" and ")}. assessment says whether the behaviour in focus is desirable, ` +
			`not whether the outcome is good: an undesirable behaviour that is absent is ABSENT/BAD, the ` +
			`positive outcome. Record the cell the evidence supports`,
	);
}

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

/** The most a citation by coordinates alone may record; beyond it, the model names a fragment. */
const COORDINATE_QUOTE_MAX_CHARS = 2000;

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
 * The quote a citation records, read out of the artifact it names, or why none could be. A quote is
 * evidence that the model read these lines, so what is recorded is always the artifact's own bytes:
 * a quote copied with a diff marker, with a non-breaking space read as a space, or as the text a
 * JSON string spells with escapes, is the same reading, and is recorded as the artifact spells it.
 * An empty quote cites by coordinates alone and records the cited lines. Admission verifies the
 * recorded bytes against the artifact, so this is what makes the two checks agree.
 *
 * <p>A refusal says which of the coordinate, the side and the text was wrong, and shows what the
 * cited lines hold, so the next attempt can be copied from them.
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
			return citedText.length > COORDINATE_QUOTE_MAX_CHARS
				? {
						mismatch: `${where} is ${citedText.length} characters; cite fewer lines or quote a fragment of them`,
					}
				: { quote: citedText };
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
	const citedLines = diffLinesOf(citation, content);
	if (typeof citedLines === "string") {
		return { mismatch: citedLines };
	}
	const citedLineCount = citation.endLine - citation.startLine + 1;
	const quoteLines = quote === "" ? null : quote.split(/\r\n|\r|\n/u);
	const spanMatches = quoteLines === null || quoteLines.length === citedLineCount;
	const atCited = spanMatches
		? diffLinesMatch(citedLines, citation.startLine, citedLineCount, quoteLines)
		: {
				mismatch: `the quote is ${quoteLines.length} line(s) and the citation covers ${citedLineCount}`,
			};
	if ("text" in atCited) {
		return quoteLines === null && atCited.text.length > COORDINATE_QUOTE_MAX_CHARS
			? {
					mismatch: `[L${citation.startLine}]-[L${citation.endLine}] is ${atCited.text.length} characters; cite fewer lines or quote a fragment of them`,
				}
			: { quote: atCited.text };
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

/** A quote as recorded, with corrected coordinates when the text was found elsewhere than cited. */
export interface ResolvedQuote {
	quote: string;
	startLine?: number;
	endLine?: number;
}

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

/**
 * The annotated diff's lines on the cited side of the cited path, by line number, or why they could
 * not be read.
 */
function diffLinesOf(citation: NormalizedCitation, content: string): Map<number, string> | string {
	let oldPath: string | null = null;
	let newPath: string | null = null;
	const citedLines = new Map<number, string>();
	for (const storedLine of content.split("\n")) {
		// Both groups are mandatory, so binding them here is what lets the annotated branch below turn
		// on a value the compiler has seen rather than on the match object being non-null.
		const [, annotatedLineNumber, annotatedText] =
			/^\[L(?<line>\d+)\] (?<text>[\s\S]*)$/u.exec(storedLine) ?? [];
		const line = annotatedText ?? storedLine;
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
			const path = side === "OLD" ? oldPath : newPath;
			if (side === citation.side && path === citation.path) {
				citedLines.set(lineNumber, line);
			}
		}
	}
	return citedLines;
}

/**
 * Whether a quote is the diff line it claims: as displayed, or without the marker, or with its
 * horizontal whitespace read differently. The coordinate has already pinned which line is compared,
 * so two lines cannot be confused by spacing; the text is what a citation proves was read, and what
 * is recorded is the line's own bytes.
 */
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
