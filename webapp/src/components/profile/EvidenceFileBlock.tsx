import { cn } from "cn";
import { ShieldAlertIcon } from "lucide-react";

import {
	DIFF_SIDE_LABELS,
	evidenceSourceDef,
} from "@/components/practice-vocabulary/evidence-source-defs";
import { hasText } from "@/lib/text";

import { type EvidenceLocation, evidenceLineRangeLabel, splitPath } from "./evidence";

const SECRET_SCANNER = "secret-diff-scanner";

const DIFF_SIDES = ["OLD", "NEW"] as const;

/** What the line was and what it became, told apart by the wash the status colours give them. */
const DIFF_SIDE_TINTS = {
	// The heavier alpha in dark is the registry badges' own: at /10 a wash on the dark ground is
	// not there at all.
	OLD: "bg-destructive/10 dark:bg-destructive/20",
	NEW: "bg-success/10 dark:bg-success/20",
} satisfies Record<(typeof DIFF_SIDES)[number], string>;

interface EvidenceFileBlockProps {
	location: EvidenceLocation;
	detector?: string;
}

/**
 * One quoted citation behind an observation. What the caption may say is decided by the source's
 * locator: a `code` citation points at a real span of a real file, so it names the path and the
 * lines it was read from; an `object` citation points into the serialised context the review was
 * handed — `inputs/context/metadata.json`, line 9 — which is the runner's own file and not a place
 * the reader could open, so the caption names the source in the registry's words and no numbers at
 * all. Which side of the diff a passage came from is said on the quoted lines themselves, where
 * the reader is looking at them, rather than once in the caption above them. A quote verified
 * against a commit in the repository's history names that commit under the caption, since the
 * lines may no longer read so at the reviewed one.
 */
export function EvidenceFileBlock({ location, detector }: EvidenceFileBlockProps) {
	const source = evidenceSourceDef(location.sourceKind);
	const locatedByLine = source.locator === "code";
	const { directory, fileName } = splitPath(location.path);
	const { change } = location;
	const { side } = location;
	const lines = location.snippet?.split("\n") ?? [];
	const firstLineNumber = location.startLine;
	const hasSnippet = change !== undefined || lines.length > 0;
	const SourceIcon = source.icon;
	/** The side of the diff on the first line, the line number on a code quote, or nothing at all. */
	const gutterOf = (index: number): number | string | undefined => {
		if (side) {
			return index === 0 ? DIFF_SIDE_LABELS[side] : "";
		}
		return locatedByLine ? firstLineNumber + index : undefined;
	};

	return (
		<figure className="min-w-0 overflow-hidden rounded-xl border">
			<figcaption
				className={cn(
					"flex min-w-0 flex-wrap items-center gap-2 bg-code-header px-2.5 py-1.5",
					hasSnippet && "border-b",
				)}
			>
				<SourceIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
				{locatedByLine ? (
					<span className="flex min-w-0 flex-1 font-mono text-xs" title={location.path}>
						{/* The directory gives way first; at the reflow width the file name alone must fit. */}
						{hasText(directory) && (
							<span className="hidden truncate text-muted-foreground sm:inline">{directory}</span>
						)}
						<span className="min-w-0 truncate font-medium sm:shrink-0">{fileName}</span>
					</span>
				) : (
					<span className="min-w-0 flex-1 truncate text-xs font-medium">{source.label}</span>
				)}
				{locatedByLine && (
					<span className="shrink-0 font-mono text-xs text-muted-foreground">
						{evidenceLineRangeLabel(location)}
					</span>
				)}
				{hasText(location.revision) && (
					<p className="min-w-0 basis-full text-xs break-all text-muted-foreground">
						Commit <code>{location.revision}</code>
					</p>
				)}
			</figcaption>
			{location.redacted && (
				<p className="flex items-start gap-2 border-t p-3 text-sm text-muted-foreground">
					<ShieldAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					{detector === SECRET_SCANNER
						? "Not quoted. This looked like a credential, so the text was never stored. The path and line above are where it sits."
						: "Not quoted. The passage was withheld, so only its location was kept."}
				</p>
			)}
			{hasSnippet && (
				<pre
					// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Keyboard users must be able to scroll this region.
					tabIndex={0}
					// max-h-36 shows about six code lines before the region scrolls on its own.
					className="max-h-36 overflow-auto bg-code py-2 font-mono text-xs leading-4"
				>
					<code>
						{change
							? DIFF_SIDES.flatMap((diffSide) =>
									(diffSide === "OLD" ? change.before : change.after)
										.split("\n")
										.map((line, index) => (
											<QuoteLine
												key={`${diffSide}-${index}`}
												// The side is said once, on the first line it covers.
												gutter={index === 0 ? DIFF_SIDE_LABELS[diffSide] : ""}
												tint={DIFF_SIDE_TINTS[diffSide]}
											>
												{line}
											</QuoteLine>
										)),
								)
							: lines.map((line, index) => (
									<QuoteLine key={firstLineNumber + index} gutter={gutterOf(index)}>
										{line}
									</QuoteLine>
								))}
					</code>
				</pre>
			)}
		</figure>
	);
}

/** One line of a quote: what the gutter says about it, then the line itself. */
function QuoteLine({
	gutter,
	tint,
	children,
}: {
	/** The line's number, the side of the diff it was read from, or nothing at all. */
	gutter?: number | string;
	/** The wash the side of the diff gives the line, for a quote that shows both. */
	tint?: string;
	children: string;
}) {
	return (
		<span className={cn("grid", gutter === undefined ? "grid-cols-1" : "grid-cols-[auto_1fr]")}>
			{gutter !== undefined && (
				<span
					className={cn(
						"sticky left-0 bg-inherit ps-2.5 pe-3 text-muted-foreground select-none",
						// The two side words are not the same width, so their column is fixed and the code
						// beside them starts in the same place on both lines.
						typeof gutter === "string" ? "w-20" : "text-end tabular-nums",
					)}
				>
					{gutter}
				</span>
			)}
			{/* The wash is the line's, not the gutter's: over the tint the muted label would fall
			    under the contrast the gutter keeps everywhere else. */}
			<span className={cn("pe-2.5", gutter === undefined && "ps-2.5", tint)}>
				{children || " "}
			</span>
		</span>
	);
}
