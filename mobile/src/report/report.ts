/**
 * Reporting what Hephaestus wrote — a reply from Heph, or a piece of practice feedback — to the people
 * who run this Hephaestus, through its product feedback. Only the reported text and the reader's reason
 * are sent, never the conversation around it.
 */

export type ReportSubject = "heph-reply" | "practice-feedback";

export interface PendingReport {
	subject: ReportSubject;
	/** The exact text being reported, as it was shown. */
	text: string;
}

/** The product feedback message field's limit. */
export const REPORT_LIMIT = 5000;

const SUBJECT = {
	"heph-reply": "a reply from Heph",
	"practice-feedback": "a piece of practice feedback",
} as const;

/** Where in the app the report was made, as the product feedback's page path. */
export const REPORT_PAGE: Record<ReportSubject, string> = {
	"heph-reply": "/mobile/heph",
	"practice-feedback": "/mobile/feedback",
};

function quoted(text: string): string {
	return text
		.split("\n")
		.map((line) => `> ${line}`)
		.join("\n");
}

/**
 * The message as it is sent: what is reported, the reason, and the reported text quoted. The reason is
 * kept whole; the quoted text is shortened to fit, saying so.
 */
export function reportMessage(report: PendingReport, reason: string): string {
	const header = `Reported in the mobile app: ${SUBJECT[report.subject]}.`;
	const why = reason.trim() === "" ? "No reason given." : `Reason: ${reason.trim()}`;
	const full = `${header}\n\n${why}\n\nReported text:\n${quoted(report.text)}`;
	if (full.length <= REPORT_LIMIT) {
		return full;
	}
	const marker = "\n> […shortened to fit]";
	const room = REPORT_LIMIT - `${header}\n\n${why}\n\nReported text:\n`.length - marker.length;
	const kept = quoted(report.text).slice(0, Math.max(0, room));
	return `${header}\n\n${why}\n\nReported text:\n${kept}${marker}`.slice(0, REPORT_LIMIT);
}

let pending: PendingReport | undefined;

/** Hands the report screen what to report, in memory: never a route parameter or a deep link. */
export function setPendingReport(report: PendingReport): void {
	pending = report;
}

/** What to report. The report screen reads it as it opens, then clears it, so nothing lingers. */
export function peekPendingReport(): PendingReport | undefined {
	return pending;
}

/** Forgets the report: the screen opened with it, or the session it came from has ended or changed. */
export function clearPendingReport(): void {
	pending = undefined;
}
