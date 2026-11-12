import type { EvidenceCitation, ResultEvidence } from "@/api/types.gen";
import { evidenceSourceDef } from "@/components/practice-vocabulary/evidence-source-defs";
import { andList, hasText } from "@/lib/text";

export interface EvidenceLocation {
	/** Absent on a historical citation that recorded no file. */
	path?: string;
	/** Absent on a historical citation that recorded no lines. */
	startLine?: number;
	endLine?: number;
	sourceKind: string;
	side?: "OLD" | "NEW";
	/** The commit a quote from the repository's history was verified against. */
	commit?: string;
	/** Absent where the citation recorded only its place. */
	snippet?: string;
	/**
	 * One changed passage, folded from the diff's two citations of it: what the lines read before
	 * the change and what they read after.
	 */
	change?: { before: string; after: string };
}
export function toEvidenceLocations(citations: EvidenceCitation[]): EvidenceLocation[] {
	return foldObjectSources(foldDiffSides(citations));
}

/** One block as the fold sees it: the citation it is built from, and the change it grew into. */
interface DiffBlock {
	citation: EvidenceCitation;
	change?: { before: string; after: string };
}

// The wire carries no hunk id; pair diff sides only when every other location fact agrees.
const pairsWith = (kept: DiffBlock, next: EvidenceCitation) =>
	kept.change === undefined &&
	kept.citation.side !== null &&
	next.side !== null &&
	kept.citation.side !== next.side &&
	kept.citation.sourceKind === next.sourceKind &&
	kept.citation.sourcePath === next.sourcePath &&
	kept.citation.path === next.path &&
	kept.citation.startLine === next.startLine &&
	kept.citation.endLine === next.endLine;

function changeBetween(
	kept: EvidenceCitation,
	next: EvidenceCitation,
): { before: string; after: string } | undefined {
	const [before, after] = kept.side === "OLD" ? [kept.quote, next.quote] : [next.quote, kept.quote];
	if (before === null || after === null) {
		return undefined;
	}
	return { before, after };
}

// A folded diff pair has no single side or version; keep the before/after quotes instead.
function foldDiffSides(citations: EvidenceCitation[]): EvidenceLocation[] {
	const blocks: DiffBlock[] = [];
	for (const citation of citations) {
		const index = blocks.findIndex((kept) => pairsWith(kept, citation));
		const kept = blocks[index];
		const change = kept === undefined ? undefined : changeBetween(kept.citation, citation);
		if (kept === undefined || change === undefined) {
			blocks.push({ citation });
			continue;
		}
		blocks[index] = { citation: kept.citation, change };
	}
	return blocks.map(toLocation);
}

// A folded diff combines sides from separate commits; no single side, commit or quote describes it.
function toLocation({ citation, change }: DiffBlock): EvidenceLocation {
	if (change !== undefined) {
		return {
			path: citation.path ?? undefined,
			startLine: citation.startLine ?? undefined,
			endLine: citation.endLine ?? undefined,
			sourceKind: citation.sourceKind,
			change,
		};
	}
	return {
		path: citation.path ?? undefined,
		startLine: citation.startLine ?? undefined,
		endLine: citation.endLine ?? undefined,
		sourceKind: citation.sourceKind,
		side: citation.side ?? undefined,
		commit: citation.commit ?? undefined,
		snippet: citation.quote ?? undefined,
	};
}

// Group only identical object sources; different objects remain separate evidence blocks.
const quotesTheSameSourceAs = (kept: EvidenceLocation, next: EvidenceLocation) =>
	kept.sourceKind === next.sourceKind &&
	evidenceSourceDef(kept.sourceKind).locator === "object" &&
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
		if (kept?.snippet === undefined || location.snippet === undefined) {
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

export function toEvidenceCheck(evidence: ResultEvidence | null): EvidenceCheck[] {
	const search = evidence?.search;
	if (search) {
		return [
			...writtenCheck("Looked for", search.lookedFor),
			...consultedCheck(search.consulted),
			...writtenCheck("How far it reached", search.boundary),
			...writtenCheck("What it found", search.hits),
		];
	}
	const inapplicability = evidence?.inapplicability;
	if (inapplicability) {
		return [
			...writtenCheck("Applies when", inapplicability.prerequisite),
			...consultedCheck(inapplicability.consulted),
			...writtenCheck("Nothing to judge because", inapplicability.ruledOutBy),
			...writtenCheck("What it found", inapplicability.hits),
		];
	}
	return [];
}

function writtenCheck(term: string, detail: string | undefined): EvidenceCheck[] {
	return hasText(detail) ? [{ term, detail }] : [];
}

/** The sources a warrant names, in the registry's words; nothing consulted is no pair at all. */
function consultedCheck(consulted: string[] | undefined): EvidenceCheck[] {
	if (consulted === undefined || consulted.length === 0) {
		return [];
	}
	return [{ term: "Read", detail: andList.format(consulted.map(sourceLabel)) }];
}

const sourceLabel = (sourceKind: string) => evidenceSourceDef(sourceKind).label;

export function splitPath(path: string): { directory: string; fileName: string } {
	const lastSlash = path.lastIndexOf("/");
	if (lastSlash === -1) {
		return { directory: "", fileName: path };
	}
	return { directory: path.slice(0, lastSlash + 1), fileName: path.slice(lastSlash + 1) };
}
export function evidenceLineRangeLabel({
	startLine,
	endLine,
}: EvidenceLocation): string | undefined {
	if (startLine === undefined) {
		return undefined;
	}
	if (endLine !== undefined && endLine !== startLine) {
		return `lines ${startLine} to ${endLine}`;
	}
	return `line ${startLine}`;
}
