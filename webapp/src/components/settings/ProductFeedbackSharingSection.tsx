import type { ProductFeedbackSharing, UpdateProductFeedbackSharing } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { hasText } from "@/lib/text";

export type ProductFeedbackSharingSectionState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			sharing: ProductFeedbackSharing;
			/** The change in flight, which both switches wait out. */
			pending: boolean;
			onChange: (next: UpdateProductFeedbackSharing) => void;
	  };

export interface ProductFeedbackSharingSectionProps {
	state: ProductFeedbackSharingSectionState;
}

/** The section has nothing to say on an instance with no public repository. */
export function hasProductFeedbackSharing(state: ProductFeedbackSharingSectionState): boolean {
	return (
		state.status === "error" ||
		(state.status === "ready" && hasText(state.sharing.publicRepository))
	);
}

const DATE_FORMAT = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

/**
 * The remembered choice about public GitHub issues, which the composer asks the first time, and the
 * opt-in mention. Each change is recorded with its date, so withdrawing is one switch.
 */
export function ProductFeedbackSharingSection({ state }: ProductFeedbackSharingSectionProps) {
	return (
		<section
			id="product-feedback"
			className="scroll-mt-20 space-y-4"
			aria-labelledby="product-feedback-heading"
		>
			<h2 id="product-feedback-heading" className="text-xl font-semibold">
				Product feedback
			</h2>
			{state.status === "loading" && <Skeleton className="h-10 rounded-lg" />}
			{state.status === "error" && (
				<QueryErrorAlert
					title="We could not load your product feedback choices"
					error={state.error}
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && <SharingSwitches {...state} />}
		</section>
	);
}

function SharingSwitches({
	sharing,
	pending,
	onChange,
}: Extract<ProductFeedbackSharingSectionState, { status: "ready" }>) {
	const allowPublic = sharing.allowPublic === true;
	const repository = sharing.publicRepository ?? "the public repository";
	return (
		<div className="space-y-4">
			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="product-feedback-public">Public GitHub issues</FieldLabel>
					<FieldDescription>
						{sharing.allowPublic === undefined
							? `You have not chosen yet. When this is on, your reports can become public issues in ${repository}.`
							: `When this is on, your reports can become public issues in ${repository}. You can still change it for each report.`}
					</FieldDescription>
				</FieldContent>
				<Switch
					id="product-feedback-public"
					checked={allowPublic}
					readOnly={pending}
					onCheckedChange={(next) => onChange({ allowPublic: next, mention: sharing.mention })}
				/>
			</Field>
			{hasText(sharing.githubLogin) && (
				// Until the first choice, a mention change would record "private" as that choice.
				<Field
					orientation="horizontal"
					data-disabled={sharing.allowPublic === undefined || undefined}
				>
					<FieldContent>
						<FieldLabel htmlFor="product-feedback-mention">
							Mention my GitHub account (@{sharing.githubLogin}) on public issues
						</FieldLabel>
						<FieldDescription>Anyone can then see that you sent it.</FieldDescription>
					</FieldContent>
					<Switch
						id="product-feedback-mention"
						checked={sharing.mention}
						disabled={sharing.allowPublic === undefined}
						readOnly={pending}
						onCheckedChange={(next) => onChange({ allowPublic, mention: next })}
					/>
				</Field>
			)}
			<p className="text-sm text-muted-foreground">
				Your choices are recorded with the date.
				{sharing.decidedAt !== undefined &&
					` Last changed on ${DATE_FORMAT.format(sharing.decidedAt)}.`}
			</p>
		</div>
	);
}
