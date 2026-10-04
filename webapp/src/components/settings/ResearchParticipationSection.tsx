import { ResearchDetails, ResearchSummary } from "@/components/auth/consent-wording";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";

export interface ResearchParticipationSectionProps {
	/** The organization running the study, named beside the control so the reader knows whom the answer is for. */
	organization: string;
	participateInResearch: boolean;
	onToggleResearch: (checked: boolean) => void;
	isLoading?: boolean;
	isError?: boolean;
	error?: unknown;
	onRetry?: () => void;
}

export function ResearchParticipationSection({
	organization,
	participateInResearch,
	onToggleResearch,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: ResearchParticipationSectionProps) {
	return (
		<section className="space-y-4" aria-labelledby="research-heading">
			<div className="space-y-1">
				<h2 id="research-heading" className="text-xl font-semibold">
					Research participation
				</h2>
				<p className="text-sm text-muted-foreground">
					This is optional. Hephaestus works the same whichever you choose.
				</p>
			</div>

			{isError ? (
				<QueryErrorAlert
					title="We could not load your research choice"
					error={error}
					onRetry={onRetry}
				/>
			) : (
				<>
					<ResearchSummary organization={organization} />
					<ResearchDetails organization={organization} />
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor="research-participation">
								Allow research use of my data
							</FieldLabel>
							<FieldDescription>
								Turn this off to withdraw. Hephaestus records your answer with the date and the
								version of the text you saw.
							</FieldDescription>
						</FieldContent>
						<Switch
							id="research-participation"
							checked={participateInResearch}
							onCheckedChange={onToggleResearch}
							disabled={isLoading}
							aria-busy={isLoading}
						/>
					</Field>
				</>
			)}
		</section>
	);
}
