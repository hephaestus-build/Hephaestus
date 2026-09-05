// ── Vocabularies shared with Java ────────────────────────────────────────────
// Each list mirrors an enum on the server. They are hand-maintained on both sides, so
// AgentVocabularySyncTest parses these literals and asserts equality with the Java enum's
// values(); it exists because presence drifted once and silently laundered INCONCLUSIVE into
// NOT_APPLICABLE. Add a value here and to the Java enum in the same change, or the test fails.
export const PRESENCE_VALUES = ["PRESENT", "ABSENT", "NOT_APPLICABLE", "INCONCLUSIVE"] as const;
export const ASSESSMENT_VALUES = ["GOOD", "BAD"] as const;
export const SEVERITY_VALUES = ["CRITICAL", "MAJOR", "MINOR", "INFO"] as const;

// The vocabularies above are the values; these are the types every consumer spells them with. They are
// derived from the arrays rather than written twice, so the arrays stay the single thing Java is synced
// against and a value cannot be added to one without being added to the other.
export type Presence = (typeof PRESENCE_VALUES)[number];
export type Assessment = (typeof ASSESSMENT_VALUES)[number];
export type Severity = (typeof SEVERITY_VALUES)[number];

/**
 * The presences that carry a good/bad direction. Naming it as a type is what lets the observation
 * shape below say, structurally, what the DB CHECK chk_observation_presence_assessment says: a
 * valenced presence always carries an assessment and a valence-free one never does.
 */
export type ValencedPresence = Extract<Presence, "PRESENT" | "ABSENT">;

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

/** What the evidence left open — the warrant an INCONCLUSIVE observation owes. */
export interface RecordedUndecidability {
	openQuestion: string;
	wouldSettleIt: string;
}

/**
 * Citations plus, at most, the one extra warrant this observation's presence requires. Which branch is
 * present is decided by presence and enforced in {@link normalizeEvidence}; the optionality here is the
 * shape, not the rule.
 */
export interface NormalizedEvidence {
	citations: NormalizedCitation[];
	search?: RecordedSearch;
	inapplicability?: RecordedInapplicability;
	undecidability?: RecordedUndecidability;
}

/** One measurement, checked. This is what reaches result.json and, from there, Java. */
export interface NormalizedObservation {
	practiceSlug: string;
	summary: string;
	presence: Presence;
	severity: Severity;
	evidence: NormalizedEvidence;
	evidenceRationale: string;
	assessment?: Assessment;
}

/**
 * The narrowing every reader of model-authored or file-authored JSON in this runtime starts from.
 *
 * <p>Exported because pi-runner.ts parses the same class of input — a task envelope, a composition
 * request, an admission response — and one guard both modules share cannot drift from itself.
 */
export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

// ── What each value MEANS, at the moment of choosing between two of them ─────
//
// A bare enum hands the model four words and no way to tell them apart, and it will then reach for
// whichever one reads as the safe default — which for a four-valued presence is always the one that
// looks like N/A on a form. So each value is described by its DISCRIMINATOR against its nearest
// neighbour rather than by a definition that stands alone: the choice is only ever made in a pair.
//
// These are the text the tool schema carries (pi-runner.ts builds `description` from them), so they
// are the last thing the model reads before it fills the field in.

/**
 * Presence answers ONE question for every practice in the catalogue: is the behaviour this practice
 * names in the work? That framing is what makes the four values decidable without knowing which
 * practice is being scored — a missing good behaviour is an ABSENT good behaviour, not a PRESENT bad
 * one, whatever the practice happens to be about.
 */
