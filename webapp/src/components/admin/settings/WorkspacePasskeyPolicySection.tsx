import { useId } from "react";

import { Button } from "@/components/ui/button";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { usePendingSpinner } from "@/lib/use-pending-spinner";

export interface WorkspacePasskeyPolicySectionProps {
	assuranceEnforced?: boolean;
	required?: boolean;
	instanceRequired?: boolean;
	owner: boolean;
	loading?: boolean;
	pending?: boolean;
	error?: string;
	onRetry: () => void;
	onChange: (required: boolean) => void;
}

export function WorkspacePasskeyPolicySection({
	assuranceEnforced = true,
	required,
	instanceRequired = false,
	owner,
	loading = false,
	pending = false,
	error,
	onRetry,
	onChange,
}: WorkspacePasskeyPolicySectionProps) {
	const id = useId();
	const instanceEnforced = assuranceEnforced && instanceRequired;
	const showSpinner = usePendingSpinner(pending);
	let description =
		"This policy is saved, but extra authentication checks are disabled on this instance.";
	if (assuranceEnforced) {
		description =
			required === true || instanceRequired
				? "Passkeys are required for workspace administration."
				: "Passkeys are optional for workspace administration.";
	}

	return (
		<section aria-labelledby={`${id}-heading`} aria-busy={loading} className="space-y-3">
			<h2 id={`${id}-heading`} className="text-lg font-semibold">
				Admin passkeys
			</h2>
			<p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
				{showSpinner && (
					<>
						<Spinner />
						Saving passkey policy…
					</>
				)}
			</p>
			<p className="text-sm text-muted-foreground">
				Require passkeys before admins and owners use workspace administration. Members can still
				use their ordinary access.
			</p>
			{loading ? (
				<Skeleton className="h-10 w-full" />
			) : (
				<>
					{required === undefined ? (
						<p>We could not load the passkey policy. Try again.</p>
					) : (
						<FieldGroup>
							<Field orientation="horizontal">
								<FieldContent>
									<FieldLabel htmlFor={`${id}-required`}>Require admin passkeys</FieldLabel>
									<FieldDescription id={`${id}-description`}>{description}</FieldDescription>
								</FieldContent>
								<Switch
									id={`${id}-required`}
									aria-describedby={`${id}-description`}
									aria-busy={pending}
									checked={required || instanceEnforced}
									disabled={pending || !owner || instanceEnforced}
									onCheckedChange={(checked) => onChange(checked)}
								/>
							</Field>
						</FieldGroup>
					)}
					{instanceEnforced && (
						<p>The instance requires passkeys. This workspace cannot weaken that requirement.</p>
					)}
					{!owner && required !== undefined && (
						<p>Only a workspace owner can change this policy.</p>
					)}
					{assuranceEnforced && owner && !instanceEnforced && (
						<p className="text-sm text-muted-foreground">
							Verify your passkey in User settings before you change this policy.
						</p>
					)}
				</>
			)}
			<div role="alert" aria-atomic="true">
				{error !== undefined && <p>{error}</p>}
			</div>
			{error !== undefined && (
				<Button variant="outline" onClick={onRetry}>
					Retry
				</Button>
			)}
		</section>
	);
}
