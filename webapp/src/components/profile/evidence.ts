import type { ObservationDetail } from "@/api/types.gen";
export interface EvidenceLocation {
	path: string;
	startLine: number;
	endLine: number;
	sourceKind: string;
	side?: "OLD" | "NEW";
	revision?: string;
	snippet?: string;
	redacted: boolean;
}
export function toEvidenceLocations(evidence: ObservationDetail["evidence"]): EvidenceLocation[] {
	return (evidence?.citations ?? []).map((citation) => ({
		path: citation.path,
		startLine: citation.startLine,
		endLine: citation.endLine,
		sourceKind: citation.sourceKind,
		side: citation.side,
		revision: citation.revision,
		snippet: citation.quote,
		redacted: citation.quoteRedacted,
	}));
}
export function splitPath(path: string): { directory: string; fileName: string } {
	const lastSlash = path.lastIndexOf("/");
	if (lastSlash < 0) return { directory: "", fileName: path };
	return { directory: path.slice(0, lastSlash + 1), fileName: path.slice(lastSlash + 1) };
}
export function evidenceLineRangeLabel(location: EvidenceLocation): string {
	if (location.endLine !== location.startLine) {
		return `${location.startLine}–${location.endLine}`;
	}
	return `${location.startLine}`;
}