export const PRESENCE_DESCRIPTIONS: Record<Presence, string> = {
	PRESENT:
		"The behaviour this practice names is in the work and you can point at it — your citation shows the " +
		"thing itself. Pick this over ABSENT when the behaviour occurred, and over INCONCLUSIVE when the " +
		"evidence settles the question.",
	ABSENT:
		"The occasion for the behaviour arose in this work and the behaviour is not there. Pick this over " +
		"NOT_APPLICABLE when there WAS a place the behaviour belonged — NOT_APPLICABLE says no such place " +
		"existed. A missing good behaviour is ABSENT (a gap), never PRESENT with a BAD assessment. It is " +
		"equally the value for a harmful behaviour that could have appeared and did not, which is a strength " +
		"(assessment=GOOD) and not a gap — but only where the practice bounds the corpus it searches, because " +
		"a clean surface is provable only over a corpus you covered whole. Requires evidence.search: an " +
		"absence is a claim about a corpus, and it holds only over the corpus you searched.",
	NOT_APPLICABLE:
		"This work has no subject for this practice: the occasion for the behaviour never arose, so there was " +
		"nothing here to judge. Pick this over INCONCLUSIVE only when you can name the fact about THIS work " +
		"that rules the subject out (evidence.inapplicability.ruledOutBy); if the honest answer to 'why does " +
		"it not apply' is 'I could not tell', the presence is INCONCLUSIVE. This value enters the developer's " +
		"long-running record as 'there was nothing to see here', so it is an assertion about their work and " +
		"never a way to abstain.",
	INCONCLUSIVE:
		"There IS a subject here, you read the evidence this practice needs, and it does not settle the " +
		"question either way. Pick this over NOT_APPLICABLE whenever the practice's subject does occur in the " +
		"work but the evidence is not dispositive, and over a speculative ABSENT whenever you could not search " +
		"far enough to say the behaviour is really missing. It carries no assessment. It does require " +
		"evidence.undecidability — the question left open, and the evidence that would have settled it — " +
		"because uncertainty is a measurement only when it says what it could not measure.",
};

/** Valence, and only valence: whether what presence recorded reflects well or badly on the work. */
export const ASSESSMENT_DESCRIPTIONS: Record<Assessment, string> = {
	GOOD:
		"What you saw reflects well and is worth acknowledging. With PRESENT: the good behaviour the practice " +
		"names is there. With ABSENT: a harmful behaviour that could have appeared did not.",
	BAD:
		"What you saw is a problem the developer should act on. With PRESENT: a harmful behaviour is in the " +
		"work (commission). With ABSENT: a good behaviour that belonged here is missing (omission).",
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
	INFO: "An observation with no required edit. This is the band for anything that is not a defect.",
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
			if (!description) throw new Error(`vocabulary value '${value}' has no description`);
			return `${value} — ${description}`;
		})
		.join("\n");
}

/**
 * Whether an observation with this presence carries a good/bad direction — the exact twin of
 * Presence.carriesValence() in Java and of the DB CHECK chk_observation_presence_assessment.
 * Asked rather than open-coding `!== "NOT_APPLICABLE"`, which silently demands an assessment on
 * INCONCLUSIVE and rejects the honest answer.
 */
