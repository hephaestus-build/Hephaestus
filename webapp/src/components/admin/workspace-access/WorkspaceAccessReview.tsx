import { useId, useState } from "react";

import type {
	ReviewWorkspaceAccessRequest,
	WorkspaceAccessRequest,
	WorkspaceAccessReviewOptions,
} from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

interface WorkspaceAccessReviewProps {
	request: WorkspaceAccessRequest;
	options: WorkspaceAccessReviewOptions;
	canReview: boolean;
	pending: boolean;
	error?: string;
	onReview: (review: ReviewWorkspaceAccessRequest) => void;
}

/** Owns the final approval details; the route keys it by request ID and version. */
export function WorkspaceAccessReview({
	request,
	options,
	canReview,
	pending,
	error,
	onReview,
}: WorkspaceAccessReviewProps) {
	const id = useId();
	const now = useNow();
	const [details, setDetails] = useState(request.approvedDetails ?? request.requestedDetails);
	const [comment, setComment] = useState("");
	const [expires, setExpires] = useState(details.expiresAt.toISOString().slice(0, 16));
	const maintainers = options.maintainers.filter(
		(maintainer) => maintainer.accountId !== request.accountId,
	);
	const actionable = canReview && request.status === "SUBMITTED";
	const validExpiry =
		new Date(`${expires}Z`).getTime() > now &&
		new Date(`${expires}Z`).getTime() <= now + (options.maximumDurationDays ?? 0) * 86_400_000;
	const validDetails =
		validExpiry &&
		maintainers.some((maintainer) => maintainer.accountId === details.maintainerAccountId) &&
		details.teamIds.every((teamId) => options.requestableTeams.some((team) => team.id === teamId));
	const decide = (decision: ReviewWorkspaceAccessRequest["decision"]) =>
		onReview({
			version: request.version,
			decision,
			comment: comment.trim() || undefined,
			details:
				decision === "APPROVE" ? { ...details, expiresAt: new Date(`${expires}Z`) } : undefined,
		});

	return (
		<section className="space-y-6 rounded-lg border p-6" aria-labelledby={`${id}-heading`}>
			<h2 id={`${id}-heading`} className="text-xl font-semibold">
				Request #{request.id} · {request.displayName}
			</h2>
			<p className="text-sm text-muted-foreground">
				Submitted {request.submittedAt.toLocaleString()} · policy version {request.policyVersion}
			</p>
			{request.comments && (
				<div>
					<h3 className="font-semibold">Applicant comments</h3>
					<p className="whitespace-pre-wrap">{request.comments}</p>
				</div>
			)}
			{request.decisionComment && (
				<div>
					<h3 className="font-semibold">Review explanation</h3>
					<p className="whitespace-pre-wrap">{request.decisionComment}</p>
				</div>
			)}
			{!canReview && request.status === "SUBMITTED" && (
				<p>Another administrator must review your request.</p>
			)}
			<FieldGroup>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-maintainer`}>Responsible maintainer</FieldLabel>
					<Select
						value={details.maintainerAccountId}
						onValueChange={(value) => {
							if (value !== null) setDetails({ ...details, maintainerAccountId: value });
						}}
						items={maintainers.map((maintainer) => ({
							value: maintainer.accountId,
							label: maintainer.displayName,
						}))}
						disabled={pending || !actionable}
					>
						<SelectTrigger
							id={`${id}-maintainer`}
							aria-label="Responsible maintainer"
							className="w-full @md/field-group:w-56"
						>
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-label="Responsible maintainer">
							{maintainers.map((maintainer) => (
								<SelectItem key={maintainer.accountId} value={maintainer.accountId}>
									{maintainer.displayName}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
				<FieldSet>
					<FieldLegend>Approved teams</FieldLegend>
					{options.requestableTeams.map((team) => (
						<Field key={team.id} orientation="horizontal">
							<Checkbox
								id={`${id}-team-${team.id}`}
								checked={details.teamIds.includes(team.id)}
								disabled={pending || !actionable}
								onCheckedChange={(checked) =>
									setDetails({
										...details,
										teamIds: checked
											? [...details.teamIds, team.id]
											: details.teamIds.filter((value) => value !== team.id),
									})
								}
							/>
							<FieldLabel htmlFor={`${id}-team-${team.id}`}>{team.name}</FieldLabel>
						</Field>
					))}
				</FieldSet>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-expires`}>Access until (UTC)</FieldLabel>
					<Input
						id={`${id}-expires`}
						type="datetime-local"
						value={expires}
						disabled={pending || !actionable}
						onChange={(event) => setExpires(event.target.value)}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
				{actionable && (
					<Field>
						<FieldLabel htmlFor={`${id}-comment`}>Explanation to the applicant</FieldLabel>
						<Textarea
							id={`${id}-comment`}
							maxLength={4000}
							value={comment}
							disabled={pending}
							onChange={(event) => setComment(event.target.value)}
						/>
						<p className="text-sm text-muted-foreground">
							Required when requesting changes or declining access.
						</p>
					</Field>
				)}
			</FieldGroup>
			{error && (
				<p role="alert" className="text-destructive">
					{error}
				</p>
			)}
			{actionable && (
				<>
					{!validDetails && (
						<p className="text-sm text-destructive">
							Choose a currently eligible maintainer and teams, and an end date within the policy's
							allowed duration.
						</p>
					)}
					<p className="text-sm text-muted-foreground">
						Approval grants Hephaestus access until this deadline. External invitations and team
						changes have separate delivery status.
					</p>
					<div className="flex flex-wrap gap-2">
						<Button disabled={pending || !validDetails} onClick={() => decide("APPROVE")}>
							{pending && <Spinner />}
							{pending ? "Saving decision…" : "Approve access"}
						</Button>
						<Button
							variant="outline"
							disabled={pending || !comment.trim()}
							onClick={() => decide("REQUEST_CHANGES")}
						>
							Request changes
						</Button>
						<Button
							variant="destructive"
							disabled={pending || !comment.trim()}
							onClick={() => decide("REJECT")}
						>
							Decline access
						</Button>
					</div>
				</>
			)}
		</section>
	);
}
