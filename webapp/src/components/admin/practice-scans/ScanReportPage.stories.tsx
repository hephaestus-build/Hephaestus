import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ScanReport, ScanSummary } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { StatefulPatch } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";

import {
	CANCELLED_REPORT,
	CANCELLED_SCAN,
	COMPLETE_REPORT,
	COMPLETE_SCAN,
	EMPTY_COUNTS,
	FAILED_REPORT,
	FAILED_SCAN,
	FIRST_BASELINE_REPORT,
	ITEM_ENTRIES,
	JUDGED_ENTRY,
	METHOD_CHANGED_REPORT,
	PARTIAL_REPORT,
	PARTIAL_SCAN,
	PROBLEM_ENTRIES,
	QUEUED_SCAN,
	RUNNING_REPORT,
	RUNNING_SCAN,
	scanDetail,
	scanReport,
	scanSummary,
	SUSPECT_LISTING_REPORT,
	WITHHELD_ENTRY,
	WITHHELD_NO_PROBLEMS_REPORT,
} from "./fixtures";
import { ScanReportPage } from "./ScanReportPage";

const list = <T,>(entries: readonly T[]) => ({
	status: "ready" as const,
	entries,
	hasNext: false,
	loadingMore: false,
	onLoadMore: fn(),
});

const loaded = (scan: ScanSummary, report: ScanReport) => ({
	status: "ready" as const,
	scan: scanDetail(scan),
	report,
});

const meta = {
	component: ScanReportPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	args: {
		workspaceSlug: "aet",
		state: loaded(COMPLETE_SCAN, COMPLETE_REPORT),
		tab: "problems",
		onTabChange: fn(),
		problems: list(PROBLEM_ENTRIES),
		problemFilter: undefined,
		onProblemFilterChange: fn(),
		files: list(ITEM_ENTRIES),
		fileFilter: undefined,
		onFileFilterChange: fn(),
		cancel: { pending: false, onRun: fn() },
		exportSarif: { pending: false, onRun: fn() },
	},
	argTypes: {
		state: { control: false },
		problems: { control: false },
		files: { control: false },
		cancel: { control: false },
		exportSarif: { control: false },
	},
	render: (args) => (
		<StatefulPatch initial={{ tab: args.tab }}>
			{(value, patch) => (
				<ScanReportPage
					{...args}
					tab={value.tab}
					onTabChange={(tab) => {
						patch({ tab });
						args.onTabChange(tab);
					}}
				/>
			)}
		</StatefulPatch>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ScanReportPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Complete: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("heading", { name: "ls1intum/hephaestus" })).toBeVisible();
		await expect(canvas.getByText("Started by Ada Lovelace")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Cancel scan" })).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Export SARIF" }));
		await expect(args.exportSarif.onRun).toHaveBeenCalledOnce();

		await userEvent.click(canvas.getByRole("tab", { name: "Practices" }));
		await expect(args.onTabChange).toHaveBeenCalledWith("practices");
		await expect(canvas.getByRole("table", { name: "Practices in this scan" })).toBeVisible();
	},
};

export const Empty: Story = {
	args: {
		state: loaded(
			{ ...COMPLETE_SCAN, counts: { ...EMPTY_COUNTS, files: 0, practiceItemPairs: 0 } },
			{
				...COMPLETE_REPORT,
				counts: { ...EMPTY_COUNTS, files: 0, practiceItemPairs: 0 },
				recordedCounts: { ...EMPTY_COUNTS, files: 0, practiceItemPairs: 0 },
				practices: [],
				withheldReasons: [],
				notReviewedReasons: [],
			},
		),
		problems: list([]),
		files: list([]),
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "This scan recorded no problems" }),
		).toBeVisible();
		await expect(canvas.queryByText("Not counted")).not.toBeInTheDocument();
	},
};

export const Queued: Story = {
	args: {
		state: loaded(QUEUED_SCAN, scanReport(QUEUED_SCAN)),
		problems: list([]),
		files: list([]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Cancel scan" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Export SARIF" })).not.toBeInTheDocument();
	},
};

export const Running: Story = {
	args: { state: loaded(RUNNING_SCAN, RUNNING_REPORT) },
	play: async ({ canvas, userEvent, args }) => {
		await expect(
			canvas.getByText(
				"2 practice and file pairs finished so far. The total is known once every file is listed. 2 running, 0 waiting.",
			),
		).toBeVisible();
		await expect(canvas.queryByText(/%/u)).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Cancel scan" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expectSettledVisible(
			dialog.getByText(
				"The scan stops and cannot be resumed. Its report keeps the observations already recorded.",
			),
		);
		await expect(args.cancel.onRun).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Keep scan running" }));
		await waitFor(async () => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
		await expect(args.cancel.onRun).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Cancel scan" }));
		await userEvent.click(
			within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel scan" }),
		);
		await expect(args.cancel.onRun).toHaveBeenCalledOnce();
	},
};

