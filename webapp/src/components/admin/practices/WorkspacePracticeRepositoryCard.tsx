import { Link } from "@tanstack/react-router";
import { CircleAlert, FolderGit2, Info, ShieldAlert } from "lucide-react";
import { type ComponentProps, type ReactNode, type SubmitEvent, useId, useState } from "react";

import type { PracticeRepository, SetPracticeRepositoryRequest } from "@/api/types.gen";
import { DocLink } from "@/components/common/DocLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	Field,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Item, ItemActions, ItemContent, ItemMedia, ItemTitle } from "@/components/ui/item";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { DEFAULT_PRACTICE_DIRECTORY, isPracticeDirectory } from "@/lib/practice-repository";
import { hasText } from "@/lib/text";

/** The card's `id` on Practice setup, which links from a practice's panel scroll to. */
export const PRACTICE_REPOSITORY_SECTION_ID = "practice-repository";

/** A link to the practice repository card on Practice setup. It closes any open level. */
export function PracticeRepositoryLink({
	workspaceSlug,
	children,
}: {
	workspaceSlug: string;
	children: ReactNode;
}) {
	return (
		<Link
			to="/w/$workspaceSlug/admin/practices"
			params={{ workspaceSlug }}
			search={{}}
			hash={PRACTICE_REPOSITORY_SECTION_ID}
			className="font-medium underline underline-offset-4 hover:text-foreground"
		>
			{children}
		</Link>
	);
}

/** What the practice repository is, as far as the console knows it. */
export type PracticeRepositoryCardState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty" }
	| { status: "set"; repository: PracticeRepository };

/** The repositories the workspace monitors, which are the ones it can choose from. */
export type MonitoredRepositoryOptions =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; options: { itemId: string; name: string }[] };

export type PracticeRepositoryAction = "set" | "change" | "unset";

/** A write that the reader asked for and has not cancelled. */
export type PracticeRepositoryRequest = { action: PracticeRepositoryAction } & (
	| { phase: "confirming" }
	| { phase: "pending" }
	| {
			phase: "refused";
			reason: string;
			/** What the server said about the directory, shown at that field. */
			directoryError?: string;
	  }
);

/** Setting, changing and unsetting the practice repository, as the route runs them. */
export interface PracticeRepositorySettingControl {
	/** The open request. Absent while no dialog is open. */
	request?: PracticeRepositoryRequest;
	/** Read only while the reader chooses a repository. */
	repositories: MonitoredRepositoryOptions;
	onOpen: (action: PracticeRepositoryAction) => void;
	/** Asks to close the dialog. Ignored while the request runs. */
	onCancel: () => void;
	onSave: (request: SetPracticeRepositoryRequest) => void;
	onUnset: () => void;
}

export interface WorkspacePracticeRepositoryCardProps {
	workspaceSlug: string;
	state: PracticeRepositoryCardState;
	setting: PracticeRepositorySettingControl;
}

function repositoryName(repository: PracticeRepository) {
	return repository.nameWithOwner ?? "a repository that the workspace no longer monitors";
}

/**
 * The practice repository of a workspace (ADR 0118): one monitored repository whose default branch
 * holds the folders of the practices edited there. Unset, it is one quiet row, so a workspace that
 * never uses it sees little of it. Each write asks first in a dialog, which stays open while the
 * request runs and keeps a refusal in view. Change and Unset say that every practice edited in the
 * repository becomes editable in Hephaestus again, because the server does that.
 */
