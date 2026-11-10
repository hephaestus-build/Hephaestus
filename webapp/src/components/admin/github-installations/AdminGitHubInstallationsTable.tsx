import { Link } from "@tanstack/react-router";
import { Check, ExternalLink, X } from "lucide-react";

import type { GitHubInstallationRequest } from "@/api/types.gen";
import { QueryErrorAlert, type QueryErrorAlertProps } from "@/components/common/QueryErrorAlert";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { GitHubIcon } from "@/components/icons/brand";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { asDate, formatDate } from "@/lib/dates";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export interface AdminGitHubInstallationsTableProps {
	installations: GitHubInstallationRequest[];
	isLoading: boolean;
	/** The list could not be read: a refusal, or a first read that failed. No installation is listed. */
	loadFailure?: Pick<QueryErrorAlertProps, "error" | "onRetry" | "pending">;
	/** Reading the list again failed; the installations last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	/** Installations with a decision on its way to the server. Their actions wait for it. */
	deciding: ReadonlySet<number>;
	onAdopt: (installation: GitHubInstallationRequest) => void;
	onDecline: (installation: GitHubInstallationRequest) => void;
}

type InstallationStatus = GitHubInstallationRequest["status"];

/** The one home of each status in words, keyed on the wire's enum so a new status fails the build here. */
const STATUS_LABELS: Record<InstallationStatus, string> = {
	PENDING: "Waiting for approval",
	DECLINED: "Declined",
	ADOPTED: "Adopted",
};

const STATUS_VARIANTS: Record<InstallationStatus, "outline" | "secondary" | "destructive"> = {
	PENDING: "outline",
	DECLINED: "destructive",
	ADOPTED: "secondary",
};

const ACCOUNT_KIND_LABELS: Record<GitHubInstallationRequest["accountKind"], string> = {
	USER: "Personal account",
	ORGANIZATION: "Organization",
};

function formatDay(value: Date | undefined): string {
	const date = asDate(value);
	return date ? formatDate(date) : "–";
}

const SKELETON_COLUMNS = ["w-32", "w-24", "w-24", "w-20", "w-20", null];

function InstallationsTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead scope="col">GitHub account</TableHead>
				<TableHead scope="col">Account type</TableHead>
				<TableHead scope="col">Status</TableHead>
				<TableHead scope="col">Installed</TableHead>
				<TableHead scope="col">Decided</TableHead>
				<TableHead scope="col" className="text-right">
					<span className="sr-only">Actions</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

interface InstallationActionsProps {
	installation: GitHubInstallationRequest;
	deciding: boolean;
	onAdopt: () => void;
	onDecline: () => void;
}

function InstallationActions({
	installation,
	deciding,
	onAdopt,
	onDecline,
}: InstallationActionsProps) {
	const login = installation.accountLogin;
	if (installation.status === "ADOPTED") {
		const slug = installation.workspaceSlug;
		return slug === undefined ? (
			<span className="text-sm text-muted-foreground">Workspace deleted</span>
		) : (
			<Link
				to="/w/$workspaceSlug"
				params={{ workspaceSlug: slug }}
				aria-label={`Open workspace for ${login}`}
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				<ExternalLink aria-hidden />
				Open workspace
			</Link>
		);
	}
	return (
		<div className="flex justify-end gap-2">
			<Button
				size="sm"
				disabled={deciding}
				aria-label={`Adopt the installation on ${login}`}
				onClick={onAdopt}
			>
				<Check aria-hidden />
				Adopt
			</Button>
			{installation.status === "PENDING" && (
				<Button
					variant="outline"
					size="sm"
					disabled={deciding}
					aria-label={`Decline the installation on ${login}`}
					onClick={onDecline}
				>
					<X aria-hidden />
					Decline
				</Button>
			)}
		</div>
	);
}

/**
 * GitHub App installations that an instance admin decides on. Adopting one creates its workspace, with the
 * admin as its owner. A declined installation stays listed, so it can still be adopted.
 */
export function AdminGitHubInstallationsTable({
	installations,
	isLoading,
	loadFailure,
	refreshFailure,
	deciding,
	onAdopt,
	onDecline,
}: AdminGitHubInstallationsTableProps) {
	if (loadFailure !== undefined) {
		return <QueryErrorAlert title="We could not load GitHub installations" {...loadFailure} />;
	}
	if (isLoading) {
		return (
			<Table bordered>
				<InstallationsTableHeader />
				<TableRowsSkeleton columns={SKELETON_COLUMNS} />
			</Table>
		);
	}
	const refreshAlert = refreshFailure !== undefined && (
		<QueryErrorAlert title="These installations may be out of date" {...refreshFailure} />
	);
	if (installations.length === 0) {
		return (
			<>
				{refreshAlert}
				<div className="flex flex-col items-center gap-2 py-12 text-center text-muted-foreground">
					<GitHubIcon className="size-8" aria-hidden />
					<p className="text-sm">No GitHub App installations yet</p>
				</div>
			</>
		);
	}

	return (
		<>
			{refreshAlert}
			<Table bordered>
				<InstallationsTableHeader />
				<TableBody>
					{installations.map((installation) => (
						<TableRow key={installation.installationId}>
							<TableCell className="font-medium">{installation.accountLogin}</TableCell>
							<TableCell className="text-muted-foreground">
								{ACCOUNT_KIND_LABELS[installation.accountKind]}
							</TableCell>
							<TableCell>
								<Badge variant={STATUS_VARIANTS[installation.status]}>
									{STATUS_LABELS[installation.status]}
								</Badge>
							</TableCell>
							<TableCell className="text-sm whitespace-nowrap text-muted-foreground">
								{formatDay(installation.receivedAt)}
							</TableCell>
							<TableCell className="text-sm whitespace-nowrap text-muted-foreground">
								{formatDay(installation.decidedAt)}
							</TableCell>
							<TableCell className="text-right">
								<InstallationActions
									installation={installation}
									deciding={deciding.has(installation.installationId)}
									onAdopt={() => onAdopt(installation)}
									onDecline={() => onDecline(installation)}
								/>
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</>
	);
}
