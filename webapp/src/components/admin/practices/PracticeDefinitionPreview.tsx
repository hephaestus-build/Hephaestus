import { cn } from "cn";
import type { CuratedPracticeDefinition, PracticeDefinitionOptions } from "@/api/types.gen";
import { deliveryBehaviorSentences } from "@/components/admin/practice-editor/delivery-behavior-text";
import { PracticeEvidenceSummary } from "@/components/admin/practice-editor/PracticeEvidenceSummary";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { PracticeGuideMarkdown } from "@/components/practice-guidance/PracticeGuideMarkdown";
import { PracticeIntro } from "@/components/practice-guidance/PracticeIntro";
import { LabelledBlock } from "@/components/practice-profile/practice-profile-blocks";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Separator } from "@/components/ui/separator";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

export interface PracticeDefinitionPreviewProps {
	definition: CuratedPracticeDefinition;
	options: PracticeDefinitionOptions;
}

/**
 * The practice first, the rule last. The practice is what developers read: its introduction exactly
 * as their practice level draws it, then the guide in full, since an admin adopting a practice is
 * deciding on that text too. `criteria` addresses the *model* in the second person and runs to
 * thousands of characters once the server composes its kind-of-work preamble in, so leading with it
 * buries `whyItMatters` — the field that answers "do we want this practice". It stays reachable behind
 * a disclosure, next to the precompute script, because adopting an automated critic without being
 * able to read its rule is worse.
 */
export function PracticeDefinitionPreview({ definition, options }: PracticeDefinitionPreviewProps) {
	const workType = options.workTypes.find(
		(candidate) => candidate.artifactKind === definition.artifactKind,
	);
	const deliverySentences = deliveryBehaviorSentences(definition.deliveryBehavior);

	return (
		<div className="space-y-6">
			<PracticeIntro
				practice={definition}
				guidance={{ status: "ready", visual: definition.visual }}
			/>

			{definition.guide && (
				// `h3`: the guide's own headings are `h4`, and the outline may not skip a level.
				<LabelledBlock label="Guide" as="h3" className="flex flex-col gap-3">
					<PracticeGuideMarkdown guide={definition.guide} />
				</LabelledBlock>
			)}

			<Separator />

			<Accordion aria-label="Practice review details">
				<AccordionItem value="review-mechanics">
					<AccordionTrigger>Review scope and evidence</AccordionTrigger>
					<AccordionContent className="pt-2">
						<PracticeEvidenceSummary
							policy={definition.automatedReviewPolicy}
							{...definition}
							validation={definition.automatedReviewValidation}
							sources={workType?.allowedSources ?? []}
							signalOptions={workType?.signals ?? []}
							reviewWhenDimensions={workType?.reviewWhenDimensions ?? []}
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
				{deliverySentences.length > 0 && (
					<AccordionItem value="delivery-behavior">
						<AccordionTrigger>How feedback is delivered</AccordionTrigger>
						<AccordionContent>
							<ul className="list-inside list-disc text-sm text-muted-foreground">
								{deliverySentences.map((sentence) => (
									<li key={sentence}>{sentence}</li>
								))}
							</ul>
						</AccordionContent>
					</AccordionItem>
				)}
				{hasText(definition.precomputeScript) && (
					<AccordionItem value="static-analysis">
						<AccordionTrigger>Static analysis</AccordionTrigger>
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