export function WorkspacePracticeRepositoryCard({
	workspaceSlug,
	state,
	setting,
}: WorkspacePracticeRepositoryCardProps) {
	const titleId = useId();
	const setId = useId();
	const changeId = useId();
	const unsetId = useId();
	const ids: Record<PracticeRepositoryAction, string> = {
		set: setId,
		change: changeId,
		unset: unsetId,
	};
	// The last action the reader opened. Its control gets focus back, unless the write replaced it:
	// then the card's next action does, such as Change after a repository was set. The form dialog
	// also keeps asking what it asked while it fades out after the request has gone.
	const [opened, setOpened] = useState<PracticeRepositoryAction>("set");
	const finalFocus = () =>
		document.getElementById(ids[opened]) ??
		document.getElementById(ids.change) ??
		document.getElementById(ids.set) ??
		true;
	const open = (action: PracticeRepositoryAction) => () => {
		setOpened(action);
		setting.onOpen(action);
	};

	if (state.status === "error") {
		return (
			<section id={PRACTICE_REPOSITORY_SECTION_ID} aria-label="Practice repository">
				<QueryErrorAlert
					error={state.error}
					title="We could not load the practice repository"
					onRetry={state.onRetry}
				/>
			</section>
		);
	}

	const current = state.status === "set" ? state.repository : undefined;
	const { request } = setting;

	return (
		<section id={PRACTICE_REPOSITORY_SECTION_ID} aria-labelledby={titleId}>
			<Item variant="outline" size={current === undefined ? "sm" : "default"}>
				<ItemMedia variant="icon">
					<FolderGit2 aria-hidden />
				</ItemMedia>
				<ItemContent>
					<ItemTitle>
						<h2 id={titleId}>Practice repository</h2>
						{state.status === "empty" && (
							<span className="font-normal text-muted-foreground">Not set</span>
						)}
					</ItemTitle>
					{state.status === "loading" && <Skeleton className="h-4 w-48 max-w-full" />}
					{current !== undefined && <PracticeRepositoryFacts repository={current} />}
				</ItemContent>
				{state.status === "empty" && (
					<ItemActions className="flex-wrap">
						<DocLink doc="practiceRepository">Keep practices in a repository</DocLink>
						<Button id={ids.set} size="sm" variant="outline" onClick={open("set")}>
							Set practice repository
						</Button>
					</ItemActions>
				)}
				{current !== undefined && (
					<ItemActions>
						<Button id={ids.change} size="sm" variant="outline" onClick={open("change")}>
							Change
						</Button>
						<Button id={ids.unset} size="sm" variant="outline" onClick={open("unset")}>
							Unset
						</Button>
					</ItemActions>
				)}
			</Item>

			<PracticeRepositoryFormDialog
				workspaceSlug={workspaceSlug}
				action={(request?.action ?? opened) === "change" ? "change" : "set"}
				current={current}
				request={request?.action === "unset" ? undefined : request}
				setting={setting}
				finalFocus={finalFocus}
			/>
			<UnsetPracticeRepositoryDialog
				current={current}
				request={request?.action === "unset" ? request : undefined}
				setting={setting}
				finalFocus={finalFocus}
			/>
		</section>
	);
}

function PracticeRepositoryFacts({ repository }: { repository: PracticeRepository }) {
	const branch = repository.defaultBranch;
	return (
		<>
			<dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 pt-1 text-sm">
				<dt className="text-muted-foreground">Repository</dt>
				<dd className="min-w-0 break-words">{repositoryName(repository)}</dd>
				<dt className="text-muted-foreground">Default branch</dt>
				<dd className="min-w-0 break-words">
					{hasText(branch) ? <code>{branch}</code> : "Not known yet"}
				</dd>
				<dt className="text-muted-foreground">Directory</dt>
				<dd className="min-w-0 break-all">
					<code>{repository.directory}</code>
				</dd>
			</dl>
			<p className="flex gap-2 pt-2 text-sm text-muted-foreground">
				<ShieldAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
				<span>
					Anyone who can merge into {hasText(branch) ? <code>{branch}</code> : "its default branch"}{" "}
					changes what reviews judge. Protect that branch: require a review before a merge.
				</span>
			</p>
		</>
	);
}

type DialogFinalFocus = ComponentProps<typeof DialogContent>["finalFocus"];

interface PracticeRepositoryDialogProps<TRequest> {
	current: PracticeRepository | undefined;
	request: TRequest | undefined;
	setting: PracticeRepositorySettingControl;
	finalFocus: DialogFinalFocus;
}

function PracticeRepositoryFormDialog({
	workspaceSlug,
	action,
	current,
	request,
	setting,
	finalFocus,
}: PracticeRepositoryDialogProps<PracticeRepositoryRequest> & {
	workspaceSlug: string;
	action: "set" | "change";
}) {
	return (
		<Dialog
			open={request !== undefined}
			onOpenChange={(next) => {
				if (!next) {
					setting.onCancel();
				}
			}}
		>
			<DialogContent finalFocus={finalFocus} showCloseButton={request?.phase !== "pending"}>
				{/* The popup unmounts once closed, so each opening starts from the saved setting. */}
				<PracticeRepositoryForm
					workspaceSlug={workspaceSlug}
					action={action}
					current={current}
					request={request}
					setting={setting}
				/>
			</DialogContent>
		</Dialog>
	);
}

