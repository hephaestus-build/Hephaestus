import { type SubmitEvent, useId, useState } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { lastDayBefore, type ResearchReleaseReceipt } from "@/lib/research-release";
import { hasText } from "@/lib/text";

import {
	InstanceResearchDisclosureList,
	type InstanceResearchDisclosureListProps,
} from "./InstanceResearchDisclosureList";

/** The research question this instance asks now, as the server reports it. */
export type ResearchProgramState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	/** No organization means the instance runs no research program; nothing new can be released. */
	| { status: "ready"; organization?: string; noticeVersion: string };

/**
 * Whether this instance asks for a recent passkey confirmation, as the server reports it. The server
 * still checks every request; this only says what to do before one.
 */
export type ResearchPasskeyState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	/** The instance turned additional confirmation off. */
	| { status: "notRequired" }
	| {
			status: "required";
			/** A passkey is registered and this browser can use it. */
			available: boolean;
			pending: boolean;
			error?: string;
			onConfirm: () => void;
	  };

export type ResearchWorkspacesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; options: { id: string; label: string }[] };

/** The release form as entered: UTC calendar days, the last one included. */
export interface ResearchReleaseDraft {
	workspaceId: string;
	firstDay: string;
	lastDay: string;
	usePermittedThrough: string;
}

export type ResearchReleaseOutcome =
	| { kind: "receipt"; receipt: ResearchReleaseReceipt }
	/** The file downloaded, but its header could not be read here. */
	| { kind: "downloaded" };

export interface InstanceResearchPageProps {
	program: ResearchProgramState;
	workspaces: ResearchWorkspacesState;
	passkey: ResearchPasskeyState;
	release: {
		pending: boolean;
		/** A failed release. The server may still have recorded it; the open datasets say so. */
		error?: unknown;
		outcome?: ResearchReleaseOutcome;
		/** How many releases completed on this page; a new count starts an empty form. */
		completed: number;
		onRelease: (draft: ResearchReleaseDraft) => void;
	};
	disclosures: InstanceResearchDisclosureListProps;
}

export function InstanceResearchPage({
	program,
	workspaces,
	passkey,
	release,
	disclosures,
}: InstanceResearchPageProps) {
	return (
		<div className="space-y-6">
			<PasskeyCard passkey={passkey} />
			<ReleaseCard program={program} workspaces={workspaces} release={release} />
			<InstanceResearchDisclosureList {...disclosures} />
		</div>
	);
}

function PasskeyCard({ passkey }: { passkey: ResearchPasskeyState }) {
	let description: string;
	if (passkey.status === "required") {
		description =
			"Exports, checks and dataset updates need a recent passkey confirmation. Confirm before you start, and again if an action is refused.";
	} else if (passkey.status === "notRequired") {
		description =
			"Extra authentication checks are disabled on this instance, so exports, checks and dataset updates need no passkey confirmation. They still need your own instance admin access.";
	} else {
		description =
			"This instance may ask for a recent passkey confirmation before exports, checks and dataset updates.";
	}
	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<h2>Passkey confirmation</h2>
				</CardTitle>
				<CardDescription>{description}</CardDescription>
			</CardHeader>
			{passkey.status === "loading" && (
				<CardContent aria-busy="true">
					<Skeleton className="h-9 w-48" />
					<span className="sr-only">Loading your passkey status</span>
				</CardContent>
			)}
			{passkey.status === "error" && (
				<CardContent>
					<QueryErrorAlert
						error={passkey.error}
						title="We could not load your passkey status"
						onRetry={passkey.onRetry}
					/>
				</CardContent>
			)}
			{passkey.status === "required" && (
				<CardContent className="space-y-3">
					{passkey.available ? (
						<Button
							variant="outline"
							className="w-fit"
							disabled={passkey.pending}
							onClick={passkey.onConfirm}
						>
							{passkey.pending && <Spinner />}
							{passkey.pending ? "Confirming…" : "Confirm with passkey"}
						</Button>
					) : (
						<p className="text-sm text-muted-foreground">
							Use a browser that supports passkeys, with a passkey registered in your account
							settings. Without one, you cannot export, check or update datasets here.
						</p>
					)}
					{hasText(passkey.error) && (
						<Alert variant="destructive">
							<AlertDescription>{passkey.error}</AlertDescription>
						</Alert>
					)}
				</CardContent>
			)}
		</Card>
	);
}

