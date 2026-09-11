import { type InventoryItem, readProjectInventory } from "../lib/context.ts";
import { classifyIssue, type IssueMetadata } from "../lib/issue-classification.ts";
import type { Hint } from "../lib/types.ts";

/** Title-token overlap (Jaccard) — a coarse near-duplicate signal across the project inventory. */
function titleOverlap(a: string, b: string): number {
	// Both callers pass text that lib/context.ts has already established is a string — an inventory item
	// whose `title` is a number or an object is dropped there rather than reaching this comparison.
	const toks = (s: string) =>
		new Set(
			s
				.toLowerCase()
				.split(/[^a-z0-9]+/)
				.filter((t) => t.length > 3),
		);
	const x = toks(a);
	const y = toks(b);
	if (x.size === 0 || y.size === 0) return 0;
	let inter = 0;
	for (const t of x) if (y.has(t)) inter++;
	return inter / (x.size + y.size - inter);
}

const has = (re: RegExp, s: string) => re.test(s);

export default async function issueStatesAnActionableProblem(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
	contextDir?: string,
) {
	const { body, title, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } =
		classifyIssue(m);

	const bodyLen = body.length;
	const isStub = bodyLen < 40 || /^_?no response_?$/i.test(body);
	const looksTemplate =
		/^#{1,3}\s|<!--/.test(body) &&
		body
			.replace(/<!--[\s\S]*?-->/g, "")
			.replace(/^#{1,3}.*$/gm, "")
			.trim().length < 60;

	const typeBug = /bug|defect/.test(issueType) || labels.some((l) => /bug|defect|fix/.test(l));
	const typeStory =
		/enhancement|feature|story/.test(issueType) ||
		labels.some((l) => /enhancement|feature|story/.test(l));
	const bodyAssertsMalfunction = has(
		/\b(does ?n'?t work|not working|fails?|error|crash|broken|wrong|unexpected|incorrect)\b/i,
		body,
	);
	const bodyRequestsCapability = has(
		/\b(add|support|implement|introduce|allow|enable|provide|ability to)\b/i,
		`${body} ${title}`,
	);

	const hasRepro = has(
		/\b(steps to reproduce|to reproduce|repro(duction)? steps|reproduce)\b/i,
		body,
	);
	const hasExpectedActual =
		has(/\b(expected|actual)\b[\s\S]{0,40}\b(result|behaviou?r|output)\b/i, body) ||
		(/\bexpected\b/i.test(body) && /\bactual\b/i.test(body));
	const hasValueClause =
		has(/\bso that\b/i, body) || has(/\bas an?\b[\s\S]{0,60}\bi (want|need|would like)\b/i, body);

	const kind =
		typeBug || bodyAssertsMalfunction
			? "BUG"
			: typeStory || bodyRequestsCapability
				? "STORY"
				: "UNCLASSIFIED";

	const directions: string[] = [];
	if (emptyOrTitleEcho && hasDeliverableType) {
		directions.push(
			`Classification fact: the body is empty or just echoes the title, yet the issue carries a deliverable type [${labels.join(", ")}]. A reader has nothing to build from; the deliverable label means this is not a board placeholder — investigate whether a maintainer can actually act on it.`,
		);
	} else if (emptyOrTitleEcho && !hasDeliverableType) {
		directions.push(
			`Classification fact: empty body and no deliverable work-item type — looks like a board column / tracker rather than an authored work item.`,
		);
	} else if (looksUmbrella) {
		directions.push(
			`Classification fact: looksUmbrella=1 and emptyOrTitleEcho=0 — an umbrella/requirement card carrying substantive prose (a label matches requirement/epic/umbrella). Its actionability lives at the requirement level: its natural bar is decomposition into child stories, not a story-style who/beneficiary/so-that problem statement and not inline checkbox acceptance criteria.`,
		);
	}
	if (isStub && !emptyOrTitleEcho)
		directions.push(
			`Body is ${bodyLen} chars — thin; check whether there is any quotable problem statement before crediting actionability.`,
		);
	if (looksTemplate)
		directions.push(
			`Body looks like an unmodified template (headings with little prose under them) — verify the author actually filled the sections.`,
		);
	// When looksUmbrella holds on a prose card the requirement-level bar above governs; the per-kind story/bug
	// readiness signals (which ask for a value/who-benefits clause or repro) do NOT apply to a requirement and
	// would contradict the umbrella fact, so suppress them in that case.
	const umbrellaProse = looksUmbrella && !emptyOrTitleEcho;
	if (!umbrellaProse) {
		directions.push(
			`Work-kind signal: ${kind} (typeBug=${typeBug}, typeStory=${typeStory}). For a BUG, an act-on-able report usually needs reproduction + expected-vs-actual; for a STORY, a value/outcome clause.`,
		);
		if (kind === "BUG")
			directions.push(
				`Bug readiness facts: reproductionPresent=${hasRepro}, expectedVsActualPresent=${hasExpectedActual} — confirm against the body text.`,
			);
		if (kind === "STORY")
			directions.push(
				`Story readiness fact: valueClausePresent=${hasValueClause} ("so that…" / "as a… I want…") — confirm against the body text.`,
			);
	} else {
		directions.push(
			`Work-kind signal: REQUIREMENT/umbrella card — the per-kind story/bug readiness checks (value clause, who-benefits, reproduction) do NOT apply; its bar is requirement-level decomposition, per the umbrella fact above.`,
		);
	}

	// A genuine actionable problem is usually NOT a restatement of one already filed. Title overlap is a
	// CANDIDATE near-duplicate signal only; the LLM confirms duplication against the bodies (precompute never decides).
	let nearDuplicateCount = 0;
	let openPrTitleMatchCount = 0;
	const inventory = await readProjectInventory(contextDir);
	if (inventory?.issues?.length && title.length > 0) {
		const matches: InventoryItem[] = inventory.issues
			.filter((it) => titleOverlap(title, it.title) >= 0.5)
			.slice(0, 3);
		nearDuplicateCount = matches.length;
		if (matches.length > 0) {
			directions.push(
				`Cross-artifact fact: ${matches.length} other issue(s) in this project share a strongly-overlapping title — ${matches.map((it) => `#${it.number} "${it.title}" (${it.state ?? "?"})`).join("; ")}. Open project_inventory.json and compare bodies before crediting this as a fresh, distinct problem.`,
			);
		} else if (inventory.truncated) {
			// Capped listing: the absence of an overlap among the LISTED subset does NOT prove uniqueness.
			directions.push(
				`Cross-artifact fact: project_inventory.json lists ${inventory.issues.length} issue(s) but is TRUNCATED (capped subset, not exhaustive); none LISTED has a strongly-overlapping title, but a duplicate may exist outside the listing — do not conclude this is unique from the inventory alone.`,
			);
		} else {
			directions.push(
				`Cross-artifact fact: ${inventory.issues.length} other issue(s) exist in project_inventory.json (full listing, not truncated); none has a strongly-overlapping title — absence of an overlap here reflects the exhaustive listing, not a capped subset.`,
			);
		}
	}

	// "Is this work already in flight?" — scan OPEN pull requests for a strongly-overlapping title. PR->issue
	// closing refs are not synced, so this title-overlap is a CANDIDATE signal only; the LLM confirms.
	if (inventory?.pullRequests?.length && title.length > 0) {
		const prMatches = inventory.pullRequests
			.filter((p) => p.state === "OPEN" && titleOverlap(title, p.title) >= 0.5)
			.slice(0, 3);
		openPrTitleMatchCount = prMatches.length;
		if (prMatches.length > 0) {
			directions.push(
				`Cross-artifact fact: ${prMatches.length} OPEN pull request(s) have a strongly-overlapping title — ${prMatches.map((p) => `#${p.number} "${p.title}"`).join("; ")}. This issue's work may already be in flight; open project_inventory.json and confirm before treating it as unaddressed (title overlap only — PR-to-issue links are not in the index).`,
			);
		}
	}

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: bodyLen,
			isStub: isStub ? 1 : 0,
			looksTemplate: looksTemplate ? 1 : 0,
			reproductionPresent: hasRepro ? 1 : 0,
			expectedVsActualPresent: hasExpectedActual ? 1 : 0,
			valueClausePresent: hasValueClause ? 1 : 0,
			labelCount: labels.length,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			hasDeliverableType: hasDeliverableType ? 1 : 0,
			looksUmbrella: looksUmbrella ? 1 : 0,
			nearDuplicateTitleCount: nearDuplicateCount,
			openPrTitleMatchCount,
		},
		directions,
	};
}
