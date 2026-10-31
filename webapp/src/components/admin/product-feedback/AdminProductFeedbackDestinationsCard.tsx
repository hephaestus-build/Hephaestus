import { GitBranch } from "lucide-react";
import { useId, useState } from "react";

import type {
	LlmModel,
	ProductFeedbackDestinations,
	UpdateProductFeedbackDestinations,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Button } from "@/components/ui/button";
import {
	Card,
	CardContent,
	CardDescription,
	CardFooter,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
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
import { Switch } from "@/components/ui/switch";

import { REPOSITORY_CHECK_DEFS } from "./repository-check-defs";

/** An instance model that can draft issues, as the admin models page lists it. */
export type DraftingModel = Pick<LlmModel, "id" | "displayName" | "connectionDisplayName">;

export type AdminProductFeedbackDestinationsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			destinations: ProductFeedbackDestinations;
			models: DraftingModel[];
			saving: boolean;
			onSave: (update: UpdateProductFeedbackDestinations) => void;
	  };

export interface AdminProductFeedbackDestinationsCardProps {
	state: AdminProductFeedbackDestinationsState;
}

/** The server's `UpdateProductFeedbackDestinationsDTO` rules. */
const REPOSITORY_PATTERN = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u;
const DAILY_LIMIT_MAX = 1000;
const NO_MODEL = "none";

/**
 * Where the instance files product feedback, and whether it is working. The form remounts on every
 * saved answer, so it never shows a value the server did not keep.
 */
export function AdminProductFeedbackDestinationsCard({
	state,
}: AdminProductFeedbackDestinationsCardProps) {
	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<GitBranch className="size-4 text-muted-foreground" aria-hidden />
					Destinations
				</CardTitle>
				<CardDescription>
					With a repository set, Hephaestus drafts an issue from each report and files it on GitHub.
					With none, reports go to the admins of this instance by email.
				</CardDescription>
			</CardHeader>
			{state.status === "loading" && (
				<CardContent className="flex flex-col gap-3">
					<Skeleton className="h-8 w-full" />
					<Skeleton className="h-8 w-full" />
					<Skeleton className="h-8 w-2/3" />
				</CardContent>
			)}
			{state.status === "error" && (
				<CardContent>
					<QueryErrorAlert
						error={state.error}
						title="We could not load the product feedback destinations"
						onRetry={state.onRetry}
					/>
				</CardContent>
			)}
			{state.status === "ready" && (
				<DestinationsForm key={savedKey(state.destinations)} {...state} />
			)}
		</Card>
	);
}

function savedKey(saved: ProductFeedbackDestinations): string {
	return [
		saved.publicRepository,
		saved.privateRepository,
		saved.llmModelId,
		saved.dailyDraftLimit,
		saved.paused,
		saved.noticeVersion,
	].join("|");
}

function DestinationsForm({
	destinations: saved,
	models,
	saving,
	onSave,
}: Extract<AdminProductFeedbackDestinationsState, { status: "ready" }>) {
	const id = useId();
	const [publicRepository, setPublicRepository] = useState(saved.publicRepository ?? "");
	const [privateRepository, setPrivateRepository] = useState(saved.privateRepository ?? "");
	const [model, setModel] = useState(
		saved.llmModelId === undefined ? NO_MODEL : String(saved.llmModelId),
	);
	const [limit, setLimit] = useState(String(saved.dailyDraftLimit));
	const [paused, setPaused] = useState(saved.paused);
	const [newNoticeVersion, setNewNoticeVersion] = useState(false);

	const publicInvalid = !isRepository(publicRepository);
	const privateInvalid = !isRepository(privateRepository);
	const limitValue = Number(limit);
	const limitInvalid =
		limit.trim() === "" ||
		!Number.isInteger(limitValue) ||
		limitValue < 0 ||
		limitValue > DAILY_LIMIT_MAX;
	const modelItems = [
		{ value: NO_MODEL, label: "No model" },
		...models.map((option) => ({
			value: String(option.id),
			label: `${option.displayName} (${option.connectionDisplayName})`,
		})),
	];
	return (
		<form
			aria-label="Product feedback destinations"
			aria-busy={saving}
			onSubmit={(event) => {
				event.preventDefault();
				if (publicInvalid || privateInvalid || limitInvalid || saving) {
					return;
				}
				onSave({
					publicRepository: optional(publicRepository),
					privateRepository: optional(privateRepository),
					llmModelId: model === NO_MODEL ? undefined : Number(model),
					dailyDraftLimit: limitValue,
					paused,
					newNoticeVersion,
				});
			}}
		>
			<CardContent className="flex flex-col gap-6">
				<FieldGroup>
					<RepositoryField
						id={`${id}-public`}
						label="Public repository"
						description="Reports that people allow to be public become issues here."
						value={publicRepository}
						saved={saved.publicRepository}
						check={saved.publicCheck}
						invalid={publicInvalid}
						disabled={saving}
						onChange={setPublicRepository}
					/>
					<RepositoryField
						id={`${id}-private`}
						label="Private repository"
						description="Reports that stay private become issues here."
						value={privateRepository}
						saved={saved.privateRepository}
						check={saved.privateCheck}
						invalid={privateInvalid}
						disabled={saving}
						onChange={setPrivateRepository}
					/>
					<Field orientation="responsive">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-model`}>Model</FieldLabel>
							<FieldDescription>
								Drafts each issue. Without a model, every report stays private and is filed as it
								was written, with the details Hephaestus finds removed.
							</FieldDescription>
						</FieldContent>
						<Select
							items={modelItems}
							value={model}
							disabled={saving}
							onValueChange={(value) => setModel(value ?? NO_MODEL)}
						>
							<SelectTrigger id={`${id}-model`} className="w-full @md/field-group:w-56">
								<SelectValue placeholder="Select a model" />
							</SelectTrigger>
							<SelectContent aria-label="Model">
								{modelItems.map((item) => (
									<SelectItem key={item.value} value={item.value}>
										{item.label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</Field>
					<Field orientation="responsive" data-invalid={limitInvalid || undefined}>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-limit`}>Daily draft limit</FieldLabel>
							<FieldDescription id={`${id}-limit-detail`}>
								Drafts today: {saved.draftsToday} of {saved.dailyDraftLimit}. Over the limit,
								reports stay private and are filed without a draft.
							</FieldDescription>
							{limitInvalid && <FieldError>Enter a whole number from 0 to 1000.</FieldError>}
						</FieldContent>
						<Input
							id={`${id}-limit`}
							type="number"
							inputMode="numeric"
							min={0}
							max={DAILY_LIMIT_MAX}
							step={1}
							value={limit}
							disabled={saving}
							aria-invalid={limitInvalid || undefined}
							aria-describedby={`${id}-limit-detail`}
							className="w-full @md/field-group:w-56"
							onChange={(event) => setLimit(event.target.value)}
						/>
					</Field>
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-paused`}>Pause</FieldLabel>
							<FieldDescription>
								While paused, new and unfiled reports go to the admins of this instance by email,
								not to GitHub.
							</FieldDescription>
						</FieldContent>
						<Switch
							id={`${id}-paused`}
							checked={paused}
							readOnly={saving}
							onCheckedChange={setPaused}
						/>
					</Field>
					<Field orientation="horizontal">
						<Checkbox
							id={`${id}-notice`}
							checked={newNoticeVersion}
							disabled={saving}
							onCheckedChange={setNewNoticeVersion}
						/>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-notice`}>Ask everyone to choose again</FieldLabel>
							<FieldDescription>
								Raises the notice version from {saved.noticeVersion} to {saved.noticeVersion + 1}.
								Use this when your privacy notice names a new recipient or purpose.
							</FieldDescription>
						</FieldContent>
					</Field>
				</FieldGroup>
				<DestinationsHealth saved={saved} />
			</CardContent>
			<CardFooter className="justify-end">
				<Button type="submit" disabled={publicInvalid || privateInvalid || limitInvalid || saving}>
					{saving && <Spinner />}
					{saving ? "Saving…" : "Save destinations"}
				</Button>
			</CardFooter>
		</form>
	);
}

