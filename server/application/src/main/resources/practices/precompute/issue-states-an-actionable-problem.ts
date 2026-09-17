import { type InventoryItem, type ProjectInventory, readProjectInventory } from "../lib/context.ts";
import { classifyIssue, type IssueMetadata } from "../lib/issue-classification.ts";
import type { Hint } from "../lib/types.ts";

function titleTokens(s: string): Set<string> {
	return new Set(
		s
			.toLowerCase()
			.split(/[^a-z0-9]+/u)
			.filter((t) => t.length > 3),
	);
}

/** Title-token overlap (Jaccard) — a coarse near-duplicate signal across the project inventory. */
function titleOverlap(a: string, b: string): number {
	// Both callers pass text that lib/context.ts has already established is a string — an inventory item
	// whose `title` is a number or an object is dropped there rather than reaching this comparison.
	const x = titleTokens(a);
	const y = titleTokens(b);
	if (x.size === 0 || y.size === 0) {
		return 0;
	}
	let inter = 0;
	for (const t of x) {
		if (y.has(t)) {
			inter += 1;
		}
	}
	return inter / (x.size + y.size - inter);
}

interface InventoryMatches {
	count: number;
	direction?: string;
}

// A genuine actionable problem is usually NOT a restatement of one already filed. Title overlap is a
// CANDIDATE near-duplicate signal only; the LLM confirms duplication against the bodies (precompute never decides).
function nearDuplicateIssues(inventory: ProjectInventory | null, title: string): InventoryMatches {
	const issues = inventory?.issues ?? [];
	if (issues.length === 0 || title.length === 0) {
		return { count: 0 };
	}
	const matches: InventoryItem[] = issues
		.filter((it) => titleOverlap(title, it.title) >= 0.5)
		.slice(0, 3);
	if (matches.length > 0) {
		return {
			count: matches.length,
			direction: `Cross-artifact fact: ${matches.length} other issue(s) in this project share a strongly-overlapping title — ${matches.map((it) => `#${it.number} "${it.title}" (${it.state ?? "?"})`).join("; ")}. Open project_inventory.json and compare bodies before crediting this as a fresh, distinct problem.`,
		};
	}
	if (inventory?.truncated === true) {
		// Capped listing: the absence of an overlap among the LISTED subset does NOT prove uniqueness.
		return {
			count: 0,
			direction: `Cross-artifact fact: project_inventory.json lists ${issues.length} issue(s) but is TRUNCATED (capped subset, not exhaustive); none LISTED has a strongly-overlapping title, but a duplicate may exist outside the listing — do not conclude this is unique from the inventory alone.`,
		};
	}
	return {
		count: 0,
		direction: `Cross-artifact fact: ${issues.length} other issue(s) exist in project_inventory.json (full listing, not truncated); none has a strongly-overlapping title — absence of an overlap here reflects the exhaustive listing, not a capped subset.`,
	};
}

// "Is this work already in flight?" — scan OPEN pull requests for a strongly-overlapping title. PR->issue
// closing refs are not synced, so this title-overlap is a CANDIDATE signal only; the LLM confirms.
function openPullRequestTitleMatches(
	inventory: ProjectInventory | null,
	title: string,
): InventoryMatches {
	const pullRequests = inventory?.pullRequests ?? [];
	if (pullRequests.length === 0 || title.length === 0) {
		return { count: 0 };
	}
	const prMatches = pullRequests
		.filter((p) => p.state === "OPEN" && titleOverlap(title, p.title) >= 0.5)
		.slice(0, 3);
	if (prMatches.length === 0) {
		return { count: 0 };
	}
	return {
		count: prMatches.length,
		direction: `Cross-artifact fact: ${prMatches.length} OPEN pull request(s) have a strongly-overlapping title — ${prMatches.map((p) => `#${p.number} "${p.title}"`).join("; ")}. This issue's work may already be in flight; open project_inventory.json and confirm before treating it as unaddressed (title overlap only — PR-to-issue links are not in the index).`,
	};
}

const has = (re: RegExp, s: string) => re.test(s);

interface WorkKind {
	kind: "BUG" | "STORY" | "UNCLASSIFIED";
	typeBug: boolean;
	typeStory: boolean;
}

