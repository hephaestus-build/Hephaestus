import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";

export interface PublicActivitySectionProps {
	/** Whether public activity pages list this account's work. */
	visible: boolean;
	onVisibleChange: (visible: boolean) => void;
	isLoading?: boolean;
	isError?: boolean;
	error?: unknown;
	onRetry?: () => void;
}

/** The account-wide choice that every public activity page honours. */
export function PublicActivitySection({
	visible,
	onVisibleChange,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: PublicActivitySectionProps) {
	return (
		<section
			id="public-activity"
			className="scroll-mt-20 space-y-4"
			aria-labelledby="public-activity-heading"
		>
			<h2 id="public-activity-heading" className="text-xl font-semibold">
				Public activity pages
			</h2>
			{isError ? (
				<QueryErrorAlert
					title="We could not load your public activity choice"
					error={error}
					onRetry={onRetry}
				/>
			) : (
				<Field orientation="horizontal">
					<FieldContent>
						<FieldLabel htmlFor="public-activity-visible">
							Show me on public activity pages
						</FieldLabel>
						<FieldDescription>
							Public pages list your pull or merge requests, reviews and issues in public
							repositories. Turn this off to leave every public page.
						</FieldDescription>
					</FieldContent>
					<Switch
						id="public-activity-visible"
						checked={visible}
						onCheckedChange={(next) => onVisibleChange(next)}
						disabled={isLoading}
						aria-busy={isLoading}
					/>
				</Field>
			)}
		</section>
	);
}
