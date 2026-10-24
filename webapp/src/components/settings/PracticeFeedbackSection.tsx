import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";

export interface PracticeFeedbackSectionProps {
	practiceFeedbackDeliveryEnabled: boolean;
	onTogglePracticeFeedback: (checked: boolean) => void;
	isLoading?: boolean;
}

export function PracticeFeedbackSection({
	practiceFeedbackDeliveryEnabled,
	onTogglePracticeFeedback,
	isLoading = false,
}: PracticeFeedbackSectionProps) {
	return (
		<section
			id="practice-feedback"
			className="scroll-mt-20 space-y-4"
			aria-labelledby="practice-feedback-heading"
		>
			<div className="space-y-1">
				<h2 id="practice-feedback-heading" className="text-xl font-semibold">
					Practice feedback
				</h2>
				<p className="text-sm text-muted-foreground">
					Your workspaces can deliver AI-generated practice feedback on work you author. You can
					turn it off below.
				</p>
			</div>

			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="practice-feedback-delivery">Comments on your work</FieldLabel>
					<FieldDescription>
						Turn this off to stop new comments on issues, pull requests, and merge requests you
						author. Reviews still run, and workspace admins can still see the observations. This
						choice applies in every workspace.
					</FieldDescription>
				</FieldContent>
				<Switch
					id="practice-feedback-delivery"
					checked={practiceFeedbackDeliveryEnabled}
					onCheckedChange={onTogglePracticeFeedback}
					disabled={isLoading}
					aria-busy={isLoading}
				/>
			</Field>
		</section>
	);
}
