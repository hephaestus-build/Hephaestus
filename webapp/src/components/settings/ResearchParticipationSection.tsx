import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";

export interface ResearchParticipationSectionProps {
	/** The organisation running the study, named on the first-login screen this answer came from. */
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
					Academic research participation
				</h2>
				<p className="text-sm text-muted-foreground">
					This optional choice does not affect access to Hephaestus.
				</p>
			</div>

			{isError ? (
				<QueryErrorAlert
					title="Could not load your research participation choice"
					error={error}
					onRetry={onRetry}
				/>
			) : (
				<Field orientation="horizontal">
					<FieldContent>
						<FieldLabel htmlFor="research-participation">
							Participate in academic research
						</FieldLabel>
						<FieldDescription>
							When enabled, {organization} may use your Hephaestus usage and feedback interactions
							for the academic research described in the{" "}
							<a
								href="/privacy"
								target="_blank"
								rel="noreferrer"
								className="underline underline-offset-4"
							>
								privacy notice (opens in a new tab)
							</a>{" "}
							and may invite you to occasional surveys. Turning it off records your withdrawal,
							dated and kept with the version of the notice you were shown.
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
			)}
		</section>
	);
}
