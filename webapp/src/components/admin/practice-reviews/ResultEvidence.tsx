import { ShieldAlertIcon } from "lucide-react";

import type { EvidenceCitation } from "@/api/types.gen";
import {
	codeCitationLocator,
	DIFF_SIDE_LABELS,
	evidenceSourceDef,
	type EvidenceSourceGroup,
	groupCitationsBySource,
} from "@/components/practice-vocabulary/evidence-source-defs";
import { Badge } from "@/components/ui/badge";
import {
	CodeBlock,
	CodeBlockActions,
	CodeBlockContent,
	CodeBlockCopyButton,
	CodeBlockFooter,
	CodeBlockHeader,
} from "@/components/ui/code-block";
import { hasText } from "@/lib/text";

export interface ResultEvidenceProps {
	citations: EvidenceCitation[];
	hidden?: boolean;
	/** One step below the heading the evidence stands under: `h4` under a section, `h5` under a subsection. */
	headingLevel?: 4 | 5;
}

export function ResultEvidence({
	citations,
	hidden = false,
	headingLevel = 4,
}: ResultEvidenceProps) {
	if (hidden) {
		return (
			<p className="text-sm text-muted-foreground">
				Quoted evidence is hidden because access to its sources could not be verified.
			</p>
		);
	}
	if (citations.length === 0) {
		return (
			<p className="text-sm text-muted-foreground">
				Nothing was quoted, so there is no passage to check this against.
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
				<EvidenceSourceSection key={group.sourceKind} group={group} headingLevel={headingLevel} />
			))}
		</div>
	);
}

function summaryLine(citations: number, sources: number): string {
	const passages = citations === 1 ? "One passage" : `${citations} passages`;
	if (sources === 1) {
		return `${passages} from one source.`;
	}
	return `${passages} from ${sources} sources.`;
}

function EvidenceSourceSection({
	group,
	headingLevel,
}: {
	group: EvidenceSourceGroup;
	headingLevel: 4 | 5;
}) {
	const { def, citations } = group;
	const Icon = def.icon;
	const Heading = headingLevel === 4 ? "h4" : "h5";
	return (
		<section className="space-y-3">
			<div className="flex min-w-0 items-start gap-3">
				<span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md border bg-muted">
					<Icon className="size-4 text-muted-foreground" aria-hidden />
				</span>
				<div className="min-w-0 flex-1">
					<Heading className="text-sm font-medium break-words">{def.label}</Heading>
					<p className="text-xs break-words text-muted-foreground">{def.description}</p>
				</div>
				<span className="shrink-0 text-xs text-muted-foreground">
					{citations.length === 1 ? "1 passage" : `${citations.length} passages`}
				</span>
			</div>
			<ul className="space-y-3 sm:pl-11">
				{citations.map((citation, index) => (
					<li key={`${citationKey(citation)}:${index}`}>
						<CitationQuote citation={citation} locator={def.locator} />
					</li>
				))}
			</ul>
		</section>
	);
}

type CitationPlace = Pick<
	EvidenceCitation,
	"sourceKind" | "path" | "startLine" | "endLine" | "side"
>;

/**
 * Where a passage sits in the work. Only a code citation has such a place: any other coordinate
 * points into the serialized input the review read, which is not a location in the source app.
 */
export function citationLocation(citation: CitationPlace): string | undefined {
	const { path, startLine, endLine } = citation;
	if (path === null || evidenceSourceDef(citation.sourceKind).locator !== "code") {
		return undefined;
	}
	return startLine === null
		? path
		: codeCitationLocator({ path, startLine, endLine: endLine ?? undefined });
}

export function citationSide(citation: CitationPlace): string | undefined {
	return evidenceSourceDef(citation.sourceKind).locator === "code" && citation.side !== null
		? DIFF_SIDE_LABELS[citation.side]
		: undefined;
}

/** One passage: where it sits, when that is a place in the work, then its words or why they are missing. */
function CitationQuote({
	citation,
	locator,
}: {
	citation: EvidenceCitation;
	locator: EvidenceSourceGroup["def"]["locator"];
}) {
	const side = citationSide(citation);
	const location = citationLocation(citation);
	const { quote, path } = citation;
	const fileName =
		location === undefined || path === null ? undefined : path.slice(path.lastIndexOf("/") + 1);
	const copy = quote !== null && (
		<CodeBlockActions>
			<CodeBlockCopyButton
				value={quote}
				aria-label={fileName === undefined ? "Copy passage" : `Copy passage from ${fileName}`}
			/>
		</CodeBlockActions>
	);
	return (
		<CodeBlock size="sm">
			{/* The source's heading already names an object source; it has no place of its own to show. */}
			{locator === "code" ? (
				<CodeBlockHeader>
					{location === undefined ? (
						<p className="min-w-0 break-words">Location not recorded</p>
					) : (
						<code className="min-w-0 break-all text-foreground">{location}</code>
					)}
					{side !== undefined && (
						<Badge variant="outline" className="shrink-0">
							{side}
						</Badge>
					)}
					{copy}
				</CodeBlockHeader>
			) : (
				copy
			)}
			{quote === null ? (
				<CodeBlockFooter>
					<p className="flex items-start gap-2">
						<ShieldAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
						{location === undefined
							? "Not quoted. The passage was withheld."
							: "Not quoted. The passage was withheld, so only its location was kept."}
					</p>
				</CodeBlockFooter>
			) : (
				<CodeBlockContent wrap>{quote}</CodeBlockContent>
			)}
			{hasText(citation.commit) && (
				<CodeBlockFooter>
					<p className="min-w-0 break-all">
						Commit <code>{citation.commit}</code>
					</p>
				</CodeBlockFooter>
			)}
		</CodeBlock>
	);
}

function citationKey(citation: EvidenceCitation): string {
	return [
		citation.sourceKind,
		citation.sourcePath,
		citation.path,
		citation.commit,
		citation.side,
		citation.startLine,
		citation.endLine,
	].join(":");
}