const DIRECTORY_RULE =
	"Enter a path from the repository root, such as .hephaestus/practices, with no slash at either end. Use letters, digits, dots, hyphens and underscores, and no ., .. or .git part.";

function PracticeRepositoryForm({
	workspaceSlug,
	action,
	current,
	request,
	setting,
}: {
	workspaceSlug: string;
	action: "set" | "change";
	current: PracticeRepository | undefined;
	/** Absent while the dialog closes. */
	request: PracticeRepositoryRequest | undefined;
	setting: PracticeRepositorySettingControl;
}) {
	const repositoryId = useId();
	const repositoryErrorId = useId();
	const directoryId = useId();
	const directoryHintId = useId();
	const directoryErrorId = useId();
	const unchangedId = useId();
	const [chosen, setChosen] = useState(current?.repositoryItemId ?? "");
	const [directory, setDirectory] = useState(current?.directory ?? "");
	const [submitted, setSubmitted] = useState(false);
	// A directory the server refused stays marked until the reader edits it.
	const [editedSinceSubmit, setEditedSinceSubmit] = useState(false);
	const { repositories } = setting;
	const pending = request?.phase === "pending";
	const changing = action === "change" && current !== undefined;
	const options = repositories.status === "ready" ? repositories.options : [];
	const chosenOption = options.find((option) => option.itemId === chosen);
	const trimmed = directory.trim();
	const unchanged =
		changing &&
		chosen === current.repositoryItemId &&
		(trimmed === "" ? DEFAULT_PRACTICE_DIRECTORY : trimmed) === current.directory;
	const missingRepository =
		submitted &&
		chosenOption === undefined &&
		repositories.status === "ready" &&
		options.length > 0;
	const serverDirectoryError =
		request?.phase === "refused" && !editedSinceSubmit ? request.directoryError : undefined;
	const directoryError =
		submitted && trimmed !== "" && !isPracticeDirectory(trimmed)
			? DIRECTORY_RULE
			: serverDirectoryError;
	const title = changing ? "Change practice repository" : "Set practice repository";

	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		setSubmitted(true);
		setEditedSinceSubmit(false);
		if (
			chosenOption === undefined ||
			request === undefined ||
			pending ||
			unchanged ||
			(trimmed !== "" && !isPracticeDirectory(trimmed))
		) {
			return;
		}
		setting.onSave(
			trimmed === ""
				? { repositoryItemId: chosenOption.itemId }
				: { repositoryItemId: chosenOption.itemId, directory: trimmed },
		);
	};

	return (
		<DialogForm onSubmit={submit}>
			<DialogHeader>
				<DialogTitle>{title}</DialogTitle>
				<DialogDescription>
					Hephaestus reads the practices edited there from the head of its default branch.
				</DialogDescription>
			</DialogHeader>
			<DialogBody className="space-y-4 py-1">
				<FieldGroup>
					<Field data-invalid={missingRepository}>
						{repositories.status === "error" ? (
							// No control to label while the list cannot load.
							<FieldTitle>Repository</FieldTitle>
						) : (
							<FieldLabel htmlFor={repositoryId}>Repository</FieldLabel>
						)}
						{repositories.status === "error" ? (
							<QueryErrorAlert
								error={repositories.error}
								title="We could not load repositories"
								onRetry={repositories.onRetry}
							/>
						) : (
							<Select
								items={options.map((option) => ({ value: option.itemId, label: option.name }))}
								value={chosenOption === undefined ? null : chosenOption.itemId}
								disabled={pending || repositories.status !== "ready" || options.length === 0}
								onValueChange={(value) => setChosen(value ?? "")}
							>
								<SelectTrigger
									id={repositoryId}
									className="w-full"
									aria-invalid={missingRepository}
									aria-describedby={missingRepository ? repositoryErrorId : undefined}
								>
									<SelectValue
										placeholder={
											repositories.status === "loading"
												? "Loading repositories…"
												: "Choose a monitored repository"
										}
									/>
								</SelectTrigger>
								<SelectContent aria-label="Monitored repositories">
									{options.map((option) => (
										<SelectItem key={option.itemId} value={option.itemId}>
											{option.name}
										</SelectItem>
									))}
								</SelectContent>
							</Select>
						)}
						{repositories.status === "ready" && options.length === 0 && (
							<FieldDescription>
								This workspace monitors no repositories yet. Add one in{" "}
								<Link
									to="/w/$workspaceSlug/admin/integrations"
									params={{ workspaceSlug }}
									className="underline underline-offset-4 hover:text-foreground"
								>
									Integrations
								</Link>{" "}
								first.
							</FieldDescription>
						)}
						{missingRepository && (
							<FieldError id={repositoryErrorId}>Choose a repository.</FieldError>
						)}
					</Field>
					<Field data-invalid={directoryError !== undefined}>
						<FieldLabel htmlFor={directoryId}>Directory</FieldLabel>
						<Input
							id={directoryId}
							value={directory}
							placeholder={DEFAULT_PRACTICE_DIRECTORY}
							autoComplete="off"
							spellCheck={false}
							disabled={pending}
							aria-invalid={directoryError !== undefined}
							aria-describedby={
								directoryError === undefined
									? directoryHintId
									: `${directoryHintId} ${directoryErrorId}`
							}
							onChange={(event) => {
								setDirectory(event.target.value);
								setEditedSinceSubmit(true);
							}}
						/>
						<FieldDescription id={directoryHintId}>
							A path from the repository root. Each practice is a folder in it, named after its
							slug. Leave it empty for <code>{DEFAULT_PRACTICE_DIRECTORY}</code>.
						</FieldDescription>
						{directoryError !== undefined && (
							<FieldError id={directoryErrorId}>{directoryError}</FieldError>
						)}
					</Field>
				</FieldGroup>
				{changing && (
					<Alert role="note">
						<Info />
						<AlertTitle>Practices edited in {repositoryName(current)} move back</AlertTitle>
						<AlertDescription>
							Every practice edited there becomes editable in Hephaestus again and keeps its current
							version. Nothing is deleted.
						</AlertDescription>
					</Alert>
				)}
				<Alert variant="warning" role="note">
					<ShieldAlert />
					<AlertTitle>Protect its default branch</AlertTitle>
					<AlertDescription>
						Anyone who can merge into it changes what reviews judge, and the practice scripts that
						run in reviews. Require a review before a merge.
					</AlertDescription>
				</Alert>
				{/* A refusal about the directory is shown at that field instead. */}
				{request?.phase === "refused" && request.directoryError === undefined && (
					<Alert variant="destructive" role="alert">
						<CircleAlert />
						<AlertTitle>We could not save the practice repository</AlertTitle>
						<AlertDescription>{request.reason}</AlertDescription>
					</Alert>
				)}
			</DialogBody>
			<DialogFooter>
				{unchanged && (
					<p id={unchangedId} className="text-sm text-muted-foreground sm:mr-auto sm:self-center">
						Choose another repository or directory first.
					</p>
				)}
				<Button type="button" variant="outline" disabled={pending} onClick={setting.onCancel}>
					Cancel
				</Button>
				<Button
					type="submit"
					pending={pending}
					disabled={unchanged}
					// Focusable, so a keyboard reader reaches the button and hears why it is unavailable.
					focusableWhenDisabled={unchanged}
					aria-describedby={unchanged ? unchangedId : undefined}
				>
					{pending ? "Saving…" : title}
				</Button>
			</DialogFooter>
		</DialogForm>
	);
}