interface RepositoryFieldProps {
	id: string;
	label: string;
	description: string;
	value: string;
	saved: string | undefined;
	check: ProductFeedbackDestinations["publicCheck"];
	invalid: boolean;
	disabled: boolean;
	onChange: (value: string) => void;
}

function RepositoryField({
	id,
	label,
	description,
	value,
	saved,
	check,
	invalid,
	disabled,
	onChange,
}: RepositoryFieldProps) {
	// The check describes the saved repository; a changed field is checked after the save.
	const changed = value.trim() !== (saved ?? "");
	const def = REPOSITORY_CHECK_DEFS[check];
	return (
		<Field orientation="responsive" data-invalid={invalid || undefined}>
			<FieldContent>
				<FieldLabel htmlFor={id}>{label}</FieldLabel>
				<FieldDescription id={`${id}-detail`}>{description}</FieldDescription>
				{changed ? (
					<FieldDescription>Hephaestus checks it after you save.</FieldDescription>
				) : (
					saved !== undefined && (
						<div className="flex flex-col gap-1">
							<StatusBadge def={def} wrap className="self-start" />
							{check !== "INSTALLED" && <FieldDescription>{def.description}</FieldDescription>}
						</div>
					)
				)}
				{invalid && <FieldError>Enter the repository as owner/name.</FieldError>}
			</FieldContent>
			<Input
				id={id}
				value={value}
				placeholder="owner/name"
				spellCheck={false}
				autoComplete="off"
				disabled={disabled}
				aria-invalid={invalid || undefined}
				aria-describedby={`${id}-detail`}
				className="w-full @md/field-group:w-56"
				onChange={(event) => onChange(event.target.value)}
			/>
		</Field>
	);
}

function DestinationsHealth({ saved }: { saved: ProductFeedbackDestinations }) {
	return (
		<section aria-label="Health" className="flex flex-col gap-2">
			<h3 className="text-sm font-medium">Health</h3>
			<dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
				<div>
					<dt className="text-muted-foreground">Drafts today</dt>
					<dd className="tabular-nums">
						{saved.draftsToday} of {saved.dailyDraftLimit}
					</dd>
				</div>
				<div>
					<dt className="text-muted-foreground">Last filing</dt>
					<dd>
						<RelativeTime value={saved.lastFiledAt} fallback="None yet" />
					</dd>
				</div>
				<div>
					<dt className="text-muted-foreground">Oldest waiting</dt>
					<dd>
						<RelativeTime value={saved.oldestWaitingSince} fallback="Nothing waits" />
					</dd>
				</div>
				<div>
					<dt className="text-muted-foreground">Figures from</dt>
					<dd>
						<RelativeTime value={saved.snapshotAt} fallback="Not counted yet" />
					</dd>
				</div>
			</dl>
		</section>
	);
}

function isRepository(value: string): boolean {
	const trimmed = value.trim();
	return trimmed === "" || REPOSITORY_PATTERN.test(trimmed);
}

function optional(value: string): string | undefined {
	const trimmed = value.trim();
	return trimmed === "" ? undefined : trimmed;
}
