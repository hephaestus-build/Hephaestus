// ── Vocabularies shared with Java ────────────────────────────────────────────
// Each list mirrors an enum on the server. They are hand-maintained on both sides, so
// AgentVocabularySyncTest parses these literals and asserts equality with the Java enum's
// values(), preventing the runtime and persistence contracts from accepting different labels.
export const OUTCOME_VALUES = ["MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED"] as const;
export const SEVERITY_VALUES = ["CRITICAL", "MAJOR", "MINOR", "INFO"] as const;

// The vocabularies above are the values; these are the types every consumer spells them with. They are
// derived from the arrays rather than written twice, so the arrays stay the single thing Java is synced
// against and a value cannot be added to one without being added to the other.
export type Outcome = (typeof OUTCOME_VALUES)[number];
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

/** The recorded scope of a search that came up empty — the warrant an absence claim owes. */
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
 * present is decided by outcome and enforced in {@link normalizeEvidence}; the optionality here is the
 * shape, not the rule.
 */
export interface NormalizedEvidence {
	citations: NormalizedCitation[];
	search?: RecordedSearch;
	inapplicability?: RecordedInapplicability;
	undecidability?: RecordedUndecidability;
}

/** One measurement, checked. This is what reaches result.json and, from there, Java. */
export type NormalizedObservation = ObservationOutcome & {
	practiceSlug: string;
	summary: string;
	evidence: NormalizedEvidence;
	evidenceRationale: string;
};

export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