function UnsetPracticeRepositoryDialog({
	current,
	request,
	setting,
	finalFocus,
}: PracticeRepositoryDialogProps<PracticeRepositoryRequest>) {
	const pending = request?.phase === "pending";
	const name = current === undefined ? "the practice repository" : repositoryName(current);
	return (
		<AlertDialog
			open={request !== undefined}
			onOpenChange={(next) => {
				if (!next) {
					setting.onCancel();
				}
			}}
		>
			<AlertDialogContent finalFocus={finalFocus}>
				<AlertDialogHeader>
					<AlertDialogTitle>Unset the practice repository?</AlertDialogTitle>
					<AlertDialogDescription>
						Every practice edited in {name} becomes editable in Hephaestus again and keeps its
						current version. Later changes to its folders no longer reach the practices. Nothing is
						deleted.
					</AlertDialogDescription>
				</AlertDialogHeader>
				{request?.phase === "refused" && (
					<Alert variant="destructive" role="alert">
						<CircleAlert />
						<AlertTitle>We could not unset the practice repository</AlertTitle>
						<AlertDescription>{request.reason}</AlertDescription>
					</Alert>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel disabled={pending}>Cancel</AlertDialogCancel>
					<AlertDialogAction pending={pending} disabled={pending} onClick={setting.onUnset}>
						{pending ? "Unsetting…" : "Unset"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
