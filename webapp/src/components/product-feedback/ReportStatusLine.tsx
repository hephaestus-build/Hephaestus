import type { MyProductFeedback } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { RelativeTime } from "@/components/common/RelativeTime";
import { hasText } from "@/lib/text";

import { closedAs } from "./sharing-copy";

export interface ReportStatusLineProps {
	report: Pick<
		MyProductFeedback,
		"createdAt" | "state" | "route" | "issueUrl" | "issueNumber" | "issueClosedAs"
	>;
}

/**
 * What happened to a report, in the order it happened: sent, filed, closed. It names only what the
 * server records, so a step without a time carries none. A filed issue that the reader may open is
 * a link.
 */
export function ReportStatusLine({ report }: ReportStatusLineProps) {
	const filed = report.state === "FILED";
	return (
		<p className="text-sm text-muted-foreground">
			Sent <RelativeTime value={report.createdAt} />
			{filed && (
				<>
					{" · Filed as "}
					{hasText(report.issueUrl) ? (
						<InlineLink href={report.issueUrl} external className="underline">
							{issueName(report)}
						</InlineLink>
					) : (
						issueName(report)
					)}
				</>
			)}
			{filed && hasText(report.issueClosedAs) && ` · Closed as ${closedAs(report.issueClosedAs)}`}
		</p>
	);
}

function issueName(report: ReportStatusLineProps["report"]): string {
	const visibility = report.route === "PUBLIC" ? "public" : "private";
	return report.issueNumber === undefined
		? `a ${visibility} issue`
		: `${visibility} issue #${report.issueNumber}`;
}
