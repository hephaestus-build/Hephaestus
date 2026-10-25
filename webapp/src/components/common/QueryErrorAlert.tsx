import { AlertCircleIcon, InfoIcon, LockIcon, SearchXIcon } from "lucide-react";
import type * as React from "react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import {
	PROBLEM_COPY,
	type ProblemCopy,
	type ProblemKind,
	problemDetailOf,
	problemIsRetryable,
	problemKindOf,
	problemStatusOf,
} from "@/lib/problem-detail";

export interface QueryErrorAlertProps {
	error: unknown;
	title: string;
	onRetry?: () => void;
	pending?: boolean;
	className?: string;
}

interface ErrorLook {
	icon: React.ReactNode;
	variant: "destructive" | "warning";
	/** The status already says everything, so the server's wording is not shown. */
	ignoreDetail?: boolean;
}

const LOOK = {
	"no-answer": { icon: <AlertCircleIcon />, variant: "destructive" },
	"signed-out": { icon: <LockIcon />, variant: "warning", ignoreDetail: true },
	"no-access": { icon: <LockIcon />, variant: "destructive" },
	"not-found": { icon: <SearchXIcon />, variant: "destructive" },
	conflict: { icon: <InfoIcon />, variant: "warning" },
	"rate-limited": { icon: <InfoIcon />, variant: "warning" },
	server: { icon: <AlertCircleIcon />, variant: "destructive" },
	rejected: { icon: <AlertCircleIcon />, variant: "destructive" },
} satisfies Record<ProblemKind, ErrorLook>;

function classifyError(status: number | undefined): ErrorLook & ProblemCopy {
	const kind = problemKindOf(status);
	return { ...LOOK[kind], ...PROBLEM_COPY[kind] };
}

/** Whether the error guidance offers a repeat of the failed read: the client retries by the same answer. */
export { problemIsRetryable as queryErrorIsRetryable } from "@/lib/problem-detail";

const LOAD_TITLE = /^We could not load (?<what>.+)$/u;

/**
 * The server's generic not-found detail (`EntityNotFoundException`). The title already says "We
 * could not find X" and the 404 line says what to do, so repeating it would say the same thrice.
 */
const GENERIC_NOT_FOUND = /^We could not find that\b.*\bIt may have been deleted\b/u;

/**
 * A detail that names a Java type (`de.tum.….ReviewJobInput`) is the server's diagnostics, not
 * wording it chose for the reader, who can do nothing with it. The status's own cause stands in.
 */
const INTERNAL_DETAIL = /\b(?:[a-z][\da-z_]*\.){2,}[A-Z][\w$]*/u;

function describe(lead: string, next: string): string {
	if (lead.length === 0 || lead === next) {
		return next;
	}
	// A lead that already says what to do must not be followed by a second instruction.
	if (/\btry again\b/iu.test(lead)) {
		return lead;
	}
	return /[.!?]$/u.test(lead) ? `${lead} ${next}` : `${lead}. ${next}`;
}

export function QueryErrorAlert({
	error,
	title,
	onRetry,
	pending = false,
	className,
}: QueryErrorAlertProps) {
	const status = problemStatusOf(error);
	const { icon, cause, next, variant, retitle, ignoreDetail } = classifyError(status);
	const raw = ignoreDetail === true ? "" : problemDetailOf(error, "").trim();
	const detail =
		(status === 404 && GENERIC_NOT_FOUND.test(raw)) || INTERNAL_DETAIL.test(raw) ? "" : raw;
	const showRetry = onRetry != null && problemIsRetryable(error);
	const what = LOAD_TITLE.exec(title)?.groups?.what;
	const shownTitle = what != null && retitle != null ? retitle(what) : title;

	return (
		<Alert variant={variant} className={className}>
			{icon}
			<AlertTitle className="min-w-0 break-words">{shownTitle}</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				{describe(detail || (cause ?? ""), next)}
			</AlertDescription>
			{showRetry && (
				<AlertAction>
					<Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
						{pending && <Spinner />}
						{pending ? "Retrying…" : "Retry"}
					</Button>
				</AlertAction>
			)}
		</Alert>
	);
}
