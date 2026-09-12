import { ShieldAlertIcon } from "lucide-react";

import type {
	EvidenceCitation,
	ObservationEvidence as ObservationEvidenceData,
} from "@/api/types.gen";
import {
	codeCitationLocator,
	DIFF_SIDE_LABELS,
	type EvidenceSourceGroup,
	groupCitationsBySource,
} from "@/components/practice-vocabulary/evidence-source-defs";
import { Badge } from "@/components/ui/badge";

export interface ObservationEvidenceProps {
	evidence: ObservationEvidenceData | null | undefined;
	/**
	 * Which tool read the sources. Only one is not the reviewing model — the secret scanner — and it
	 * is the only thing that ever withholds a quote, so naming it turns a blank passage into an
	 * explanation. Taken as a prop rather than read off `evidence.detector` inside the citation loop
	 * because it is a fact about the whole observation, not about one passage.
	 */
	detector?: string;
}

const SECRET_SCANNER = "secret-diff-scanner";

/** Non-code coordinates refer to serialized input, not a location a reader can open in the source. */
export function ObservationEvidence({ evidence, detector }: ObservationEvidenceProps) {
	const citations = evidence?.citations ?? [];
	if (citations.length === 0) {
		return (
			<p className="text-sm text-muted-foreground">
				Nothing was quoted for this observation, so there is no passage to check it against.
			</p>
		);
	}
	const groups = groupCitationsBySource(citations);

	return (
		<div className="space-y-6">
			<p className="text-sm text-muted-foreground">
				{summaryLine(citations.length, groups.length)}
			</p>
			{groups.map((group) => (
				<EvidenceSourceSection
					key={group.sourceKind}
					group={group}
					fromSecretScanner={detector === SECRET_SCANNER}
				/>
			))}
		</div>
	);
}

function summaryLine(citations: number, sources: number): string {
	const passages = citations === 1 ? "One passage" : `${citations} passages`;
	if (sources === 1) return `${passages} from one source.`;
	return `${passages} from ${sources} sources.`;
}

function EvidenceSourceSection({
	group,
	fromSecretScanner,
}: {
	group: EvidenceSourceGroup;
	fromSecretScanner: boolean;
}) {
	const { def, citations } = group;
	const Icon = def.icon;
	return (
		<section className="space-y-3">
			<div className="flex min-w-0 items-start gap-3">
				<span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md border bg-muted">
					<Icon className="size-4 text-muted-foreground" aria-hidden />
				</span>
				<div className="min-w-0 flex-1">
					<h4 className="text-sm font-medium break-words">{def.label}</h4>
					<p className="text-xs text-muted-foreground break-words">{def.description}</p>
				</div>
				<span className="shrink-0 text-xs text-muted-foreground">
					{citations.length === 1 ? "1 passage" : `${citations.length} passages`}
				</span>
			</div>
			<ul className="space-y-3 sm:pl-11">
				{citations.map((citation, index) => (
					<li
						key={`${citationKey(citation)}:${index}`}
						className="overflow-hidden rounded-lg border"
					>
						<CitationHeader citation={citation} locator={def.locator} />
						{citation.quoteRedacted ? (
							<RedactedQuote fromSecretScanner={fromSecretScanner} />
						) : (
							<pre className="overflow-x-auto p-3 text-xs whitespace-pre-wrap break-words">
								{citation.quote}
							</pre>
						)}
					</li>
				))}
			</ul>
		</section>
	);
}

function CitationHeader({
	citation,
	locator,
}: {
	citation: EvidenceCitation;
	locator: EvidenceSourceGroup["def"]["locator"];
}) {
	return (
		<div className="flex flex-wrap items-center gap-2 border-b bg-muted/50 px-3 py-2">
			{locator === "code" ? (
				<code className="min-w-0 text-xs break-all">{codeCitationLocator(citation)}</code>
			) : (
				<p className="min-w-0 text-xs break-words text-muted-foreground">{citation.path}</p>
			)}
			{locator === "code" && citation.side && (
				<Badge variant="outline" className="shrink-0">
					{DIFF_SIDE_LABELS[citation.side]}
				</Badge>
			)}
			{citation.revision && (
				<Badge variant="outline" className="shrink-0" title={citation.revision}>
					<code>{citation.revision.slice(0, 7)}</code>
				</Badge>
			)}
		</div>
	);
}

/**
 * The server accepts a missing quote from exactly one detector, so when that is the one that ran the
 * reason is knowable and can be said. When some other detector produced it the app does not know
 * why, and says only that.
 */
function RedactedQuote({ fromSecretScanner }: { fromSecretScanner: boolean }) {
	return (
		<p className="flex items-start gap-2 p-3 text-sm text-muted-foreground">
			<ShieldAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
			{fromSecretScanner
				? "Not quoted. This looked like a credential, so the text was never stored — open the line above to read it."
				: "Not quoted. The passage was withheld, so only its location was kept."}
		</p>
	);
}

function citationKey(citation: EvidenceCitation): string {
	return [
		citation.sourceKind,
		citation.artifactPath,
		citation.path,
		citation.revision,
		citation.side,
		citation.startLine,
		citation.endLine,
	].join(":");
}
