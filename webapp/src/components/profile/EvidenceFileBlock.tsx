import { cn } from "cn";
import { ChevronDownIcon, ShieldAlertIcon } from "lucide-react";
import { useId, useState } from "react";
import {
	DIFF_SIDE_LABELS,
	evidenceSourceDef,
} from "@/components/practice-vocabulary/evidence-source-defs";
import { EvidenceRevision } from "@/components/practice-vocabulary/EvidenceRevision";
import { Badge } from "@/components/ui/badge";
import { type EvidenceLocation, evidenceLineRangeLabel, splitPath } from "./evidence";
const SECRET_SCANNER = "secret-diff-scanner";

interface EvidenceFileBlockProps {
	location: EvidenceLocation;
	detector?: string;
	defaultOpen?: boolean;
}
export function EvidenceFileBlock({
	location,
	detector,
	defaultOpen = true,
}: EvidenceFileBlockProps) {
	const [isOpen, setIsOpen] = useState(defaultOpen);
	const instanceId = useId();
	const source = evidenceSourceDef(location.sourceKind);
	const locatedByLine = source.locator === "code";
	const { directory, fileName } = splitPath(location.path);
	const lines = location.snippet?.split("\n") ?? [];
	const firstLineNumber = location.startLine;
	const hasSnippet = lines.length > 0;
	const bodyId = `evidence-${location.path.replace(/[^\w-]/g, "-")}-${firstLineNumber}-${instanceId}`;
	const SourceIcon = source.icon;

	return (
		<figure className="min-w-0 overflow-hidden rounded-md border">
			<figcaption
				className={cn(
					"flex min-w-0 items-center gap-2 bg-code-header px-2.5 py-1.5",
					isOpen && hasSnippet && "border-b",
				)}
			>
				<SourceIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
				<span className="flex min-w-0 flex-1 font-mono text-xs" title={location.path}>
					{locatedByLine && directory && (
						<span className="truncate text-muted-foreground">{directory}</span>
					)}
					<span className={cn("min-w-0 font-medium", locatedByLine ? "shrink-0" : "truncate")}>
						{locatedByLine ? fileName : location.path}
					</span>
				</span>
				{locatedByLine && (
					<span className="shrink-0 font-mono text-xs text-muted-foreground">
						{evidenceLineRangeLabel(location)}
					</span>
				)}
				{locatedByLine && location.side === "OLD" && (
					<Badge variant="outline" className="shrink-0">
						{DIFF_SIDE_LABELS.OLD}
					</Badge>
				)}

				{hasSnippet && (
					<button
						type="button"
						aria-expanded={isOpen}
						aria-controls={bodyId}
						aria-label={`${isOpen ? "Hide" : "Show"} the quote from ${locatedByLine ? fileName : source.label}`}
						className="-me-1 shrink-0 rounded p-0.5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
						onClick={() => setIsOpen((open) => !open)}
					>
						<ChevronDownIcon
							className={cn("size-3.5 transition-transform", isOpen && "rotate-180")}
						/>
					</button>
				)}
			</figcaption>
			<EvidenceRevision revision={location.revision} />
			{location.redacted && (
				<p className="flex items-start gap-2 border-t p-3 text-sm text-muted-foreground">
					<ShieldAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					{detector === SECRET_SCANNER
						? "Not quoted. This looked like a credential, so the text was never stored — the path and line above are where it sits."
						: "Not quoted. The passage was withheld, so only its location was kept."}
				</p>
			)}
			{hasSnippet && isOpen && (
				<pre
					id={bodyId}
					// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Keyboard users must be able to scroll this region.
					tabIndex={0}
					className="max-h-72 overflow-auto bg-code py-2 font-mono text-xs leading-relaxed"
				>
					<code>
						{lines.map((line, index) => {
							const lineNumber = firstLineNumber + index;
							return (
								<span
									key={`${bodyId}-${lineNumber}`}
									className={cn("grid", locatedByLine ? "grid-cols-[auto_1fr]" : "grid-cols-1")}
								>
									{locatedByLine && (
										<span className="sticky left-0 select-none bg-inherit pe-3 ps-2.5 text-end tabular-nums text-muted-foreground">
											{lineNumber}
										</span>
									)}
									<span className={cn("pe-2.5", !locatedByLine && "ps-2.5")}>{line || " "}</span>
								</span>
							);
						})}
					</code>
				</pre>
			)}
		</figure>
	);
}
