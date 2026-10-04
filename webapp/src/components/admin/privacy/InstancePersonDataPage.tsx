import { ChevronDownIcon, UserSearchIcon } from "lucide-react";
import { useState } from "react";

import type { PersonDataProvider, PersonDataRequest } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Field,
	FieldDescription,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
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
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

import { InstancePersonDataEraseDialog } from "./InstancePersonDataEraseDialog";

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
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			request: PersonDataRequest;
			pendingAction?: "export" | "erase";
			/** A rejected export or erasure; the preview stays on screen. */
			actionError?: unknown;
			onExport: () => void;
			onErase: (externalCopiesRemoved: boolean) => void;
	  };

export interface InstancePersonDataPageProps {
	providers: PersonDataProvider[];
	selection: PersonSelectionInput;
	onChange: (selection: PersonSelectionInput) => void;
	onPreview: () => void;
	state: InstancePersonDataPageState;
}

const MAX_IDENTITIES = 32;

const REQUEST_STATE_TITLES = {
	PREVIEW: "Preview",
	ERASING: "Erasure in progress",
	FAILED: "Erasure stopped",
	COMPLETE: "Erasure complete",
	EXPIRED: "Preview expired",
} satisfies Record<PersonDataRequest["state"], string>;

function providerLabel(provider: PersonDataProvider): string {
	return `${provider.type} · ${provider.serverUrl}`;
}

function isWebAddress(locator: string): boolean {
	return /^https?:\/\//u.test(locator);
}

export function InstancePersonDataPage({
	providers,
	selection,
	onChange,
	onPreview,
	state,
}: InstancePersonDataPageProps) {
	const busy =
		state.status === "loading" ||
		(state.status === "ready" &&
			(state.request.state === "ERASING" || state.pendingAction !== undefined));
	const providerItems = providers.map((provider) => ({
		value: String(provider.id),
		label: providerLabel(provider),
	}));
	const updateIdentity = (index: number, patch: Partial<PersonIdentityInput>) =>
		onChange({
			...selection,
			identities: selection.identities.map((identity, position) =>
				position === index ? { ...identity, ...patch } : identity,
			),
		});
	const hasSubject =
		selection.accountId !== "" || selection.identities.some((identity) => identity.subject !== "");

	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<CardTitle>Find a person</CardTitle>
					<CardDescription>
						Verify the requester through your privacy procedure first. Hephaestus matches only exact
						account IDs and provider user IDs, never names, logins or email addresses.
					</CardDescription>
				</CardHeader>
				<CardContent>
					<form
						onSubmit={(event) => {
							event.preventDefault();
							onPreview();
						}}
					>
						<FieldGroup>
							<Field>
								<FieldLabel htmlFor="person-account">Account ID</FieldLabel>
								<Input
									id="person-account"
									inputMode="numeric"
									pattern="[1-9][0-9]*"
									autoComplete="off"
									value={selection.accountId}
									disabled={busy}
									aria-describedby="person-account-description"
									onChange={(event) => onChange({ ...selection, accountId: event.target.value })}
								/>
								<FieldDescription id="person-account-description">
									Optional when you add a provider identity.
								</FieldDescription>
							</Field>
							{selection.identities.map((identity, index) => {
								const isSlack =
									providers.find((provider) => String(provider.id) === identity.providerId)
										?.type === "SLACK";
								return (
									<FieldSet key={identity.inputId} disabled={busy}>
										<FieldLegend variant="label">Provider identity {index + 1}</FieldLegend>
										<Field>
											<FieldLabel htmlFor={`person-provider-${identity.inputId}`}>
												Provider instance
											</FieldLabel>
											<Select
												items={providerItems}
												value={identity.providerId === "" ? null : identity.providerId}
												required
												disabled={busy}
												onValueChange={(value) =>
													updateIdentity(index, { providerId: value ?? "", teamId: "" })
												}
											>
												<SelectTrigger
													id={`person-provider-${identity.inputId}`}
													className="w-full"
												>
													<SelectValue placeholder="Select a provider instance" />
												</SelectTrigger>
												<SelectContent aria-label={`Provider instance for identity ${index + 1}`}>
													{providerItems.map((item) => (
														<SelectItem key={item.value} value={item.value}>
															{item.label}
														</SelectItem>
													))}
												</SelectContent>
											</Select>
										</Field>
										<Field>
											<FieldLabel htmlFor={`person-subject-${identity.inputId}`}>
												Provider user ID
											</FieldLabel>
											<Input
												id={`person-subject-${identity.inputId}`}
												value={identity.subject}
												required
												autoComplete="off"
												maxLength={255}
												onChange={(event) => updateIdentity(index, { subject: event.target.value })}
											/>
										</Field>
										{isSlack && (
											<Field>
												<FieldLabel htmlFor={`person-team-${identity.inputId}`}>
													Slack workspace ID
												</FieldLabel>
												<Input
													id={`person-team-${identity.inputId}`}
													value={identity.teamId}
													required
													autoComplete="off"
													maxLength={255}
													onChange={(event) =>
														updateIdentity(index, { teamId: event.target.value })
													}
												/>
											</Field>
										)}
										<Button
											type="button"
											variant="outline"
											className="w-fit"
											onClick={() =>
												onChange({
													...selection,
													identities: selection.identities.filter(
														(_, position) => position !== index,
													),
												})
											}
										>
											Remove identity {index + 1}
										</Button>
									</FieldSet>
								);
							})}
							<div className="flex flex-wrap gap-2">
								<Button
									type="button"
									variant="outline"
									disabled={busy || selection.identities.length >= MAX_IDENTITIES}
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
								<Button type="submit" disabled={busy || !hasSubject}>
									Preview data
								</Button>
							</div>
						</FieldGroup>
					</form>
				</CardContent>
			</Card>
			{state.status === "empty" && (
				<EmptyState
					icon={<UserSearchIcon />}
					title="No person selected"
					headingLevel={2}
					description="Enter an account ID or a provider identity, then preview what Hephaestus holds about them."
				/>
			)}
			{state.status === "loading" && (
				<div aria-busy="true">
					<Skeleton className="h-64 w-full" />
					<span className="sr-only">Loading preview</span>
				</div>
			)}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not complete the request"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<RequestCard key={state.request.id} state={state} providers={providers} />
			)}
		</div>
	);
}

