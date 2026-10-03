import { useState } from "react";
import type { PersonDataRequest, PersonDataProvider } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

export interface PersonIdentityInput {
	inputId: string;
	providerId: string;
	subject: string;
	teamId: string;
}
export interface PersonSelectionInput {
	accountId: string;
	identities: PersonIdentityInput[];
}
export type InstancePersonDataPageState =
	| { status: "empty" }
	| { status: "loading" }
	| { status: "error"; message: string; onRetry: () => void }
	| {
			status: "ready";
			request: PersonDataRequest;
			pendingAction?: "export" | "erase";
			onExport: () => void;
			onErase: (externalCopiesRemoved: boolean) => void;
			onRefresh: () => void;
	  };

interface Props {
	providers: PersonDataProvider[];
	selection: PersonSelectionInput;
	onChange: (selection: PersonSelectionInput) => void;
	onPreview: () => void;
	state: InstancePersonDataPageState;
}

const REQUEST_STATE_LABELS = {
	PREVIEW: "Personal-data preview",
	ERASING: "Erasure in progress",
	FAILED: "Erasure stopped — resume required",
	COMPLETE: "Erasure complete",
	EXPIRED: "Preview expired",
} satisfies Record<PersonDataRequest["state"], string>;

export function InstancePersonDataPage({
	providers,
	selection,
	onChange,
	onPreview,
	state,
}: Props) {
	const [confirmation, setConfirmation] = useState("");
	const [externalCopiesRemoved, setExternalCopiesRemoved] = useState(false);
	const pending =
		state.status === "loading" ||
		(state.status === "ready" &&
			(state.request.state === "ERASING" || state.pendingAction !== undefined));
	let eraseLabel = "Erase this person's data";
	if (state.status === "ready") {
		if (state.request.state === "FAILED") {
			eraseLabel = "Resume erasure";
		}
		if (state.pendingAction === "erase") {
			eraseLabel = "Starting erasure…";
		}
	}
	const updateIdentity = (index: number, patch: Partial<PersonIdentityInput>) =>
		onChange({
			...selection,
			identities: selection.identities.map((identity, position) =>
				position === index ? { ...identity, ...patch } : identity,
			),
		});
	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<CardTitle>Resolve a person</CardTitle>
					<CardDescription>
						Use an account ID or exact provider identities. Names, logins and email addresses are
						never used to match. Verify the requester&rsquo;s identity through your privacy
						procedure first.
					</CardDescription>
				</CardHeader>
				<CardContent>
					<form
						className="space-y-4"
						onSubmit={(event) => {
							event.preventDefault();
							setConfirmation("");
							setExternalCopiesRemoved(false);
							onPreview();
						}}
					>
						<div className="space-y-2">
							<Label htmlFor="person-account">Account ID (optional)</Label>
							<Input
								id="person-account"
								type="number"
								min="1"
								step="1"
								value={selection.accountId}
								disabled={pending}
								onChange={(event) => onChange({ ...selection, accountId: event.target.value })}
							/>
						</div>
						{selection.identities.map((identity, index) => (
							<fieldset key={identity.inputId} disabled={pending} className="space-y-3">
								<legend className="text-sm font-medium">Provider identity {index + 1}</legend>
								<div className="space-y-2">
									<Label htmlFor={`person-provider-${index}`}>Provider instance</Label>
									<Select
										items={providers.map((provider) => ({
											value: String(provider.id),
											label: `${provider.type} — ${provider.serverUrl}`,
										}))}
										value={identity.providerId || null}
										disabled={pending}
										onValueChange={(value) =>
											updateIdentity(index, { providerId: value ?? "", teamId: "" })
										}
									>
										<SelectTrigger id={`person-provider-${index}`} className="w-full">
											<SelectValue placeholder="Select an exact provider instance" />
										</SelectTrigger>
										<SelectContent aria-label={`Provider instance for identity ${index + 1}`}>
											{providers.map((provider) => (
												<SelectItem key={provider.id} value={String(provider.id)}>
													{provider.type} — {provider.serverUrl}
												</SelectItem>
											))}
										</SelectContent>
									</Select>
								</div>
								<div className="space-y-2">
									<Label htmlFor={`person-subject-${index}`}>Native user ID</Label>
									<Input
										id={`person-subject-${index}`}
										value={identity.subject}
										autoComplete="off"
										maxLength={255}
										onChange={(event) => updateIdentity(index, { subject: event.target.value })}
									/>
								</div>
								{providers.find((provider) => String(provider.id) === identity.providerId)?.type ===
								"SLACK" ? (
									<div className="space-y-2">
										<Label htmlFor={`person-team-${index}`}>Slack workspace ID</Label>
										<Input
											id={`person-team-${index}`}
											value={identity.teamId}
											required
											autoComplete="off"
											maxLength={255}
											onChange={(event) => updateIdentity(index, { teamId: event.target.value })}
										/>
									</div>
								) : null}
								<Button
									type="button"
									variant="outline"
									onClick={() =>
										onChange({
											...selection,
											identities: selection.identities.filter((_, position) => position !== index),
										})
									}
								>
									Remove identity {index + 1}
								</Button>
							</fieldset>
						))}
						<div className="flex flex-wrap gap-2">
							<Button
								type="button"
								variant="outline"
								disabled={pending || selection.identities.length >= 32}
								onClick={() =>
									onChange({
										...selection,
										identities: [
											...selection.identities,
											{ inputId: crypto.randomUUID(), providerId: "", subject: "", teamId: "" },
										],
									})
								}
							>
								Add provider identity
							</Button>
							<Button
								type="submit"
								disabled={
									pending ||
									(!selection.accountId &&
										!selection.identities.some(
											(identity) => identity.providerId && identity.subject,
										))
								}
							>
								Preview data
							</Button>
						</div>
					</form>
				</CardContent>
			</Card>
			{state.status === "empty" ? (
				<Card>
					<CardContent>
						<p>
							No person selected. A preview will list each store, including stores with no rows.
						</p>
					</CardContent>
				</Card>
			) : null}
			{state.status === "loading" ? (
				<div aria-busy="true" aria-label="Loading personal-data scope">
					<Skeleton className="h-48 w-full" />
					<p className="sr-only">Loading personal-data scope</p>
				</div>
			) : null}
			{state.status === "error" ? (
				<Alert variant="destructive">
					<AlertTitle>Could not complete the request</AlertTitle>
					<AlertDescription>
						<p>{state.message}</p>
						<Button variant="outline" onClick={state.onRetry}>
							Try again
						</Button>
					</AlertDescription>
				</Alert>
			) : null}
			{state.status === "ready" ? (
				<Card>
					<CardHeader>
						<CardTitle>{REQUEST_STATE_LABELS[state.request.state]}</CardTitle>
						<CardDescription>
							Request {state.request.id}. Export and erasure use this preview&rsquo;s row keys. A
							changed scope needs a new preview. Provider records can still be mirrored from
							upstream after erasure; processing of these identities stays blocked.
						</CardDescription>
					</CardHeader>
					<CardContent className="space-y-4">
						{state.request.scope == null ? null : (
							<section aria-label="Resolved identity scope" className="space-y-2">
								<h3 className="font-medium">Resolved identity scope</h3>
								{state.request.scope.accountId == null ? null : (
									<p>Account ID: {state.request.scope.accountId}</p>
								)}
								<ul className="space-y-1 text-sm">
									{state.request.scope.identities.map((identity) => (
										<li key={`${identity.providerId}:${identity.subject}:${identity.teamId ?? ""}`}>
											Provider instance {identity.providerId}, native user{" "}
											<code>{identity.subject}</code>
											{identity.teamId == null ? null : (
												<>
													; provider workspace <code>{identity.teamId}</code>
												</>
											)}
										</li>
									))}
								</ul>
							</section>
						)}
						<Table>
							<TableHeader>
								<TableRow>
									<TableHead>Store</TableHead>
									<TableHead>Selected rows</TableHead>
									<TableHead>Rows processed</TableHead>
								</TableRow>
							</TableHeader>
							<TableBody>
								{Object.entries(state.request.counts).map(([store, count]) => (
									<TableRow key={store}>
										<TableCell>{store}</TableCell>
										<TableCell>{count}</TableCell>
										<TableCell>{state.request.completed[store] ?? "—"}</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
						{state.request.externalDeliveries.length > 0 ? (
							<Alert variant="destructive">
								<AlertTitle>Remove provider copies first</AlertTitle>
								<AlertDescription>
									<p>
										Follow the admin runbook to un-deliver external feedback. Erasure does not
										remove provider comments.
									</p>
									<ul>
										{state.request.externalDeliveries.map((delivery) => (
											<li key={`${delivery.workspaceId}:${delivery.locator}`}>
												Workspace {delivery.workspaceId}: <code>{delivery.locator}</code>
											</li>
										))}
									</ul>
								</AlertDescription>
							</Alert>
						) : null}
						{state.request.state === "PREVIEW" || state.request.state === "FAILED" ? (
							<div className="space-y-3">
								{state.request.state === "PREVIEW" ? (
									<Button variant="outline" disabled={pending} onClick={state.onExport}>
										{state.pendingAction === "export" ? <Spinner /> : null}
										{state.pendingAction === "export" ? "Downloading…" : "Download JSON export"}
									</Button>
								) : (
									<p>
										The completed steps are retained. Resume to retry unfinished steps. Failure
										code: {state.request.failureCode}.
									</p>
								)}
								<div className="flex items-center gap-2">
									<Checkbox
										id="external-copies-removed"
										disabled={pending}
										checked={externalCopiesRemoved}
										onCheckedChange={setExternalCopiesRemoved}
									/>
									<Label htmlFor="external-copies-removed">
										I have checked and removed external feedback copies.
									</Label>
								</div>
								<div className="space-y-2">
									<Label htmlFor="person-erasure-confirmation">
										Type ERASE to confirm permanent erasure
									</Label>
									<Input
										id="person-erasure-confirmation"
										disabled={pending}
										autoComplete="off"
										value={confirmation}
										onChange={(event) => setConfirmation(event.target.value)}
									/>
								</div>
								<Button
									variant="destructive"
									disabled={
										pending ||
										confirmation !== "ERASE" ||
										(state.request.externalDeliveries.length > 0 && !externalCopiesRemoved)
									}
									onClick={() => state.onErase(externalCopiesRemoved)}
								>
									{state.pendingAction === "erase" ? <Spinner /> : null}
									{eraseLabel}
								</Button>
							</div>
						) : null}
						{state.request.state === "ERASING" || state.request.state === "FAILED" ? (
							<Button
								variant="outline"
								disabled={state.pendingAction !== undefined}
								onClick={state.onRefresh}
							>
								Refresh job status
							</Button>
						) : null}
						{state.request.state === "COMPLETE" ? (
							<p>
								The audit receipt keeps store counts and the acting administrator. It holds no
								erased content or subject identities.
							</p>
						) : null}
					</CardContent>
				</Card>
			) : null}
		</div>
	);
}
