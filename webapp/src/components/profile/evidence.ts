import type { ObservationDetail } from "@/api/types.gen";
import { evidenceSourceDef } from "@/components/practice-vocabulary/evidence-source-defs";

export interface EvidenceLocation {
	path: string;
	startLine: number;
	endLine: number;
	sourceKind: string;
	side?: "OLD" | "NEW";
	snippet?: string;
	redacted: boolean;
	/**
	 * One changed passage, folded from the diff's two citations of the same lines: what the lines
	 * read before the change and what they read after.
	 */
	change?: { before: string; after: string };
}
export function toEvidenceLocations(evidence: ObservationDetail["evidence"]): EvidenceLocation[] {
	return foldObjectSources(
		foldDiffSides(
			(evidence?.citations ?? []).map((citation) => ({
				path: citation.path,
				startLine: citation.startLine,
				endLine: citation.endLine,
				sourceKind: citation.sourceKind,
				side: citation.side,
				snippet: citation.quote,
				redacted: citation.quoteRedacted,
			})),
		),
	);
}

/** Two sides of one change: the same lines of the same file, quoted from either side of the diff. */
const pairsWith = (kept: EvidenceLocation, next: EvidenceLocation) =>
	kept.change === undefined &&
	kept.side !== undefined &&
	next.side !== undefined &&
	kept.side !== next.side &&
	kept.path === next.path &&
	kept.startLine === next.startLine &&
	kept.endLine === next.endLine;

/**
 * The OLD and the NEW quote of the same lines folded into one location. A changed line is cited
 * from both sides of the diff, and two blocks over one change read as two pieces of evidence; the
 * pair becomes the one block that says what the lines were and what they became. A withheld quote
 * never folds: a redacted block says something its counterpart cannot.
 */
function foldDiffSides(locations: EvidenceLocation[]): EvidenceLocation[] {
	const folded: EvidenceLocation[] = [];
	for (const location of locations) {
		const index = folded.findIndex((kept) => pairsWith(kept, location));
		const kept = folded[index];
		if (kept?.snippet === undefined || location.snippet === undefined) {
			folded.push(location);
			continue;
		}
		const [before, after] =
			kept.side === "OLD" ? [kept.snippet, location.snippet] : [location.snippet, kept.snippet];
		folded[index] = {
			path: kept.path,
			startLine: kept.startLine,
			endLine: kept.endLine,
			sourceKind: kept.sourceKind,
			redacted: false,
			change: { before, after },
		};
	}
	return folded;
}
/**
 * Two quotes of the same object source: not a place the reader could open, so the same registry
 * label over each of them reads as two pieces of evidence instead of one thing quoted twice. A
 * withheld quote never folds, for the same reason a diff pair does not — a redacted block says
 * something its counterpart cannot.
 */
const quotesTheSameSourceAs = (kept: EvidenceLocation, next: EvidenceLocation) =>
	kept.sourceKind === next.sourceKind &&
	evidenceSourceDef(kept.sourceKind).locator === "object" &&
	!kept.redacted &&
	!next.redacted &&
	kept.snippet !== undefined &&
	next.snippet !== undefined;

/**
 * Every quote of one object source folded into the block that first named it, each on its own
 * line. The block keeps the position of that first citation, so the order the reviewer recorded
 * still decides which source is read first.
 */
function foldObjectSources(locations: EvidenceLocation[]): EvidenceLocation[] {
	const folded: EvidenceLocation[] = [];
	for (const location of locations) {
		const index = folded.findIndex((kept) => quotesTheSameSourceAs(kept, location));
		const kept = folded[index];
		if (kept === undefined) {
			folded.push(location);
			continue;
		}
		folded[index] = { ...kept, snippet: `${kept.snippet}\n${location.snippet}` };
	}
	return folded;
}

/** One pair of the "What was checked" list: the muted term, and the reviewer's own words for it. */
export interface EvidenceCheck {
	term: string;
	detail: string;
}

/**
 * What the review checked when it recorded no strength and no problem, in the warrant it wrote:
 * where it looked and found nothing (a search), why this practice had nothing to judge here (an
 * inapplicability), or what it could not settle (an undecidability). A review writes at most one
 * of the three about one observation, so the first one present is the whole list.
 *
 * Nothing here is inferred: a pair exists only where the reviewer wrote the sentence behind it,
 * and the sources it consulted are named in the registry's words rather than by their wire kind.
 */
export function toEvidenceCheck(evidence: ObservationDetail["evidence"]): EvidenceCheck[] {
	const search = evidence?.search;
	if (search) {
		return [
			{ term: "Looked for", detail: search.lookedFor },
			...consultedCheck(search.consulted),
			{ term: "Not covered", detail: search.boundary },
		];
	}
	const inapplicability = evidence?.inapplicability;
	if (inapplicability) {
		return [
			{ term: "Looks for", detail: inapplicability.subject },
			...consultedCheck(inapplicability.consulted),
			{ term: "Nothing to judge because", detail: inapplicability.ruledOutBy },
		];
	}
	const undecidability = evidence?.undecidability;
	if (undecidability) {
		return [
			{ term: "Open question", detail: undecidability.openQuestion },
			{ term: "Would settle it", detail: undecidability.wouldSettleIt },
		];
	}
	return [];
}

/** The sources a warrant names, in the registry's words; nothing consulted is no pair at all. */
function consultedCheck(consulted: string[]): EvidenceCheck[] {
	if (consulted.length === 0) return [];
	return [{ term: "Read", detail: joinWithAnd(consulted.map(sourceLabel)) }];
}

const sourceLabel = (sourceKind: string) => evidenceSourceDef(sourceKind).label;

/** "A", "A and B", "A, B and C" — a list read as a sentence rather than as a set of chips. */
function joinWithAnd(items: string[]): string {
	if (items.length < 2) return items.join("");
	return `${items.slice(0, -1).join(", ")} and ${items.at(-1)}`;
}

export function splitPath(path: string): { directory: string; fileName: string } {
	const lastSlash = path.lastIndexOf("/");
	if (lastSlash < 0) return { directory: "", fileName: path };
	return { directory: path.slice(0, lastSlash + 1), fileName: path.slice(lastSlash + 1) };
}
export function evidenceLineRangeLabel(location: EvidenceLocation): string {
	if (location.endLine !== location.startLine) {
		return `lines ${location.startLine} to ${location.endLine}`;
	}
	return `line ${location.startLine}`;
}