function RequestCard({
	state,
	providers,
}: {
	state: Extract<InstancePersonDataPageState, { status: "ready" }>;
	providers: PersonDataProvider[];
}) {
	const [eraseOpen, setEraseOpen] = useState(false);
	const { request, pendingAction } = state;
	const processed = request.state !== "PREVIEW" && request.state !== "EXPIRED";
	const stores = Object.entries(request.counts).sort(([a], [b]) => a.localeCompare(b));
	const holds = ([store, count]: [string, number]) =>
		count > 0 || (request.completed[store] ?? 0) > 0;
	const heldStores = stores.filter(holds);
	const emptyStores = stores.filter((entry) => !holds(entry)).map(([store]) => store);
	const rowCount = stores.reduce((sum, [, count]) => sum + count, 0);
	const canErase = request.state === "PREVIEW" || request.state === "FAILED";
	const providerById = new Map(providers.map((provider) => [provider.id, provider]));

	return (
		<Card>
			<CardHeader>
				<CardTitle>{REQUEST_STATE_TITLES[request.state]}</CardTitle>
				<CardDescription>
					{rowCount} {rowCount === 1 ? "row" : "rows"} in {heldStores.length} of {stores.length}{" "}
					{stores.length === 1 ? "store" : "stores"}.{" "}
					{request.state === "PREVIEW" && (
						<>
							Export and erasure use exactly these rows. If the data changes, preview again. This
							preview expires <RelativeTime value={request.expiresAt} />.
						</>
					)}
					{request.state === "ERASING" && "This page refreshes until the job finishes."}
					{request.state === "FAILED" &&
						`Stores already erased stay erased. Resume to finish the rest (failure code ${request.failureCode ?? "unknown"}).`}
					{request.state === "COMPLETE" &&
						"The audit record keeps store counts and the acting administrator, not erased content or identities. Provider records that still exist upstream can be mirrored again, but Hephaestus does not process them."}
					{request.state === "EXPIRED" && "Preview again to export or erase."}
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-6">
				{state.actionError !== undefined && (
					<QueryErrorAlert error={state.actionError} title="The request was not accepted" />
				)}
				{request.scope && (
					<section aria-labelledby={`${request.id}-scope`} className="space-y-2">
						<h3 id={`${request.id}-scope`} className="text-sm font-medium">
							Identities
						</h3>
						<ul className="space-y-1 text-sm">
							{request.scope.accountId !== undefined && (
								<li>
									Account <code>{request.scope.accountId}</code>
								</li>
							)}
							{request.scope.identities.map((identity) => {
								const provider = providerById.get(identity.providerId);
								return (
									<li key={`${identity.providerId}:${identity.subject}:${identity.teamId ?? ""}`}>
										{provider ? providerLabel(provider) : `Provider ${identity.providerId}`}: user{" "}
										<code>{identity.subject}</code>
										{identity.teamId !== undefined && (
											<>
												{" "}
												in Slack workspace <code>{identity.teamId}</code>
											</>
										)}
									</li>
								);
							})}
						</ul>
					</section>
				)}
				{canErase && request.externalDeliveries.length > 0 && (
					<Alert variant="warning">
						<AlertTitle>Feedback is still posted on providers</AlertTitle>
						<AlertDescription>
							<p>
								Erasure does not remove provider comments. Remove them first. The runbook section on
								un-delivering external feedback explains how.
							</p>
							<ul className="mt-2 space-y-1">
								{request.externalDeliveries.map((delivery) => (
									<li key={`${delivery.workspaceId}:${delivery.locator}`} className="break-all">
										Workspace {delivery.workspaceId}:{" "}
										{isWebAddress(delivery.locator) ? (
											<InlineLink href={delivery.locator} external>
												{delivery.locator}
											</InlineLink>
										) : (
											<code>{delivery.locator}</code>
										)}
									</li>
								))}
							</ul>
						</AlertDescription>
					</Alert>
				)}
				<Table>
					<TableHeader>
						<TableRow>
							<TableHead>Store</TableHead>
							<TableHead className="text-right">Rows</TableHead>
							{processed && <TableHead className="text-right">Erased</TableHead>}
						</TableRow>
					</TableHeader>
					<TableBody>
						{heldStores.map(([store, count]) => (
							<TableRow key={store}>
								<TableCell>
									<code>{store}</code>
								</TableCell>
								<TableCell className="text-right tabular-nums">{count}</TableCell>
								{processed && (
									<TableCell className="text-right tabular-nums">
										{request.completed[store] ?? "—"}
									</TableCell>
								)}
							</TableRow>
						))}
					</TableBody>
				</Table>
				{emptyStores.length > 0 && (
					<Collapsible>
						<CollapsibleTrigger render={<Button variant="ghost" size="sm" />}>
							<ChevronDownIcon aria-hidden />
							{emptyStores.length === 1 ? "1 store holds" : `${emptyStores.length} stores hold`} no
							rows
						</CollapsibleTrigger>
						<CollapsibleContent>
							<ul className="mt-2 columns-1 text-sm text-muted-foreground sm:columns-2 lg:columns-3">
								{emptyStores.map((store) => (
									<li key={store}>
										<code>{store}</code>
									</li>
								))}
							</ul>
						</CollapsibleContent>
					</Collapsible>
				)}
				{canErase && (
					<div className="flex flex-wrap gap-2">
						{request.state === "PREVIEW" && (
							<Button
								variant="outline"
								disabled={pendingAction !== undefined}
								onClick={state.onExport}
							>
								{pendingAction === "export" && <Spinner aria-hidden />}
								{pendingAction === "export" ? "Downloading…" : "Download JSON export"}
							</Button>
						)}
						<Button
							variant="destructive"
							disabled={pendingAction !== undefined}
							onClick={() => setEraseOpen(true)}
						>
							{pendingAction === "erase" && <Spinner aria-hidden />}
							{pendingAction === "erase" && "Starting erasure…"}
							{pendingAction !== "erase" &&
								(request.state === "FAILED" ? "Resume erasure…" : "Erase data…")}
						</Button>
					</div>
				)}
				<InstancePersonDataEraseDialog
					open={eraseOpen}
					onOpenChange={setEraseOpen}
					resume={request.state === "FAILED"}
					rowCount={rowCount}
					storeCount={heldStores.length}
					externalDeliveryCount={request.externalDeliveries.length}
					onConfirm={state.onErase}
				/>
			</CardContent>
		</Card>
	);
}
