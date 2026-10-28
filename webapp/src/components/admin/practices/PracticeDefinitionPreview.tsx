import type { ReactNode } from "react";

import { cn } from "cn";
import type { PracticeDefinition, PracticeDefinitionOptions } from "@/api/types.gen";
import {
	appliesWhenLines,
	READ_NEED_LABELS,
	reviewedByLines,
	scanLines,
} from "@/components/admin/practices/practice-definition-fields";
import { DetailRow } from "@/components/common/DetailRow";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { PracticeGuideMarkdown } from "@/components/practice-guidance/PracticeGuideMarkdown";
import { PracticeIntro } from "@/components/practice-guidance/PracticeIntro";
import { LabelledBlock } from "@/components/practice-profile/practice-profile-blocks";
import { aboutLabel } from "@/components/practice-vocabulary/about-role-labels";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";

export interface PracticeDefinitionPreviewProps {
	slug: string;
	/** The kind of item the practice reviews, which the definition itself does not carry. */
	kind: string;
	definition: PracticeDefinition;
	options: PracticeDefinitionOptions;
	version?: number;
	/**
	 * What the practice script needs in this workspace, above its source. Only a workspace has
	 * models to assign, so the catalog passes none.
	 */
	practiceScript?: ReactNode;
	/**
	 * `4` where the definition sits under a record sheet's `h3` section: its labels and folds take
	 * `h4`, and the headings of the guide and of the criteria `h5`. Left out, it keeps the levels of
	 * the practice pages.
	 */
	headingLevel?: 4;
}

function Lines({ lines }: { lines: readonly string[] }) {
	return lines.length === 1 ? (
		lines[0]
	) : (
		<ul className="space-y-0.5">
			{lines.map((line) => (
				<li key={line}>{line}</li>
			))}
		</ul>
	);
}

export function PracticeDefinitionPreview({
	slug,
	kind,
	definition,
	options,
	version,
	practiceScript,
	headingLevel,
}: PracticeDefinitionPreviewProps) {
	const itemType = options.itemTypes.find((candidate) => candidate.kind === kind);
	const eventLabel = (name: string) =>
		itemType?.events.find((event) => event.name === name)?.label ?? name;
	const sourceLabel = (source: string) =>
		itemType?.readSources.find((option) => option.source === source)?.label ?? source;
	const stateLines = Object.entries(definition.reviewOn.state).map(([name, values]) => {
		const dimension = itemType?.reviewOnStateDimensions.find(
			(candidate) => candidate.name === name,
		);
		const valueLabel = (value: string) =>
			dimension?.values.find((candidate) => candidate.value === value)?.label ?? value;
		return `${dimension?.label ?? name}: ${values.map(valueLabel).join(", ")}`;
	});
	// A stored practice is not checked again, so one can name what its kind does not offer. Hephaestus
	// never selects it; say so here, where an admin can change it.
	const unofferedEvents = definition.reviewOn.events.filter(
		(name) => itemType !== undefined && !itemType.events.some((event) => event.name === name),
	);
	const unofferedAbout =
		itemType !== undefined &&
		definition.about !== null &&
		!itemType.aboutRoles.includes(definition.about);
	const anyEventLabel = (name: string) =>
		options.itemTypes.flatMap((type) => type.events).find((event) => event.name === name)?.label ??
		name;
	const { guidance, script, scan } = definition;
	const scanSupported = itemType?.scanSupported === true;

	return (
		<div className="space-y-6">
			<PracticeIntro
				labelAs={headingLevel === 4 ? "h4" : "h2"}
				practice={guidance}
				guidance={{
					status: "ready",
					practiceSlug: slug,
					guide: guidance.guide,
					visual: guidance.visual,
				}}
			/>

			{guidance.guide !== null && (
				// One above the guide's own headings, as the outline may not skip a level.
				<LabelledBlock
					label="Guide"
					as={headingLevel === 4 ? "h4" : "h3"}
					className="flex flex-col gap-3"
				>
					<PracticeGuideMarkdown guide={guidance.guide} headingLevel={headingLevel === 4 ? 5 : 4} />
				</LabelledBlock>
			)}

			<Separator />

			{(unofferedEvents.length > 0 || unofferedAbout) && (
				<Alert variant="warning">
					<AlertTitle>Hephaestus does not run this practice</AlertTitle>
					<AlertDescription>
						{unofferedEvents.length > 0 &&
							`It reviews on ${unofferedEvents.map(anyEventLabel).join(", ")}, which this kind of work does not offer. `}
						{unofferedAbout &&
							`It is about the ${aboutLabel(definition.about).toLowerCase()}, a role this kind of work does not have. `}
						Change its review settings to ones that this kind of work offers.
					</AlertDescription>
				</Alert>
			)}

			<section aria-label="Review settings">
				<dl className="divide-y">
					<DetailRow label="Reviews">
						<WorkTypeLabel itemKind={kind} />
					</DetailRow>
					<DetailRow label="Review on">
						<Lines
							lines={
								definition.reviewOn.events.length === 0 && stateLines.length === 0
									? ["Only when someone asks"]
									: [...definition.reviewOn.events.map(eventLabel), ...stateLines]
							}
						/>
					</DetailRow>
					<DetailRow label="Applies when">
						<Lines lines={appliesWhenLines(definition.appliesWhen)} />
					</DetailRow>
					<DetailRow label="About">{aboutLabel(definition.about)}</DetailRow>
					<DetailRow label="Reviewed by">
						<Lines lines={reviewedByLines(definition.reviewedBy)} />
					</DetailRow>
					{definition.reads.sources.length > 0 && (
						<DetailRow label="Reads">
							<Lines
								lines={definition.reads.sources.map(
									({ source, need }) => `${sourceLabel(source)} (${READ_NEED_LABELS[need]})`,
								)}
							/>
						</DetailRow>
					)}
					<DetailRow label="Script">{script === null ? "Built-in" : "Practice script"}</DetailRow>
					<DetailRow label="Scan support">
						{scanSupported || scan !== null ? (
							<Lines lines={scanLines(scan)} />
						) : (
							"Scans do not review this kind of work"
						)}
					</DetailRow>
					{version !== undefined && <DetailRow label="Version">Version {version}</DetailRow>}
				</dl>
			</section>

			<Accordion aria-label="Practice review details">
				<AccordionItem value="review-rule">
					<AccordionTrigger headingLevel={headingLevel}>How it decides</AccordionTrigger>
					<AccordionContent>
						{/* The editor promises "Markdown is supported", and this is the one definition field
						    that uses it. `max-w-2xl` because the drawer reaches 62rem. */}
						<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-2xl")}>
							<UntrustedMarkdown headingLevel={headingLevel === 4 ? 5 : 4}>
								{definition.criteria}
							</UntrustedMarkdown>
						</div>
					</AccordionContent>
				</AccordionItem>
				{script !== null && (
					<AccordionItem value="practice-script">
						<AccordionTrigger headingLevel={headingLevel}>Practice script</AccordionTrigger>
						{/* Gaps rather than margins: the panel's own paragraph margin would double them. */}
						<AccordionContent className="flex flex-col gap-4 [&_p:not(:last-child)]:mb-0">
							{practiceScript}
							<pre
								// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Keyboard users must be able to scroll this region.
								tabIndex={0}
								className="max-h-80 overflow-auto rounded-md bg-muted p-3 text-xs"
							>
								<code>{script}</code>
							</pre>
						</AccordionContent>
					</AccordionItem>
				)}
			</Accordion>
		</div>
	);
}
