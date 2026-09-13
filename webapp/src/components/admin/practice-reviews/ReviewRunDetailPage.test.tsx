import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { renderWithRouter } from "@/test/router-harness";

import { ReviewRunDetailPage } from "./ReviewRunDetailPage";
import { reviewFeedback, reviewJob } from "./story-mock-data";

const RUN_ID = "11111111-1111-1111-1111-111111111111";

describe("review result processing", () => {
	it("does not claim publication when results are processed but feedback awaits approval", async () => {
		await renderWithRouter(
			<ReviewRunDetailPage
				workspaceSlug="demo"
				jobId={RUN_ID}
				search={{}}
				job={{ ...reviewJob(RUN_ID), deliveryStatus: "DELIVERED" }}
				isLoading={false}
				error={null}
				onRetry={vi.fn()}
				observations={{ status: "ready", items: [], total: 0 }}
				feedback={{
					status: "ready",
					total: 1,
					items: reviewFeedback
						.filter((item) => item.agentJobId === RUN_ID)
						.slice(0, 1)
						.map((item) => ({ ...item, deliveryState: "AWAITING_APPROVAL" })),
				}}
				onCancel={vi.fn()}
				cancelPending={false}
				onRetryDelivery={vi.fn()}
				retryDeliveryPending={false}
			/>,
			"/",
		);
		await screen.findByText("Results processed");
		await screen.findByText("Awaiting approval");
		expect(screen.queryByText("Summary posted")).toBeNull();
		expect(screen.queryByText("Delivered")).toBeNull();
	});
});
