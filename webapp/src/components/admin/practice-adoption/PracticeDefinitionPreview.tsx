import { cn } from "cn";
import type { CuratedPracticeDefinition, PracticeDefinitionOptions } from "@/api/types.gen";
import { PracticeEvidenceSummary } from "@/components/admin/practice-catalog/PracticeEvidenceSummary";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { Section } from "@/components/core/Section";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Separator } from "@/components/ui/separator";
import { artifactKindLabel } from "@/lib/artifact-kinds";

export interface PracticeDefinitionPreviewProps {
	definition: CuratedPracticeDefinition;
	options: PracticeDefinitionOptions;
}

/**
 * The habit first, the rule last. `criteria` addresses the *model* in the second person and runs to
 * thousands of characters once the server composes its work-type preamble in, so leading with it
 * buries `whyItMatters` — the field that answers "do we want this habit". It stays reachable behind
 * a disclosure, next to the precompute script, because adopting an automated critic without being
 * able to read its rule is worse.
 */
export function PracticeDefinitionPreview({ definition, options }: PracticeDefinitionPreviewProps) {
	const workType = options.workTypes.find(
		(candidate) => candidate.artifactKind === definition.artifactKind,
	);

	return (
		<div className="space-y-6">
			{definition.whyItMatters && (
				<p className="max-w-2xl text-pretty text-lg leading-relaxed">{definition.whyItMatters}</p>
			)}

			{definition.whatGoodLooksLike && (
				<Section size="sm" level={3} title="What good looks like">
					<p className="max-w-2xl text-pretty text-muted-foreground">
						{definition.whatGoodLooksLike}
					</p>
				</Section>
			)}

			<Separator />

			<Accordion aria-label="Practice review details">
				<AccordionItem value="review-mechanics">
					<AccordionTrigger>What it reads</AccordionTrigger>
					<AccordionContent className="pt-2">
						<PracticeEvidenceSummary
							policy={definition.automatedReviewPolicy}
							bindings={definition.bindings}
							validation={definition.automatedReviewValidation}
							sources={workType?.allowedSources ?? []}
							signals={workType?.signals ?? []}
							workTypeLabel={artifactKindLabel(definition.artifactKind)}
							showValidation
						/>
					</AccordionContent>
				</AccordionItem>
				<AccordionItem value="review-rule">
					<AccordionTrigger>How it decides</AccordionTrigger>
					<AccordionContent>
						{/* The editor promises "Markdown is supported", and this is the one definition field
						    that uses it. `max-w-2xl` because the drawer reaches 62rem. */}
						<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-2xl")}>
							<UntrustedMarkdown>{definition.criteria}</UntrustedMarkdown>
						</div>
					</AccordionContent>
				</AccordionItem>
				{definition.precomputeScript && (
					<AccordionItem value="static-analysis">
						<AccordionTrigger>What it measures first</AccordionTrigger>
						<AccordionContent>
							<pre className="max-h-80 overflow-auto rounded-md bg-muted p-3 text-xs">
								<code>{definition.precomputeScript}</code>
							</pre>
						</AccordionContent>
					</AccordionItem>
				)}
			</Accordion>
		</div>
	);
}