function ReleaseCard({
	program,
	workspaces,
	release,
}: Pick<InstanceResearchPageProps, "program" | "workspaces" | "release">) {
	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<h2>Export a dataset</h2>
				</CardTitle>
				<CardDescription>
					One workspace, one window of days, for the research organization the instance names.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-6">
				{program.status === "loading" && (
					<div aria-busy="true" className="space-y-3">
						<Skeleton className="h-5 w-2/3" />
						<Skeleton className="h-40 w-full" />
						<span className="sr-only">Loading the research question</span>
					</div>
				)}
				{program.status === "error" && (
					<QueryErrorAlert
						error={program.error}
						title="We could not load the research question"
						onRetry={program.onRetry}
					/>
				)}
				{program.status === "ready" && !hasText(program.organization) && (
					<Alert>
						<AlertTitle>This instance runs no research program</AlertTitle>
						<AlertDescription>
							You cannot export a new dataset. Datasets exported earlier stay open below until you
							close them.
						</AlertDescription>
					</Alert>
				)}
				{program.status === "ready" && hasText(program.organization) && (
					<>
						<dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
							<dt className="text-muted-foreground">Research organization</dt>
							<dd>{program.organization}</dd>
							<dt className="text-muted-foreground">Notice version</dt>
							<dd>{program.noticeVersion}</dd>
							<dt className="text-muted-foreground">Purpose</dt>
							<dd>Research participation, as the notice describes it</dd>
						</dl>
						<ReleaseDuties />
						{workspaces.status === "loading" && <Skeleton className="h-48 w-full" />}
						{workspaces.status === "error" && (
							<QueryErrorAlert
								error={workspaces.error}
								title="We could not load the workspaces"
								onRetry={workspaces.onRetry}
							/>
						)}
						{workspaces.status === "ready" && (
							<ReleaseForm
								// Each completed release remounts the form, so a second dataset needs a new deliberate
								// entry. A failed one keeps the entry.
								key={release.completed}
								options={workspaces.options}
								release={release}
							/>
						)}
					</>
				)}
				{release.error !== undefined && (
					<>
						<QueryErrorAlert
							error={release.error}
							title="The dataset was not delivered to this browser"
						/>
						<p className="text-sm text-muted-foreground">
							The export may have been recorded before the download failed. Check open datasets
							before you export again. A recorded export appears there and can be closed.
						</p>
					</>
				)}
				{release.outcome && <ReleaseOutcome outcome={release.outcome} />}
			</CardContent>
		</Card>
	);
}

function ReleaseDuties() {
	return (
		<Alert variant="warning">
			<AlertTitle>What you export, and what you owe</AlertTitle>
			<AlertDescription>
				<ul className="mt-1 list-disc space-y-1 pl-4">
					<li>
						The file is pseudonymous personal data, not anonymous. Keys replace names, but dates and
						outcomes can still identify people.
					</li>
					<li>
						It includes every member with a current research grant for this question, and only them.
						You cannot pick people.
					</li>
					<li>
						It holds structured observations, problems, feedback states and usage. It holds nothing
						that participants or models wrote, and no code, quotes or conversations. Its only text
						is the public criteria of practices shipped with Hephaestus, where a version matches
						them. Older observations say where their record is incomplete.
					</li>
					<li>
						Hephaestus does not keep the file. Give it only to the research team, store it where
						only they can reach it, and delete the browser download.
					</li>
					<li>
						Check open datasets before the team uses the data again, and close the dataset when
						every copy is deleted or anonymized.
					</li>
				</ul>
			</AlertDescription>
		</Alert>
	);
}