export const OUTCOME_DESCRIPTIONS: Record<Outcome, string> = {
	MET: "The applicable practice standard is met in the captured evidence. Cite the evidence. Claims based on absence require a bounded, complete search; do not infer mastery or unseen work.",
	NOT_MET:
		"The applicable practice standard is not met. Cite the contradiction or record a bounded search for the required work. Supply severity and explain the consequence.",
	NOT_APPLICABLE:
		"A concrete fact rules out the practice's prerequisite occasion. Record the prerequisite and exclusion in evidence.inapplicability. This is not missing or incomplete evidence.",
	UNDETERMINED:
		"Relevant evidence was captured and read, but does not settle whether the applicable standard is met. Record the open question and what would settle it in evidence.undecidability. A capture failure is a review readiness failure, not an observation.",
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
	INFO: "An advisory, low-impact problem. Still a NOT_MET outcome; strengths and unassessed observations require null severity.",
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

/**
 * How a citation is completed when it leaves out what the run already knows, and where the repairs made
 * on the way in are reported, one line each.
 */
export interface CitationRepairs {
	/** The source kind the manifest staged an artifact under; undefined when it staged no such artifact. */
	sourceOf?: (artifactPath: string) => string | undefined;
	notes?: string[];
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

/**
 * What the manifest already knows is filled in, not asked for: the artifact a staged record is its own
 * path, and the source an artifact was staged by. Each fill is noted.
 */
function completedFromManifest(
	fields: Record<string, unknown>,
	path: string,
	which: string,
	sourceOf: CitationRepairs["sourceOf"],
	notes: string[],
): { artifactPath: string; sourceKind: string } {
	let artifactPath = typeof fields.artifactPath === "string" ? fields.artifactPath : "";
	if (!artifactPath.trim() && path.trim() && sourceOf?.(path) !== undefined) {
		artifactPath = path;
		notes.push(`${which}artifactPath filled in as ${path}, the staged record the path names`);
	}
	let sourceKind = trimmedText(fields.sourceKind);
	const staged = artifactPath.trim() ? sourceOf?.(artifactPath) : undefined;
	if (!sourceKind && staged !== undefined) {
		sourceKind = staged;
		notes.push(`${which}sourceKind filled in as ${staged}, the source that staged ${artifactPath}`);
	}
	return { artifactPath, sourceKind };
}

export function normalizeEvidence(
	evidence: unknown,
	outcome: Outcome,
	{ sourceOf, notes = [] }: CitationRepairs = {},
): NormalizedEvidence {
	if (
		!isRecord(evidence) ||
		!Array.isArray(evidence.citations) ||
		evidence.citations.length === 0
	) {
		throw new Error("evidence citations are required");
	}
	if (evidence.inapplicability != null && outcome !== "NOT_APPLICABLE") {
		throw new Error("evidence.inapplicability is permitted only for NOT_APPLICABLE");
	}
	if (evidence.undecidability != null && outcome !== "UNDETERMINED") {
		throw new Error("evidence.undecidability is permitted only for UNDETERMINED");
	}
	if (evidence.search != null && outcome !== "MET" && outcome !== "NOT_MET") {
		throw new Error("evidence.search is permitted only for MET or NOT_MET");
	}
	const many = evidence.citations.length > 1;
	const citations = evidence.citations.map((citation: unknown, index): NormalizedCitation => {
		// With several citations, a problem names the one it is about.
		const which = many ? `citation ${index + 1}: ` : "";
		// A citation that is not an object reads as one with every field missing, which is what the
		// required-field checks below already reject by name.
		const fields: Record<string, unknown> = isRecord(citation) ? citation : {};
		const given = typeof fields.path === "string" ? fields.path : "";
		const { artifactPath, sourceKind } = completedFromManifest(
			fields,
			given,
			which,
			sourceOf,
			notes,
		);
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
			throw new Error(`${which}evidence citation sourceKind is required`);
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
	// Inapplicability is a positive claim about scope, not an uncertain assessment.
	if (outcome === "NOT_APPLICABLE") {
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
	if (outcome === "UNDETERMINED") {
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
 * {@link normalizeInapplicability}: each observation that makes a claim beyond its citations has to ground it.
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
	const admitted = values.find((candidate) => candidate === value);
	if (admitted === undefined) {
		const missing = nullish(value) ? " (missing)" : "";
		throw new Error(`invalid ${field} '${String(value)}'${missing}: one of ${values.join(", ")}`);
	}
	return admitted;
}

function nullish(value: unknown): boolean {
	return value == null;
}

type ObservationOutcome =
	| { outcome: "NOT_MET"; severity: Severity }
	| { outcome: "MET" | "NOT_APPLICABLE" | "UNDETERMINED"; severity: null };

function parseOutcome(sent: Record<string, unknown>): ObservationOutcome {
	const outcome = parseVocabulary(OUTCOME_VALUES, sent.outcome, "outcome");
	if (outcome === "NOT_MET") {
		return { outcome, severity: parseVocabulary(SEVERITY_VALUES, sent.severity, "severity") };
	}
	if (!nullish(sent.severity)) {
		throw new Error("Severity is permitted only for NOT_MET");
	}
	return { outcome, severity: null };
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

/** The evidence fields, in the order the session tends to put them beside the observation instead. */
const EVIDENCE_FIELDS = ["citations", "search", "inapplicability", "undecidability"] as const;
/**
 * The fields of each evidence branch, which arrive loose — beside the observation or straight under
 * evidence — when the session forgets the branch that wraps them. `consulted` is shared by two, so a
 * branch is named by its own fields; a lone `consulted` belongs to the search.
 */
const BRANCH_FIELDS = [
	{ branch: "inapplicability", own: ["subject", "ruledOutBy"], consulted: true },
	{ branch: "undecidability", own: ["openQuestion", "wouldSettleIt"], consulted: false },
	{ branch: "search", own: ["lookedFor", "boundary", "consulted"], consulted: true },
] as const;

/** Move misplaced evidence fields and report corrections; conflicting copies remain invalid. */
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
	const loose = (key: string) => beside.has(key) || evidence.has(key);
	const take = (key: string) => {
		if (beside.has(key) && evidence.has(key)) {
			throw new Error(
				`${key} was sent both beside the observation and under evidence; send one value`,
			);
		}
		const value = beside.has(key) ? beside.get(key) : evidence.get(key);
		beside.delete(key);
		evidence.delete(key);
		return value;
	};
	for (const { branch, own, consulted } of BRANCH_FIELDS) {
		if (evidence.has(branch) || !own.some(loose)) {
			continue;
		}
		const fields =
			consulted && !own.some((key) => key === "consulted") ? [...own, "consulted"] : own;
		const value = Object.fromEntries(fields.filter(loose).map((key) => [key, take(key)]));
		evidence.set(branch, value);
		moved.push(`${branch}{${Object.keys(value).join(", ")}}`);
	}
	if (evidence.has("evidenceRationale") && !beside.has("evidenceRationale")) {
		beside.set("evidenceRationale", evidence.get("evidenceRationale"));
		evidence.delete("evidenceRationale");
		notes.push(
			"evidenceRationale read from under evidence and recorded beside it, where it belongs; nothing to resend",
		);
	}
	if (moved.length > 0) {
		notes.push(
			`${moved.join(", ")} read from where they were sent and recorded under evidence, where they belong; nothing to resend`,
		);
	}
	if (moved.length > 0 || beside.has("evidence")) {
		beside.set("evidence", Object.fromEntries(evidence));
	}
	return Object.fromEntries(beside);
}

/**
 * Validate the model output before recording an observation.
 * @param notes receives one line per correction made on the way in — a field moved to its home, a
 *   value filled in from the manifest — so the caller can echo what was recorded.
 */
export function normalizeObservation(
	raw: unknown,
	notes: string[] = [],
	sourceOf?: (artifactPath: string) => string | undefined,
): NormalizedObservation {
	if (!isRecord(raw)) {
		throw new Error("observation must be an object");
	}
	const observation = rehomed(raw, notes);
	const allowed = new Set([
		"practiceSlug",
		"summary",
		"outcome",
		"severity",
		"evidence",
		"evidenceRationale",
	]);
	// A stray key with no value (`practiceSlug2: null`) carries nothing: dropped, and said so.
	const empty = Object.keys(observation).filter(
		(key) => !allowed.has(key) && (nullish(observation[key]) || observation[key] === ""),
	);
	for (const key of empty) {
		Reflect.deleteProperty(observation, key);
	}
	if (empty.length > 0) {
		notes.push(`empty field(s) ${empty.join(", ")} dropped`);
	}
	const unknownFields = Object.keys(observation).filter((key) => !allowed.has(key));
	if (unknownFields.length > 0) {
		throw new Error(
			`unknown observation field(s): ${unknownFields.join(", ")}; an observation has only ${[...allowed].join(", ")}`,
		);
	}
	const practiceSlug = normalizePracticeSlug(observation.practiceSlug);
	if (!practiceSlug) {
		throw new Error(
			`practiceSlug is required: each item of observations is one observation object (received keys: ${Object.keys(observation).join(", ") || "none"})`,
		);
	}
	const sent = trimmedText(observation.summary).replaceAll(/\s+/gu, " ");
	const reasoning = trimmedText(observation.evidenceRationale);
	// Every problem of the observation is named at once: one per resend costs a model call each.
	const problems: string[] = [];
	const attempt = <T>(check: () => T): T | undefined => {
		try {
			return check();
		} catch (error) {
			problems.push(error instanceof Error ? error.message : String(error));
			return undefined;
		}
	};
	const result = attempt(() => parseOutcome(observation));
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
			`summary must be at most ${MAX_SUMMARY_CHARS} characters; this one is ${sent.length}. Resend the ` +
				"observation with a shorter summary that reads as a complete phrase on its own — name the " +
				"behavior, and move titles, quotes and reasons into evidenceRationale",
		);
	}
	if (!reasoning) {
		problems.push("evidenceRationale is required");
	}
	const externalEvidence: Record<string, unknown> = isRecord(observation.evidence)
		? observation.evidence
		: {};
	const evidenceFields = new Set(["citations", "search", "inapplicability", "undecidability"]);
	const unknownEvidence = Object.keys(externalEvidence).filter((key) => !evidenceFields.has(key));
	if (unknownEvidence.length > 0) {
		problems.push(`unknown evidence field(s): ${unknownEvidence.join(", ")}`);
	}
	const evidence =
		result === undefined
			? undefined
			: attempt(() => normalizeEvidence(externalEvidence, result.outcome, { sourceOf, notes }));
	if (problems.length > 0 || result === undefined || evidence === undefined) {
		throw new Error(problems.join("; also: "));
	}
	return {
		practiceSlug,
		summary: sent,
		...result,
		evidence,
		evidenceRationale: reasoning,
	};
}

export function normalizePracticeSlug(value: unknown): string {
	return trimmedText(value).toLowerCase().replaceAll("_", "-");
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
					`artifact '${citation.artifactPath}' was not staged by '${sourceKind}'; ` +
						`choose a staged artifact for this source from the task-declared manifest.${derived}`,
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
 * MET based on absence additionally requires an exhaustive source policy: a positive absence claim needs a
 * closed search boundary. These checks validate the declared search, not whether the model read it.
 */
export function validateSearchScope(
	observation: NormalizedObservation,
	exhaustiveSourceKinds: ReadonlySet<string>,
	availableSourceKinds: ReadonlySet<string>,
): void {
	const { search } = observation.evidence;
	if (!search) {
		return;
	}
	if (observation.outcome === "MET" && exhaustiveSourceKinds.size === 0) {
		throw new Error(
			`MET for '${observation.practiceSlug}' rests on an absence claim, and the practice declares no ` +
				`source it searches exhaustively, so no search can bound that claim. Record what the evidence ` +
				`does show: MET from cited evidence, NOT_APPLICABLE when the work gives the practice no ` +
				`occasion, or UNDETERMINED with what would settle it`,
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
			`an absence claim for '${observation.practiceSlug}' rests on searching ${unsearched.join(", ")} as ` +
				`well: add ${unsearched.length > 1 ? "them" : "it"} to evidence.search.consulted once you have ` +
				`searched ${unsearched.length > 1 ? "them" : "it"}`,
		);
	}
}

export function validateInapplicabilityScope(
	observation: NormalizedObservation,
	availableSourceKinds: ReadonlySet<string>,
): void {
	if (observation.outcome !== "NOT_APPLICABLE") {
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

/** The thread a conversation review judges. Each turn states whether the reviewed participant wrote it. */
export const CONVERSATION_THREAD = "context/conversation_thread.json";

/**
 * Whether a citation lies inside a turn of the conversation record marked `underReview: true`.
 * Admission applies the same rule to a NOT_MET of a conversation review.
 */
export function citesReviewedTurn(
	citations: readonly NormalizedCitation[],
	thread: string,
): boolean {
	const turns = reviewedTurnLines(thread);
	return citations.some(
		(citation) =>
			citation.artifactPath === CONVERSATION_THREAD &&
			turns.some((turn) => turn.first <= citation.startLine && citation.endLine <= turn.last),
	);
}

/** The first and last line of each turn under review, read from the record as written. */
function reviewedTurnLines(thread: string): { first: number; last: number }[] {
	let parsed: unknown;
	try {
		parsed = JSON.parse(thread);
	} catch {
		return [];
	}
	const messages = isRecord(parsed) && Array.isArray(parsed.messages) ? parsed.messages : [];
	// JSON.parse keeps no positions, so one pass finds each turn's lines, in the same order.
	const spans: { first: number; last: number }[] = [];
	let line = 1;
	let depth = 0;
	let inString = false;
	let escaped = false;
	let stringStart = 0;
	let lastString = "";
	let key = "";
	let inMessages = false;
	let first = 0;
	for (let index = 0; index < thread.length; index += 1) {
		const char = thread[index];
		if (char === "\n") {
			line += 1;
		}
		if (inString) {
			if (escaped) {
				escaped = false;
			} else if (char === "\\") {
				escaped = true;
			} else if (char === '"') {
				inString = false;
				lastString = thread.slice(stringStart, index);
			}
			continue;
		}
		if (char === '"') {
			inString = true;
			stringStart = index + 1;
		} else if (char === ":" && depth === 1) {
			key = lastString;
		} else if (char === "{" || char === "[") {
			depth += 1;
			if (char === "[" && depth === 2 && key === "messages") {
				inMessages = true;
			}
			if (char === "{" && inMessages && depth === 3) {
				first = line;
			}
		} else if (char === "}" || char === "]") {
			if (char === "}" && inMessages && depth === 3) {
				spans.push({ first, last: line });
			}
			if (char === "]" && inMessages && depth === 2) {
				inMessages = false;
			}
			depth -= 1;
		}
	}
	return spans.filter((_, index) => {
		const message: unknown = messages[index];
		return isRecord(message) && message.underReview === true;
	});
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
	if (!("text" in atCited)) {
		const atView = readAtViewLines(lines, citedLines, citation, quoteLines);
		if (atView !== null) {
			return atView;
		}
	}
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

/** A quote as recorded, with corrected coordinates when the text was found elsewhere than cited. */
export interface ResolvedQuote {
	quote: string;
	startLine?: number;
	endLine?: number;
	/** The changed file and side the quote is on, when the citation named the diff view itself. */
	path?: string;
	side?: DiffSide;
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

/** A quote found in the artifact, so it always has the lines it is on. */
type LocatedQuote = ResolvedQuote & { startLine: number; endLine: number };

/** Every place the quote occurs in the whole artifact, as written there, with its line range. */
function locateAsWritten(content: string, quote: string): LocatedQuote[] {
	const hits: LocatedQuote[] = [];
	for (const candidate of [quote, JSON.stringify(quote).slice(1, -1)]) {
		for (const { start, end } of occurrencesAsWritten(content, candidate)) {
			const written = content.slice(start, end);
			const startLine = content.slice(0, start).split("\n").length;
			const endLine = startLine + written.split("\n").length - 1;
			if (!hits.some((hit) => hit.startLine === startLine)) {
				hits.push({ quote: written, startLine, endLine });
			}
		}
	}
	return hits;
}

/**
 * The text as the artifact writes it, when the quote is a reading of it: verbatim, or with the
 * escapes a JSON string uses, or with a non-breaking space where the quote has a space. Null when
 * the text holds no such reading.
 */
function findAsWritten(text: string, quote: string): string | null {
	for (const candidate of [quote, JSON.stringify(quote).slice(1, -1)]) {
		if (text.includes(candidate)) {
			return candidate;
		}
		const [first] = occurrencesAsWritten(text, candidate);
		if (first !== undefined) {
			return text.slice(first.start, first.end);
		}
	}
	return null;
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
	{ lineCount: number; at: ReadonlyMap<number, { key: string; line: number }> }
>();

/**
 * The annotated diff's lines by file and side (keyed by {@link diffKey}), each by its line number, or
 * why they could not be read.
 */
function annotatedDiff(content: string): Map<string, Map<number, string>> | string {
	let oldPath: string | null = null;
	let newPath: string | null = null;
	const byFile = new Map<string, Map<number, string>>();
	const atViewLine = new Map<number, { key: string; line: number }>();
	const viewed = content.split("\n");
	viewLines.set(byFile, { lineCount: viewed.length, at: atViewLine });
	// A file the diff names without a hunk — binary, renamed without edits, mode only — is known with no
	// lines, so a citation of it is told why rather than that the change does not touch it.
	const known = (side: DiffSide, filePath: string) => {
		const key = diffKey(filePath, side);
		if (!byFile.has(key)) {
			byFile.set(key, new Map());
		}
	};
	for (const [index, storedLine] of viewed.entries()) {
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
 * The coordinates may be lines of diff.patch itself, as a numbered view prints them. With a quote, the view's
 * line names the cited file's line that must hold it. With coordinates alone, the range is a stretch of the view,
 * the file's header and hunks included, and the cited file's lines among its lines, from the first to the last,
 * are what it cites: the view's lines are not the file's, since headers and the other side's lines interleave.
 * Null when the view names no line of the cited file there, or the lines do not hold what was cited.
 */
function readAtViewLines(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	citedLines: ReadonlyMap<number, string>,
	citation: NormalizedCitation,
	quoteLines: readonly string[] | null,
): ResolvedQuote | null {
	const view = viewLines.get(lines);
	if (view === undefined) {
		return null;
	}
	const key = diffKey(citation.path, citation.side);
	if (quoteLines !== null) {
		const viewed = view.at.get(citation.startLine);
		if (viewed?.key !== key) {
			return null;
		}
		const atView = diffLinesMatch(citedLines, viewed.line, quoteLines.length, quoteLines);
		return "text" in atView
			? { quote: atView.text, startLine: viewed.line, endLine: viewed.line + quoteLines.length - 1 }
			: null;
	}
	// A range past the view's last line names no line of it, however far it reaches.
	if (citation.endLine > view.lineCount) {
		return null;
	}
	const inRange: number[] = [];
	for (let at = citation.startLine; at <= citation.endLine; at += 1) {
		const viewed = view.at.get(at);
		if (viewed?.key === key) {
			inRange.push(viewed.line);
		}
	}
	const [first] = inRange;
	const last = inRange.at(-1);
	if (first === undefined || last === undefined) {
		return null;
	}
	const atView = diffLinesMatch(citedLines, first, last - first + 1, null);
	return "text" in atView && atView.text.length <= COORDINATE_QUOTE_MAX_CHARS
		? { quote: atView.text, startLine: first, endLine: last }
		: null;
}

/**
 * A range of the diff view by coordinates alone, when the view's lines in it are lines of one changed file:
 * what it cites is that file's lines, its NEW side when the range adds a line and its OLD side when it only
 * removes. Unchanged lines are read on the NEW side, so they do not decide. A range across files names no
 * one file.
 */
function viewRangeOfOneFile(
	lines: ReadonlyMap<string, ReadonlyMap<number, string>>,
	citation: NormalizedCitation,
): ResolvedQuote | null {
	const view = viewLines.get(lines);
	// A range past the view's last line names no line of it, however far it reaches.
	if (view === undefined || citation.endLine > view.lineCount) {
		return null;
	}
	const keys = new Set<string>();
	let adds = false;
	for (let at = citation.startLine; at <= citation.endLine; at += 1) {
		const viewed = view.at.get(at);
		if (viewed !== undefined) {
			keys.add(viewed.key);
			adds ||= lines.get(viewed.key)?.get(viewed.line)?.startsWith("+") === true;
		}
	}
	const paths = new Set([...keys].map((key) => key.slice(key.indexOf(" ") + 1)));
	const [path] = paths;
	if (paths.size !== 1 || path === undefined) {
		return null;
	}
	const side = adds || !keys.has(diffKey(path, "OLD")) ? "NEW" : "OLD";
	const fileLines = lines.get(diffKey(path, side));
	if (fileLines === undefined) {
		return null;
	}
	const asFile = { ...citation, path, side } as const;
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

const IS_HORIZONTAL_SPACE = new RegExp(`^${HORIZONTAL_SPACE}$`, "u");

/** What {@link flattened} leaves out: horizontal spacing, and a `\r` that ends a line before its `\n`. */
const FLATTENED_OUT = new RegExp(String.raw`${HORIZONTAL_SPACE}|\r(?=\n)`, "gu");

/**
 * Text without its horizontal spacing and with `\r\n` read as `\n`, and for each character kept, its
 * offset in the text.
 */
function flattened(text: string): { flat: string; offsets: Uint32Array } {
	const offsets = new Uint32Array(text.length);
	let kept = 0;
	for (let offset = 0; offset < text.length; offset += 1) {
		const character = text[offset] ?? "";
		if (
			!IS_HORIZONTAL_SPACE.test(character) &&
			!(character === "\r" && text[offset + 1] === "\n")
		) {
			offsets[kept] = offset;
			kept += 1;
		}
	}
	return { flat: text.replaceAll(FLATTENED_OUT, ""), offsets: offsets.subarray(0, kept) };
}

/**
 * Where the quote occurs in the text as the artifact may write it, leftmost first and never
 * overlapping: the same characters and line breaks, each break `\n` or `\r\n`, with horizontal spacing
 * in the text only where the quote has spacing or a line break beside it. Spacing in the quote may be
 * absent from the text. The whole quote, spacing removed, is found by plain search in the text, spacing
 * removed; a place found is kept only when the text's spacing there falls where the quote allows it.
 * Each place found that is refused costs up to one pass over the quote.
 */
function* occurrencesAsWritten(
	text: string,
	candidate: string,
): Generator<{ start: number; end: number }> {
	const quote = flattened(candidate);
	if (quote.flat === "") {
		return;
	}
	// Whether the quote has spacing before each of its characters, and after its last.
	const spaced = Array.from(
		quote.offsets,
		(offset, index) => offset > (quote.offsets[index - 1] ?? -1) + 1,
	);
	const spacedAfter = (quote.offsets.at(-1) ?? 0) < candidate.length - 1;
	// A quote that ends in a bare `\r` may end where a `\r\n` of the text does, whose `\r` the text's
	// flat form leaves out; so that `\r` is sought in the text itself, after the rest is found.
	const endsInReturn = quote.flat.endsWith("\r");
	const sought = endsInReturn ? quote.flat.slice(0, -1) : quote.flat;
	if (sought === "") {
		return;
	}
	const lastIndex = sought.length - 1;
	const source = flattened(text);
	const allowedBefore = (index: number) =>
		spaced[index] === true || quote.flat[index] === "\n" || quote.flat[index - 1] === "\n";
	let limit = 0;
	let found = source.flat.indexOf(sought);
	while (found !== -1) {
		const at = found;
		const offsetOf = (index: number) => source.offsets[at + index] ?? text.length;
		let fits = true;
		for (let index = 1; fits && index <= lastIndex; index += 1) {
			fits = offsetOf(index) === offsetOf(index - 1) + 1 || allowedBefore(index);
		}
		let end = offsetOf(lastIndex) + 1;
		if (fits && endsInReturn) {
			if (allowedBefore(lastIndex + 1)) {
				while (IS_HORIZONTAL_SPACE.test(text[end] ?? "")) {
					end += 1;
				}
			}
			fits = text[end] === "\r";
			end += 1;
		}
		if (!fits) {
			found = source.flat.indexOf(sought, at + 1);
			continue;
		}
		let start = offsetOf(0);
		if (allowedBefore(0)) {
			start -= quote.flat.startsWith("\n") && start > limit && text[start - 1] === "\r" ? 1 : 0;
			while (start > limit && IS_HORIZONTAL_SPACE.test(text[start - 1] ?? "")) {
				start -= 1;
			}
		}
		if (spacedAfter || quote.flat.endsWith("\n")) {
			while (IS_HORIZONTAL_SPACE.test(text[end] ?? "")) {
				end += 1;
			}
		}
		yield { start, end };
		limit = end;
		// The search resumes at the first character the occurrence did not take, its bare `\r` included.
		let next = at + sought.length;
		while ((source.offsets[next] ?? text.length) < end) {
			next += 1;
		}
		found = source.flat.indexOf(sought, next);
	}
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