function classifyWorkKind(
	body: string,
	title: string,
	issueType: string,
	labels: string[],
): WorkKind {
	const typeBug = /bug|defect/u.test(issueType) || labels.some((l) => /bug|defect|fix/u.test(l));
	const typeStory =
		/enhancement|feature|story/u.test(issueType) ||
		labels.some((l) => /enhancement|feature|story/u.test(l));
	const bodyAssertsMalfunction = has(
		/\b(?:does ?n'?t work|not working|fails?|error|crash|broken|wrong|unexpected|incorrect)\b/iu,
		body,
	);
	const bodyRequestsCapability = has(
		/\b(?:add|support|implement|introduce|allow|enable|provide|ability to)\b/iu,
		`${body} ${title}`,
	);
	let kind: WorkKind["kind"] = "UNCLASSIFIED";
	if (typeBug || bodyAssertsMalfunction) {
		kind = "BUG";
	} else if (typeStory || bodyRequestsCapability) {
		kind = "STORY";
	}
	return { kind, typeBug, typeStory };
}

interface Readiness {
	hasRepro: boolean;
	hasExpectedActual: boolean;
	hasValueClause: boolean;
}

function readinessSignals(body: string): Readiness {
	const hasRepro = has(
		/\b(?:steps to reproduce|to reproduce|repro(?:duction)? steps|reproduce)\b/iu,
		body,
	);
	const hasExpectedActual =
		has(/\b(?:expected|actual)\b[\s\S]{0,40}\b(?:result|behaviou?r|output)\b/iu, body) ||
		(/\bexpected\b/iu.test(body) && /\bactual\b/iu.test(body));
	const hasValueClause =
		has(/\bso that\b/iu, body) ||
		has(/\bas an?\b[\s\S]{0,60}\bi (?:want|need|would like)\b/iu, body);
	return { hasRepro, hasExpectedActual, hasValueClause };
}

function classificationDirection({
	emptyOrTitleEcho,
	hasDeliverableType,
	looksUmbrella,
	labels,
}: ReturnType<typeof classifyIssue>): string | undefined {
	if (emptyOrTitleEcho && hasDeliverableType) {
		return `Classification fact: the body is empty or just echoes the title, yet the issue carries a deliverable type [${labels.join(", ")}]. A reader has nothing to build from; the deliverable label means this is not a board placeholder — investigate whether a maintainer can actually act on it.`;
	}
	if (emptyOrTitleEcho && !hasDeliverableType) {
		return `Classification fact: empty body and no deliverable work-item type — looks like a board column / tracker rather than an authored work item.`;
	}
	if (looksUmbrella) {
		return `Classification fact: looksUmbrella=1 and emptyOrTitleEcho=0 — an umbrella/requirement card carrying substantive prose (a label matches requirement/epic/umbrella). Its actionability lives at the requirement level: its natural bar is decomposition into child stories, not a story-style who/beneficiary/so-that problem statement and not inline checkbox acceptance criteria.`;
	}
	return undefined;
}

// When looksUmbrella holds on a prose card the requirement-level bar above governs; the per-kind story/bug
// readiness signals (which ask for a value/who-benefits clause or repro) do NOT apply to a requirement and
// would contradict the umbrella fact, so suppress them in that case.
function workKindDirections(
	umbrellaProse: boolean,
	{ kind, typeBug, typeStory }: WorkKind,
	{ hasRepro, hasExpectedActual, hasValueClause }: Readiness,
): string[] {
	if (umbrellaProse) {
		return [
			`Work-kind signal: REQUIREMENT/umbrella card — the per-kind story/bug readiness checks (value clause, who-benefits, reproduction) do NOT apply; its bar is requirement-level decomposition, per the umbrella fact above.`,
		];
	}
	const directions = [
		`Work-kind signal: ${kind} (typeBug=${typeBug}, typeStory=${typeStory}). For a BUG, an act-on-able report usually needs reproduction + expected-vs-actual; for a STORY, a value/outcome clause.`,
	];
	if (kind === "BUG") {
		directions.push(
			`Bug readiness facts: reproductionPresent=${hasRepro}, expectedVsActualPresent=${hasExpectedActual} — confirm against the body text.`,
		);
	}
	if (kind === "STORY") {
		directions.push(
			`Story readiness fact: valueClausePresent=${hasValueClause} ("so that…" / "as a… I want…") — confirm against the body text.`,
		);
	}
	return directions;
}

export default async function issueStatesAnActionableProblem(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
	contextDir?: string,
) {
	const shape = classifyIssue(m);
	const { body, title, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } =
		shape;

	const bodyLen = body.length;
	const isStub = bodyLen < 40 || /^_?no response_?$/iu.test(body);
	const looksTemplate =
		/^#{1,3}\s|<!--/u.test(body) &&
		body
			.replaceAll(/<!--[\s\S]*?-->/gu, "")
			.replaceAll(/^#{1,3}.*$/gmu, "")
			.trim().length < 60;

	const workKind = classifyWorkKind(body, title, issueType, labels);
	const readiness = readinessSignals(body);

	const directions: string[] = [];
	const classification = classificationDirection(shape);
	if (classification !== undefined) {
		directions.push(classification);
	}
	if (isStub && !emptyOrTitleEcho) {
		directions.push(
			`Body is ${bodyLen} chars — thin; check whether there is any quotable problem statement before crediting actionability.`,
		);
	}
	if (looksTemplate) {
		directions.push(
			`Body looks like an unmodified template (headings with little prose under them) — verify the author actually filled the sections.`,
		);
	}
	directions.push(...workKindDirections(looksUmbrella && !emptyOrTitleEcho, workKind, readiness));

	const inventory = await readProjectInventory(contextDir);
	const nearDuplicates = nearDuplicateIssues(inventory, title);
	const openPullRequests = openPullRequestTitleMatches(inventory, title);
	for (const { direction } of [nearDuplicates, openPullRequests]) {
		if (direction !== undefined) {
			directions.push(direction);
		}
	}

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: bodyLen,
			isStub: isStub ? 1 : 0,
			looksTemplate: looksTemplate ? 1 : 0,
			reproductionPresent: readiness.hasRepro ? 1 : 0,
			expectedVsActualPresent: readiness.hasExpectedActual ? 1 : 0,
			valueClausePresent: readiness.hasValueClause ? 1 : 0,
			labelCount: labels.length,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			hasDeliverableType: hasDeliverableType ? 1 : 0,
			looksUmbrella: looksUmbrella ? 1 : 0,
			nearDuplicateTitleCount: nearDuplicates.count,
			openPrTitleMatchCount: openPullRequests.count,
		},
		directions,
	};
}