export const Cancelling: Story = {
	args: {
		state: loaded(RUNNING_SCAN, RUNNING_REPORT),
		cancel: { pending: true, onRun: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Cancelling scan…" })).toBeDisabled();
	},
};

export const CancelFailed: Story = {
	args: {
		state: loaded(RUNNING_SCAN, RUNNING_REPORT),
		cancel: {
			pending: false,
			error: { status: 500, detail: "The server had a problem." },
			onRun: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not cancel the scan")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Cancel scan" })).toBeEnabled();
	},
};

export const Partial: Story = {
	args: { state: loaded(PARTIAL_SCAN, PARTIAL_REPORT) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"These counts cover only the work it reached. The scan’s budget was used up.",
			),
		).toBeVisible();
		await expect(canvas.getByText("Not comparable")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Export SARIF" })).toBeEnabled();
	},
};

const metPairCounts = { ...EMPTY_COUNTS, files: 1, practiceItemPairs: 1, reviewed: 1, met: 1 };
const withheldPairCounts = { ...EMPTY_COUNTS, files: 1, practiceItemPairs: 1, withheld: 1 };
const partialWithheldScan = scanSummary({
	...PARTIAL_SCAN,
	stopReason: "RESULT_WITHHELD",
	baselineScanId: null,
	counts: { ...EMPTY_COUNTS, files: 1, practiceItemPairs: 2, reviewed: 1, met: 1, withheld: 1 },
});
const partialWithheldReport = scanReport(partialWithheldScan, {
	comparison: "FIRST_SCAN",
	problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
	withheldReasons: [{ reason: "MODEL_UNAVAILABLE", description: "model unavailable", count: 1 }],
	notReviewedReasons: [],
	removalGuard: { state: "NOT_NEEDED", previousFiles: 0, wouldRemove: 0 },
});
partialWithheldReport.practices = partialWithheldReport.practices.map((practice) => ({
	...practice,
	counts: practice.practiceSlug === "error-handling" ? metPairCounts : withheldPairCounts,
	comparison: "FIRST_SCAN",
	listingComplete: true,
	status: "DONE",
	reason: null,
}));

/**
 * A partial scan that reviewed every file and withheld some results, with no problem recorded. The
 * note and the Problems tab name what its results cover; neither says the scan stopped.
 */
export const PartialWithheldNoProblems: Story = {
	args: {
		state: loaded(partialWithheldScan, partialWithheldReport),
		problems: list([]),
		files: list([
			{
				...JUDGED_ENTRY,
				sourceReviewId: partialWithheldScan.id,
				outcome: "MET",
				severity: null,
				summary: null,
				problemCount: 0,
			},
			{
				...WITHHELD_ENTRY,
				item: JUDGED_ENTRY.item,
				itemVersion: JUDGED_ENTRY.itemVersion,
				practiceSlug: "comment-quality",
				practiceName: "Comments explain why",
			},
		]),
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"These counts cover only the work it reached. Its candidate observations were withheld.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("heading", { name: "No problems recorded in the work it reached" }),
		).toBeVisible();
		await expect(canvas.queryByText("This scan recorded no problems")).not.toBeInTheDocument();
	},
};

export const Cancelled: Story = {
	args: { state: loaded(CANCELLED_SCAN, CANCELLED_REPORT), problems: list([]), files: list([]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Started by Scheduled")).toBeVisible();
		await expect(canvas.getByText(/A workspace admin cancelled it\./u)).toBeVisible();
	},
};

/**
 * A first scan that failed while listing the repository. Every statement on the page must hold for
 * a scan that reviewed nothing: one error explains it, and no tab or figure reads as a clean result.
 */
export const Failed: Story = {
	args: { state: loaded(FAILED_SCAN, FAILED_REPORT), problems: list([]), files: list([]) },
	play: async ({ canvas, userEvent }) => {
		const alerts = canvas.getAllByRole("alert");
		await expect(alerts).toHaveLength(1);
		await expect(alerts[0]).toHaveTextContent(
			"Scan failedThe scan stopped before it reviewed any file. Listing the repository’s files failed. Nothing here says the code is clean. Start a new scan to try again.",
		);
		await expect(canvas.queryByText("Not comparable")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Listing incomplete")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/Not known yet/u)).not.toBeInTheDocument();
		await expect(
			canvas.getByRole("heading", { name: "No problems recorded in the work it reached" }),
		).toBeVisible();
		await expect(canvas.queryByText("This scan recorded no problems")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("tab", { name: "Files" }));
		await expect(canvas.getByRole("heading", { name: "No files were listed" })).toBeVisible();
		await userEvent.click(canvas.getByRole("tab", { name: "Practices" }));
		await expect(
			canvas.getByRole("heading", { name: "This scan reached no practice" }),
		).toBeVisible();
	},
};

export const FirstBaseline: Story = {
	args: {
		state: loaded(COMPLETE_SCAN, FIRST_BASELINE_REPORT),
		problems: list(PROBLEM_ENTRIES.slice(0, 1)),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("First scan")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "New (3)" })).toBeVisible();
	},
};