export function carriesValence(presence: string): presence is ValencedPresence {
	return presence === "PRESENT" || presence === "ABSENT";
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
	if (typeof value === "string") return value.trim();
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

/**
 * The recorded scope of a search that came up empty. An ABSENT observation is a universal claim —
 * "it is not there" — and the one thing a fragment of a corpus can never support. Narrating the
 * search in `reasoning` is the honour-system version of this; a structured block is what a validator
 * can actually hold against the domain the practice declared.
 */
export function normalizeSearch(search: unknown): RecordedSearch {
	if (!isRecord(search)) throw new Error("search is required");
	const consulted = trimmedStrings(search.consulted);
	const lookedFor = trimmedText(search.lookedFor);
	const boundary = trimmedText(search.boundary);
	if (consulted.length === 0)
		throw new Error("search.consulted must name at least one source you searched");
	if (!lookedFor) throw new Error("search.lookedFor is required");
	if (!boundary) throw new Error("search.boundary is required");
	return { consulted: [...new Set(consulted)].toSorted(), lookedFor, boundary };
}

/**
 * The positive claim behind a NOT_APPLICABLE observation: what this practice looks for, and the fact about
 * this work that means it cannot be here.
 *
 * NOT_APPLICABLE is the one presence that costs nothing to say. A PRESENT observation is warranted by the
 * citation that shows the thing; an ABSENT one has to record the search that came up empty. NOT_APPLICABLE
 * needs a citation too, but any citation will do — quoting a line proves nothing about a practice having no
 * subject — so it is the cheapest answer available and it is where uncertainty drains to. Every form on
 * earth uses N/A for "no answer", and a model reaches for it the same way.
 *
 * That matters because NOT_APPLICABLE is not a shrug: it is a claim about the developer's work, entering a
 * long-running record of how a person works as "there was nothing here to see". The honest answer when you
 * cannot tell is INCONCLUSIVE. Naming the ground makes the difference visible to the model while it is
 * choosing, which is the only moment the choice can be made well.
 */
export function normalizeInapplicability(inapplicability: unknown): RecordedInapplicability {
	if (!isRecord(inapplicability)) throw new Error("inapplicability is required");
	const consulted = trimmedStrings(inapplicability.consulted);
	const subject = trimmedText(inapplicability.subject);
	const ruledOutBy = trimmedText(inapplicability.ruledOutBy);
	if (consulted.length === 0)
		throw new Error(
			"inapplicability.consulted must name at least one source you read to conclude this",
		);
	if (!subject)
		throw new Error("inapplicability.subject is required: name what this practice looks for");
	if (!ruledOutBy)
		throw new Error(
			"inapplicability.ruledOutBy is required: state the fact about THIS work that means the subject " +
				"cannot occur in it. If you are merely unsure, the answer is INCONCLUSIVE, not NOT_APPLICABLE",
		);
	return { consulted: [...new Set(consulted)].toSorted(), subject, ruledOutBy };
}

export function normalizeEvidence(evidence: unknown, presence: Presence): NormalizedEvidence {
	if (!isRecord(evidence) || !Array.isArray(evidence.citations) || evidence.citations.length === 0)
		throw new Error("evidence citations are required");
	const citations = evidence.citations.map((citation: unknown): NormalizedCitation => {
		// A citation that is not an object reads as one with every field missing, which is what the
		// required-field checks below already reject by name.
		const fields: Record<string, unknown> = isRecord(citation) ? citation : {};
		const sourceKind = trimmedText(fields.sourceKind);
		const artifactPath = trimmedText(fields.artifactPath);
		const path = trimmedText(fields.path);
		const declaredSide = fields.side == null ? null : trimmedText(fields.side).toUpperCase();
		const startLine = Number(fields.startLine);
		const endLine = fields.endLine == null ? startLine : Number(fields.endLine);
		const quote = trimmedText(fields.quote);
		if (!sourceKind) throw new Error("evidence citation sourceKind is required");
		if (!artifactPath) throw new Error("evidence citation artifactPath is required");
		if (!path) throw new Error("evidence citation path is required");
		if (sourceKind === "scm.pull-request.diff" && declaredSide !== "OLD" && declaredSide !== "NEW")
			throw new Error("diff evidence citation side must be OLD or NEW");
		if (sourceKind !== "scm.pull-request.diff" && declaredSide !== null)
			throw new Error("non-diff evidence citation must not specify side");
		if (!Number.isInteger(startLine) || startLine <= 0)
			throw new Error("evidence citation startLine must be a positive integer");
		if (!Number.isInteger(endLine) || endLine < startLine)
			throw new Error("evidence citation endLine must be >= startLine");
		if (!quote) throw new Error("evidence citation quote is required");
		// Only the two checks above can let a side through, so this is a re-reading of what they proved
		// rather than a second rule: anything else already threw.
		const side: DiffSide | null =
			declaredSide === "OLD" || declaredSide === "NEW" ? declaredSide : null;
		return {
			sourceKind,
			artifactPath,
			path,
			...(side == null ? {} : { side }),
			startLine,
			endLine,
			quote,
		};
	});
	// Required for ABSENT and kept whenever it is offered: on a PRESENT observation the citations are
	// already the warrant, but a model that recorded where it looked has said something true and there
	// is no reason to discard it.
	if (presence === "ABSENT") {
		if (evidence.search == null) {
			throw new Error(
				"an ABSENT observation must record its search: evidence.search with consulted, lookedFor and boundary",
			);
		}
		return { citations, search: normalizeSearch(evidence.search) };
	}
	// The same shape one presence over: a claim that the practice has no subject here must say what the
	// subject is and what rules it out, or it is an abstention wearing a measurement's clothes.
	if (presence === "NOT_APPLICABLE") {
		if (evidence.inapplicability == null) {
			throw new Error(
				"a NOT_APPLICABLE observation must say why the practice does not apply: " +
					"evidence.inapplicability with consulted, subject and ruledOutBy. If you looked and could " +
					"not tell, say INCONCLUSIVE instead",
			);
		}
		return { citations, inapplicability: normalizeInapplicability(evidence.inapplicability) };
	}
	// And the same shape once more, for the last presence that had no ground at all. "I could not tell" is
	// only a measurement if it says what it could not tell and what would have told it; otherwise it is the
	// cheapest thing to write, which is how it becomes the next place uncertainty drains to.
	if (presence === "INCONCLUSIVE") {
		if (evidence.undecidability == null) {
			throw new Error(
				"an INCONCLUSIVE observation must say what it could not settle: evidence.undecidability with " +
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
	if (!isRecord(undecidability)) throw new Error("undecidability is required");
	const openQuestion = trimmedText(undecidability.openQuestion);
	const wouldSettleIt = trimmedText(undecidability.wouldSettleIt);
	if (!openQuestion) throw new Error("undecidability.openQuestion is required");
	if (!wouldSettleIt) throw new Error("undecidability.wouldSettleIt is required");
	return { openQuestion, wouldSettleIt };
}

/**
 * The three fields one `outcome` word encodes, and the rule that binds them: a presence that carries a
 * direction always names one, and a presence that carries none never does. Stating it as a union rather
 * than as three independent fields is what makes the DB CHECK chk_observation_presence_assessment
 * unbreakable here instead of merely tested.
 */
type OutcomeVerdict =
	| { presence: ValencedPresence; assessment: Assessment; severity: Severity }
	| { presence: Exclude<Presence, ValencedPresence>; assessment: null; severity: "INFO" };

/**
 * Narrow a word the model produced to a member of the vocabulary it belongs to, or throw.
 *
 * <p>The regex below already constrains what reaches this, so it never fires in practice. It is here so
 * that the value the record ends up holding is one the vocabulary arrays admit — the same arrays
 * AgentVocabularySyncTest holds against the Java enums — rather than one only the regex vouched for.
 *
 * <p>It takes the undefined a regex group can hand back rather than making each caller rule that out
 * first: an absent group is a word no vocabulary admits, which is the same rejection by a shorter path.
 */
function parseVocabulary<T extends string>(
	values: readonly T[],
	value: string | undefined,
	field: string,
): T {
	const admitted = values.find((candidate) => candidate === value);
	if (!admitted) throw new Error(`invalid ${field} '${value}'`);
	return admitted;
}

/** Read the outcome word the tool schema offers back into presence, valence and cost. */
function parseOutcome(outcome: string): OutcomeVerdict {
	if (outcome === "NO_REVIEW_OCCASION")
		return { presence: "NOT_APPLICABLE", assessment: null, severity: "INFO" };
	if (outcome === "INSUFFICIENT_EVIDENCE")
		return { presence: "INCONCLUSIVE", assessment: null, severity: "INFO" };
	const match = /^BEHAVIOR_(PRESENT|ABSENT)_(GOOD|BAD)(?:_(MINOR|MAJOR|CRITICAL))?$/.exec(outcome);
	if (!match) throw new Error(`invalid outcome '${outcome}'`);
	const presence = parseVocabulary(PRESENCE_VALUES, match[1], "presence");
	if (!carriesValence(presence)) throw new Error(`invalid outcome '${outcome}'`);
	const assessment = parseVocabulary(ASSESSMENT_VALUES, match[2], "assessment");
	if (assessment === "BAD") {
		if (!match[3]) throw new Error("BAD outcome requires a severity suffix");
		return {
			presence,
			assessment,
			severity: parseVocabulary(SEVERITY_VALUES, match[3], "severity"),
		};
	}
	if (match[3]) throw new Error("GOOD outcome must not carry severity");
	return { presence, assessment, severity: "INFO" };
}

export function normalizeObservation(observation: unknown): NormalizedObservation {
	if (!isRecord(observation)) throw new Error("observation must be an object");
	const allowed = new Set(["practiceSlug", "summary", "outcome", "evidence", "evidenceRationale"]);
	const unknownFields = Object.keys(observation).filter((key) => !allowed.has(key));
	if (unknownFields.length)
		throw new Error(`unknown observation field(s): ${unknownFields.join(", ")}`);
	const practiceSlug = trimmedText(observation.practiceSlug).toLowerCase().replace(/_/g, "-");
	const title = trimmedText(observation.summary);
	const reasoning = trimmedText(observation.evidenceRationale);
	const outcome = trimmedText(observation.outcome).toUpperCase();
	const { presence, assessment, severity } = parseOutcome(outcome);
	if (!practiceSlug) throw new Error("practiceSlug is required");
	if (!title) throw new Error("summary is required");
	// The summary is what the developer reads on their practice page, above the practice's own name and
	// with no evidence beside it, so a single word there ("Test") names nothing the practice did not.
	if (!/\S\s+\S/.test(title))
		throw new Error(
			"summary must say what was observed as a short phrase, not one word — e.g. " +
				"'Debug print left in the request handler'",
		);
	if (!reasoning) throw new Error("evidenceRationale is required");
	const externalEvidence: Record<string, unknown> = isRecord(observation.evidence)
		? observation.evidence
		: {};
	const evidenceFields = new Set(["citations", "exhaustiveSearch", "exclusion", "missingEvidence"]);
	const unknownEvidence = Object.keys(externalEvidence).filter((key) => !evidenceFields.has(key));
	if (unknownEvidence.length)
		throw new Error(`unknown evidence field(s): ${unknownEvidence.join(", ")}`);
	const branchCount = ["exhaustiveSearch", "exclusion", "missingEvidence"].filter(
		(key) => externalEvidence[key] != null,
	).length;
	const expectedBranch: string | null =
		presence === "ABSENT"
			? "exhaustiveSearch"
			: presence === "NOT_APPLICABLE"
				? "exclusion"
				: presence === "INCONCLUSIVE"
					? "missingEvidence"
					: null;
	if (
		(expectedBranch == null && branchCount !== 0) ||
		(expectedBranch != null && (branchCount !== 1 || externalEvidence[expectedBranch] == null))
	) {
		throw new Error(
			`evidence must carry exactly ${expectedBranch ?? "citations"} for this outcome`,
		);
	}
	const internalEvidence = {
		citations: externalEvidence.citations,
		...(externalEvidence.exhaustiveSearch == null
			? {}
			: { search: externalEvidence.exhaustiveSearch }),
		...(externalEvidence.exclusion == null ? {} : { inapplicability: externalEvidence.exclusion }),
		...(externalEvidence.missingEvidence == null
			? {}
			: { undecidability: externalEvidence.missingEvidence }),
	};
	const evidence = normalizeEvidence(internalEvidence, presence);
	const out: NormalizedObservation = {
		practiceSlug,
		summary: title,
		presence,
		severity,
		evidence,
		evidenceRationale: reasoning,
	};
	// Exactly the presences carriesValence() admits, because parseOutcome() is what decided both.
	if (assessment !== null) out.assessment = assessment;
	return out;
}

export function dedupeKeyForObservation(observation: NormalizedObservation): string {
	const citations = observation.evidence.citations
		.map((citation) => `${citation.path}:${citation.startLine}-${citation.endLine}`)
		.join(",");
	return `${observation.practiceSlug}|${observation.summary}|${citations}`;
}

/**
 * Holds every citation to bytes this run actually staged, under the source that produced them.
 *
 * <p>The run stages every source that applies to the artifact, so the question is no longer whether
 * the practice predicted it would read this one — it is whether the source was there and the quote
 * really came out of it. A practice whose subject turns out to live in a source its author did not
 * name is exactly the case full context exists to catch, and rejecting its citation would have thrown
 * away the observation for being observant.
 */
export function validateEvidenceSources(
	observation: NormalizedObservation,
	availableSourceKinds: ReadonlySet<string>,
	artifactSources: ReadonlyMap<string, string> = new Map(),
): void {
	for (const citation of observation.evidence.citations) {
		const sourceKind = citation.sourceKind;
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`evidence source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`inputs/manifest.json: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
		const artifactSource = artifactSources.get(citation.artifactPath);
		if (artifactSource !== sourceKind) {
			throw new Error(
				artifactSource === undefined
					? `artifact '${citation.artifactPath}' was not staged; copy an artifact path from inputs/manifest.json`
					: `artifact '${citation.artifactPath}' belongs to evidence source '${artifactSource}', not '${sourceKind}'`,
			);
		}
	}
}

function describeAvailableSources(sourceKinds: ReadonlySet<string>): string {
	return sourceKinds.size === 0 ? "(none)" : [...sourceKinds].toSorted().join(", ");
}

/**
 * Holds a recorded search against the domain the practice declared it would search.
 *
 * <p>`EXHAUSTIVE` is the practice saying "this claim asserts something is NOT in this source", which
 * is the only stance under which absence is assertable at all. So the sources held that way ARE the
 * domain: an ABSENT observation that did not consult one of them is asserting a universal over a
 * corpus it never opened, and the honest answer is INCONCLUSIVE instead.
 *
 * <p>The two directions of an absence do not need the same proof, and the difference is what lets a
 * clean surface be recorded as a strength at all. An ABSENT/BAD says a good behaviour is missing from
 * the place the citation points at — the claim is anchored to that locus, so the search only has to
 * reach as far as the locus does. An ABSENT/GOOD says a harmful behaviour is nowhere in the work, which
 * ranges over the WHOLE corpus and is provable only if that corpus is closed and was covered whole.
 * A practice that has not declared an exhaustive stance has not closed a corpus, so it cannot make that
 * claim, and INCONCLUSIVE is the honest answer; one that has, can. This is what the eight defect
 * detectors used to buy by refusing GOOD outright and paying for it in false NOT_APPLICABLEs.
 *
 * <p>Consulting something this run never staged is the same error the citation check already rejects —
 * the bytes were not there, so they cannot have been searched.
 */
export function validateSearchScope(
	observation: NormalizedObservation,
	exhaustiveSourceKinds: ReadonlySet<string>,
	availableSourceKinds: ReadonlySet<string>,
): void {
	if (observation.presence !== "ABSENT") return;
	const search = observation.evidence.search;
	if (!search) throw new Error("an ABSENT observation must record its search");
	if (observation.assessment === "GOOD" && exhaustiveSourceKinds.size === 0) {
		throw new Error(
			`cannot conclude ABSENT + GOOD for '${observation.practiceSlug}': it declares no source it searches ` +
				`exhaustively, so "this is not anywhere in the work" ranges over a corpus it has not bounded — ` +
				`say INCONCLUSIVE instead`,
		);
	}
	const consulted = new Set(search.consulted);
	for (const sourceKind of consulted) {
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`searched source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`inputs/manifest.json: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
	}
	const unsearched = [...exhaustiveSourceKinds]
		.filter((sourceKind) => !consulted.has(sourceKind))
		.toSorted();
	if (unsearched.length > 0) {
		throw new Error(
			`cannot conclude ABSENT for '${observation.practiceSlug}' without searching ${unsearched.join(", ")} — ` +
				`say INCONCLUSIVE instead, or record the search`,
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
	if (observation.presence !== "NOT_APPLICABLE") return;
	const inapplicability = observation.evidence.inapplicability;
	if (!inapplicability)
		throw new Error("a NOT_APPLICABLE observation must say why the practice does not apply");
	for (const sourceKind of inapplicability.consulted) {
		if (!availableSourceKinds.has(sourceKind)) {
			throw new Error(
				`consulted source '${sourceKind}' was not available; copy one of these source kinds from ` +
					`inputs/manifest.json: ${describeAvailableSources(availableSourceKinds)}`,
			);
		}
	}
}

/** ASCII-equivalent punctuation folded before citation comparison. */
const CONFUSABLES = new Map(
	Object.entries({
		"‘": "'",
		"’": "'",
		"‚": "'",
		"‛": "'",
		"“": '"',
		"”": '"',
		"„": '"',
		"‟": '"',
		"‐": "-",
		"‑": "-",
		"‒": "-",
		"–": "-",
		"—": "-",
		"―": "-",
		" ": " ",
		" ": " ",
		" ": " ",
		" ": " ",
	}),
);

/** Fold the substitutions above; everything else is compared as written. */
function foldConfusables(text: string): string {
	let out = "";
	for (const ch of text) out += CONFUSABLES.get(ch) ?? ch;
	return out;
}

export function citationMatchesArtifact(citation: NormalizedCitation, content: string): boolean {
	if (citation.sourceKind !== "scm.pull-request.diff") {
		return (
			content.includes(citation.quote) ||
			foldConfusables(content).includes(foldConfusables(citation.quote))
		);
	}
	let oldPath: string | null = null;
	let newPath: string | null = null;
	const citedLines = new Map<number, string>();
	for (const storedLine of content.split("\n")) {
		// Both groups are mandatory, so binding them here is what lets the annotated branch below turn
		// on a value the compiler has seen rather than on the match object being non-null.
		const [, annotatedLineNumber, annotatedText] = storedLine.match(/^\[L(\d+)] (.*)$/) ?? [];
		const line = annotatedText ?? storedLine;
		if (line.startsWith("--- ")) {
			oldPath = diffPath(line.slice(4));
			continue;
		}
		if (line.startsWith("+++ ")) {
			newPath = diffPath(line.slice(4));
			continue;
		}
		if (annotatedLineNumber !== undefined) {
			const lineNumber = Number(annotatedLineNumber);
			const side: DiffSide = line.startsWith("-") ? "OLD" : "NEW";
			const path = side === "OLD" ? oldPath : newPath;
			if (side === citation.side && path === citation.path) citedLines.set(lineNumber, line);
		}
	}
	const quoteLines = citation.quote.split("\n");
	if (quoteLines.length !== citation.endLine - citation.startLine + 1) return false;
	return quoteLines.every((quoteLine, index) => {
		const diffLine = citedLines.get(citation.startLine + index);
		return diffLine === quoteLine || diffLine?.slice(1) === quoteLine;
	});
}

function diffPath(rawPath: string): string | null {
	let value = rawPath.trim();
	if (value === "/dev/null") return null;
	if (value.startsWith('"') && value.endsWith('"'))
		value = value.slice(1, -1).replaceAll('\\"', '"');
	return value.startsWith("a/") || value.startsWith("b/") ? value.slice(2) : value;
}
