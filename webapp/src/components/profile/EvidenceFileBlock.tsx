import { useId } from "react";

import { DotSeparator } from "@/components/common/DotSeparator";
import {
	DIFF_SIDE_LABELS,
	evidenceSourceDef,
} from "@/components/practice-vocabulary/evidence-source-defs";
import {
	CodeBlock,
	CodeBlockActions,
	CodeBlockContent,
	CodeBlockCopyButton,
	CodeBlockFooter,
	CodeBlockHeader,
	CodeBlockLine,
	CodeBlockTitle,
} from "@/components/ui/code-block";
import { useHighlightedCode } from "@/hooks/use-highlighted-code";
import { codeLanguage } from "@/lib/code-language";
import { hasText } from "@/lib/text";

import { type EvidenceLocation, evidenceLineRangeLabel, splitPath } from "./evidence";

interface EvidenceFileBlockProps {
	location: EvidenceLocation;
}

// Non-code locators point into serialized runner input, not coordinates in the source app.
export function EvidenceFileBlock({ location }: EvidenceFileBlockProps) {
	const titleId = useId();
	const source = evidenceSourceDef(location.sourceKind);
	const locatedByLine = source.locator === "code" && hasText(location.path);
	const { directory, fileName } = splitPath(location.path ?? "");
	const { change, side, snippet } = location;
	// A range over the two sides of a change would be true of one of them only.
	const lineRange =
		locatedByLine && change === undefined ? evidenceLineRangeLabel(location) : undefined;
	const sideLabel = change === undefined && side !== undefined ? DIFF_SIDE_LABELS[side] : undefined;
	const language = locatedByLine ? codeLanguage(location.path) : "";
	// Each side of a change is a passage of a different version of the file, so each is read alone.
	const before = useHighlightedCode(change?.before ?? "", language);
	const after = useHighlightedCode(change?.after ?? "", language);
	const quoted = useHighlightedCode(snippet ?? "", language);
	const copyText = change === undefined ? snippet : unifiedDiff(change);
	const SourceIcon = source.icon;

	return (
		<CodeBlock size="sm" role="figure" aria-labelledby={titleId}>
			<CodeBlockHeader>
				<CodeBlockTitle id={titleId} className="flex items-center">
					<SourceIcon className="shrink-0 text-muted-foreground" aria-hidden />
					{locatedByLine ? (
						<span className="flex min-w-0 font-mono" title={location.path}>
							{/* The directory gives way first; at the reflow width the file name alone must fit. */}
							{hasText(directory) && (
								<span className="hidden truncate font-normal text-muted-foreground sm:inline">
									{directory}
								</span>
							)}
							<span className="min-w-0 truncate sm:shrink-0">{fileName}</span>
						</span>
					) : (
						<span className="min-w-0 truncate">{source.label}</span>
					)}
				</CodeBlockTitle>
				{(lineRange !== undefined || sideLabel !== undefined) && (
					<span className="flex shrink-0 items-center">
						{lineRange !== undefined && <span className="font-mono">{lineRange}</span>}
						{lineRange !== undefined && sideLabel !== undefined && <DotSeparator inline />}
						{sideLabel}
					</span>
				)}
				{copyText !== undefined && (
					<CodeBlockActions>
						<CodeBlockCopyButton
							value={copyText}
							aria-label={locatedByLine ? `Copy passage from ${fileName}` : "Copy passage"}
						/>
					</CodeBlockActions>
				)}
			</CodeBlockHeader>
			{change !== undefined && (
				// max-h-36 shows about six code lines before the region scrolls on its own.
				<CodeBlockContent className="max-h-36">
					{before.map((line, index) => (
						<CodeBlockLine key={`OLD-${index}`} variant="removed">
							{line}
						</CodeBlockLine>
					))}
					{after.map((line, index) => (
						<CodeBlockLine key={`NEW-${index}`} variant="added">
							{line}
						</CodeBlockLine>
					))}
				</CodeBlockContent>
			)}
			{change === undefined && snippet !== undefined && (
				<CodeBlockContent
					className="max-h-36"
					lineNumbers={locatedByLine && location.startLine !== undefined}
					startLine={location.startLine}
				>
					{quoted.map((line, index) => (
						<CodeBlockLine key={index}>{line}</CodeBlockLine>
					))}
				</CodeBlockContent>
			)}
			{hasText(location.commit) && (
				<CodeBlockFooter>
					<p className="min-w-0 break-all">
						Commit <code>{location.commit}</code>
					</p>
				</CodeBlockFooter>
			)}
		</CodeBlock>
	);
}

/** A change as a diff reads: every line before it marked `-`, every line after it marked `+`. */
function unifiedDiff({ before, after }: { before: string; after: string }): string {
	return [
		...before.split("\n").map((line) => `-${line}`),
		...after.split("\n").map((line) => `+${line}`),
	].join("\n");
}