export const MethodChanged: Story = {
	args: {
		state: loaded(COMPLETE_SCAN, METHOD_CHANGED_REPORT),
		problems: list(PROBLEM_ENTRIES.slice(0, 1).map((entry) => ({ ...entry, baselineState: null }))),
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"The review method changed. This scan cannot show which problems were fixed.",
			),
		).toBeVisible();
		await expect(canvas.queryByText("Now met")).not.toBeInTheDocument();
	},
};

export const SuspectListing: Story = {
	args: { state: loaded(COMPLETE_SCAN, SUSPECT_LISTING_REPORT) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Listing not trusted")).toBeVisible();
		await expect(canvas.getByText(/12 of 40 earlier files were missing\./u)).toBeVisible();
	},
};

export const WithheldNoProblems: Story = {
	args: {
		state: loaded(WITHHELD_NO_PROBLEMS_REPORT.scan, WITHHELD_NO_PROBLEMS_REPORT),
		problems: list([]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Why pairs were withheld" })).toHaveTextContent(
			"2 · The assigned AI model was unavailable.",
		);
		await expect(canvas.queryByText(/SOMETHING_NEW/u)).not.toBeInTheDocument();
		await expect(
			canvas.getByText(
				"That does not mean every practice is met: 4 practice and file pairs have no observation in this scan.",
			),
		).toBeVisible();
	},
};

export const ExcludedFiles: Story = {
	args: {
		state: loaded(
			COMPLETE_REPORT.scan,
			scanReport(COMPLETE_REPORT.scan, {
				exclusions: [
					{ itemId: "901", path: "assets/video.mp4", reason: "BINARY_FILE", sizeBytes: 4_200_000 },
					{ itemId: "902", path: "assets/logo.png", reason: "BINARY_FILE", sizeBytes: 18_000 },
					{ itemId: "903", path: "data/dump.json", reason: "FILE_TOO_LARGE", sizeBytes: 3_100_000 },
				],
			}),
		),
	},
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Files not eligible for review" });
		await expect(region).toHaveTextContent("1 · Larger than the 2 MiB a review can read");
		await expect(region).toHaveTextContent("2 · Binary files, so no practice reviews them.");
	},
};

export const Expired: Story = {
	args: {
		state: loaded(COMPLETE_SCAN, {
			...COMPLETE_REPORT,
			availability: { ...COMPLETE_REPORT.availability, expired: true, expiredProofPairs: 4 },
		}),
		problems: { status: "error", error: { status: 410 }, onRetry: fn() },
		files: { status: "error", error: { status: 410 }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Details no longer kept")).toBeVisible();
		await expect(
			canvas.getByText(
				"This scan’s file and problem lists and its SARIF export are past their retention. The counts stay.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("region", { name: "Counts" })).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Export SARIF" }));
	},
};

export const Exporting: Story = {
	args: { exportSarif: { pending: true, onRun: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Exporting SARIF…" })).toBeDisabled();
	},
};

export const ExportFailed: Story = {
	args: {
		exportSarif: {
			pending: false,
			error: { status: 410, detail: "This scan’s details are past their retention." },
			onRun: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not export the SARIF file")).toBeVisible();
		await expect(canvas.getByText("This scan’s details are past their retention.")).toBeVisible();
		await expect(canvas.getByRole("region", { name: "Counts" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Export SARIF" })).toBeEnabled();
	},
};

export const FilesTab: Story = {
	args: { tab: "files" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Files and practices" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading scan report")).toHaveClass("sr-only");
		await expect(canvas.queryByRole("heading")).not.toBeInTheDocument();
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByText("We could not load this scan")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status === "error") {
			await expect(args.state.onRetry).toHaveBeenCalledOnce();
		}
	},
};

export const NotFound: Story = {
	args: { state: { status: "error", error: { status: 404 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not find this scan")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Scans" })).toBeVisible();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const RefreshFailed: Story = {
	args: {
		state: {
			...loaded(COMPLETE_SCAN, COMPLETE_REPORT),
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "ls1intum/hephaestus" })).toBeVisible();
		await expect(canvas.getByRole("table", { name: "Problems in this scan" })).toBeVisible();
		await expect(canvas.getByText("Scan details may be out of date")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};