function ReleaseForm({
	options,
	release,
}: {
	options: { id: string; label: string }[];
	release: InstanceResearchPageProps["release"];
}) {
	const id = useId();
	const [draft, setDraft] = useState<ResearchReleaseDraft>({
		workspaceId: "",
		firstDay: "",
		lastDay: "",
		usePermittedThrough: "",
	});
	const [acknowledged, setAcknowledged] = useState(false);
	const [submitted, setSubmitted] = useState(false);
	const reversed =
		hasText(draft.firstDay) && hasText(draft.lastDay) && draft.lastDay < draft.firstDay;
	const missingWorkspace = submitted && !hasText(draft.workspaceId);
	const missingAcknowledgement = submitted && !acknowledged;

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		setSubmitted(true);
		if (!hasText(draft.workspaceId) || !acknowledged || reversed) {
			return;
		}
		release.onRelease(draft);
	}

	return (
		<form onSubmit={submit}>
			<FieldGroup>
				<Field orientation="responsive" data-invalid={missingWorkspace}>
					<FieldLabel htmlFor={`${id}-workspace`}>Workspace</FieldLabel>
					<Select
						items={options.map((option) => ({ value: option.id, label: option.label }))}
						value={hasText(draft.workspaceId) ? draft.workspaceId : null}
						disabled={release.pending}
						onValueChange={(value) => setDraft({ ...draft, workspaceId: value ?? "" })}
					>
						<SelectTrigger id={`${id}-workspace`} className="w-full @md/field-group:w-56">
							<SelectValue placeholder="Select a workspace" />
						</SelectTrigger>
						<SelectContent aria-label="Workspace">
							{options.map((option) => (
								<SelectItem key={option.id} value={option.id}>
									{option.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
					{missingWorkspace && <FieldError>Choose a workspace.</FieldError>}
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-first`}>First day (UTC)</FieldLabel>
					</FieldContent>
					<Input
						id={`${id}-first`}
						type="date"
						required
						disabled={release.pending}
						className="w-full @md/field-group:w-56"
						value={draft.firstDay}
						onChange={(event) => setDraft({ ...draft, firstDay: event.target.value })}
					/>
				</Field>
				<Field orientation="responsive" data-invalid={reversed}>
					<FieldContent>
						<FieldLabel htmlFor={`${id}-last`}>Last day (UTC)</FieldLabel>
						<FieldDescription>Included. The window can span up to 400 days.</FieldDescription>
						{reversed && <FieldError>The last day cannot come before the first day.</FieldError>}
					</FieldContent>
					<Input
						id={`${id}-last`}
						type="date"
						required
						disabled={release.pending}
						className="w-full @md/field-group:w-56"
						value={draft.lastDay}
						onChange={(event) => setDraft({ ...draft, lastDay: event.target.value })}
					/>
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-use`}>Use allowed through (UTC)</FieldLabel>
						<FieldDescription>
							From the study protocol. After this day the dataset must be deleted or anonymized.
						</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-use`}
						type="date"
						required
						disabled={release.pending}
						className="w-full @md/field-group:w-56"
						value={draft.usePermittedThrough}
						onChange={(event) => setDraft({ ...draft, usePermittedThrough: event.target.value })}
					/>
				</Field>
				<Field orientation="horizontal" data-invalid={missingAcknowledgement}>
					<Checkbox
						id={`${id}-acknowledge`}
						checked={acknowledged}
						disabled={release.pending}
						onCheckedChange={setAcknowledged}
						aria-invalid={missingAcknowledgement}
						aria-describedby={missingAcknowledgement ? `${id}-acknowledge-error` : undefined}
					/>
					<FieldContent>
						<FieldLabel htmlFor={`${id}-acknowledge`}>
							I give this file only to the research team, and I will check removals before it is
							used again.
						</FieldLabel>
						{missingAcknowledgement && (
							<FieldError id={`${id}-acknowledge-error`}>
								Confirm this before you export.
							</FieldError>
						)}
					</FieldContent>
				</Field>
				<Button type="submit" className="w-fit" disabled={release.pending}>
					{release.pending && <Spinner />}
					{release.pending ? "Exporting…" : "Export and download"}
				</Button>
			</FieldGroup>
		</form>
	);
}

function ReleaseOutcome({ outcome }: { outcome: ResearchReleaseOutcome }) {
	if (outcome.kind === "downloaded") {
		return (
			<Alert>
				<AlertTitle>The file downloaded</AlertTitle>
				<AlertDescription>
					This page could not read the file’s header. The dataset is listed under open datasets.
				</AlertDescription>
			</Alert>
		);
	}
	const { receipt } = outcome;
	return (
		<section aria-labelledby="research-release-receipt" className="space-y-2 rounded-lg border p-4">
			<h3 id="research-release-receipt" className="font-medium">
				Dataset exported
			</h3>
			<p className="text-sm text-muted-foreground">
				The file downloaded once. Hephaestus does not keep it, so you cannot download it again.
			</p>
			<dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
				<dt className="text-muted-foreground">Dataset</dt>
				<dd>
					<code className="break-all">{receipt.datasetId}</code>
				</dd>
				<dt className="text-muted-foreground">Days</dt>
				<dd>
					{receipt.window.from} to {lastDayBefore(receipt.window.to)} (UTC)
				</dd>
				<dt className="text-muted-foreground">Use allowed through</dt>
				<dd>{lastDayBefore(receipt.useUntil)} (UTC)</dd>
				<dt className="text-muted-foreground">Participants</dt>
				<dd className="tabular-nums">{receipt.counts.participants}</dd>
				<dt className="text-muted-foreground">Observations, problems, feedback</dt>
				<dd className="tabular-nums">
					{receipt.counts.results}, {receipt.counts.problems}, {receipt.counts.feedback}
				</dd>
				{receipt.counts.feedbackExcludedIncompleteBasis > 0 && (
					<>
						<dt className="text-muted-foreground">Feedback left out</dt>
						<dd className="tabular-nums">
							{receipt.counts.feedbackExcludedIncompleteBasis}, because their support fell outside
							the export
						</dd>
					</>
				)}
			</dl>
		</section>
	);
}
