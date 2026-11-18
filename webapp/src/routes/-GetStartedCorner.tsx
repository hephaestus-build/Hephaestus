import { useQuery } from "@tanstack/react-query";
import { Link, useLocation } from "@tanstack/react-router";
import { useEffect } from "react";

import {
	getMemberOnboardingOptions,
	getOwnPracticeProfileOverviewOptions,
	getPublicActivityOnboardingOptions,
} from "@/api/@tanstack/react-query.gen";
import { queryLoadState } from "@/components/common/panel-state";
import { reviewFromOverview } from "@/components/onboarding/get-started-open";
import {
	type GetStartedOpening,
	useGetStartedRecord,
	writeGetStartedRecord,
} from "@/components/onboarding/get-started-record";
import { getStartedState } from "@/components/onboarding/get-started-rows";
import { GetStarted, getStartedDone } from "@/components/onboarding/GetStarted";
import { firstRunContent } from "@/components/practice-profile/ProfileFirstRun";
import type { TourChapterId } from "@/components/tour/tour-chapters";
import { useTourProgress } from "@/components/tour/tour-progress";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { toast } from "@/lib/toast";
import { useWorkProvider } from "@/lib/work-provider-context";

import { firstRunLinks } from "./_authenticated/w/$workspaceSlug/-first-run-links";

export interface GetStartedCornerContainerProps {
	accountId: string;
	workspaceSlug: string;
	open: boolean;
	opening: GetStartedOpening;
	onOpenChange: (open: boolean, opening?: GetStartedOpening) => void;
	/** Whether the pill shows, so the page keeps room for it at its end. */
	onShownChange: (shown: boolean) => void;
}

/**
 * Where focus goes when the pill leaves: the page's main region, which a skip link also targets. Only
 * from the corner itself, or from nowhere: a reader who moved focus to the page keeps it there.
 */
function focusPage() {
	const active = document.activeElement;
	if (
		active === null ||
		active === document.body ||
		active.closest("[data-get-started-corner]") !== null
	) {
		document.getElementById("main-content")?.focus({ preventScroll: true });
	}
}

/**
 * The reader's Get started list in the bottom-right corner, from their member onboarding read, the
 * Practice profile's overview where practices are on, and the tour chapters this account finished in
 * this browser. The pill shows while a setup step is open, and from its first showing on until every
 * step is done and the reader closes the card, or until they hide it.
 */
export default function GetStartedCornerContainer({
	accountId,
	workspaceSlug,
	open,
	opening,
	onOpenChange,
	onShownChange,
}: GetStartedCornerContainerProps) {
	const record = useGetStartedRecord(accountId, workspaceSlug);
	const workspace = useActiveWorkspaceSlug().workspaces.find(
		(each) => each.workspaceSlug === workspaceSlug,
	);
	const practicesEnabled = workspace?.practicesEnabled === true;
	const workspaceName = workspace?.displayName ?? workspaceSlug;
	// From the reader's own membership: an admin read here would ask an admin for a passkey step-up.
	const { isAdmin } = useWorkspaceAccess();
	const provider = useWorkProvider();
	const { progress } = useTourProgress(accountId);
	const pathname = useLocation({ select: (location) => location.pathname });
	// A link that opened a level, a card or a tour chapter came to show that: the card waits for a
	// page that the reader did not open for something.
	const arrivedFor = useLocation({
		select: ({ search }) =>
			search.detail !== undefined || search.feedbackCard !== undefined || search.tour !== undefined,
	});

	// The workspace layout holds this read already.
	const onboarding = useQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	// No poll of its own: the Practice profile polls the same read, and the list follows it there.
	const overview = useQuery({
		...getOwnPracticeProfileOverviewOptions({ path: { workspaceSlug } }),
		enabled: practicesEnabled,
	});
	// The workspace layout asks a member of a workspace that publishes its activity whether to appear
	// there; this is the same read. The card's first open waits until that question is answered.
	const publicActivityAsked = workspace?.publishesPublicActivity === true;
	const publicActivity = useQuery({
		...getPublicActivityOnboardingOptions({ path: { slug: workspaceSlug } }),
		enabled: publicActivityAsked,
	});

	const readiness = overview.data?.reviewReadiness ?? null;
	const closeCard = () => onOpenChange(false);
	const state = getStartedState({
		workspaceName,
		onboarding: { state: queryLoadState(onboarding), data: onboarding.data },
		review: practicesEnabled
			? {
					state: queryLoadState(overview),
					facts: overview.data === undefined ? undefined : reviewFromOverview(overview.data),
					content:
						readiness === null
							? undefined
							: firstRunContent(
									{
										workspaceName,
										readiness,
										activeReview: overview.data?.activeReview ?? undefined,
										reviewed: false,
										...firstRunLinks({
											workspaceSlug,
											readOnly: false,
											isAdmin,
											returnTo: pathname,
										}),
									},
									provider,
								),
				}
			: undefined,
		finished: progress.done,
		links: {
			setup: (
				<Link
					to="/w/$workspaceSlug/onboarding"
					params={{ workspaceSlug }}
					search={{ returnTo: pathname }}
				/>
			),
			// The tour lives on the Practice profile, which opens the chapter from its address.
			startChapter: (chapter: TourChapterId) => (
				<Link
					to="/w/$workspaceSlug/practice-profile"
					params={{ workspaceSlug }}
					search={{ tour: chapter }}
					resetScroll={false}
					// On the Practice profile itself, the chapter takes the page's place in history, so Back
					// after the tour leaves the page rather than landing on it again.
					replace={pathname === `/w/${workspaceSlug}/practice-profile`}
					onClick={closeCard}
				/>
			),
			conversation: <Link to="/w/$workspaceSlug/conversation" params={{ workspaceSlug }} />,
		},
	});
	const done = getStartedDone(state);
	const setupOpen = state.status === "ready" && !done;
	const shown = record === "open" || (record === undefined && setupOpen);

	// The first time the list shows for this account and workspace, the card opens once by itself.
	// Not on the setup page, not over the question about public activity, which comes first, and not
	// for a reader who came to act on something.
	const firstVisit =
		record === undefined &&
		setupOpen &&
		!arrivedFor &&
		pathname !== `/w/${workspaceSlug}/onboarding` &&
		(!publicActivityAsked || publicActivity.data?.seen === true);
	useEffect(() => {
		if (firstVisit) {
			writeGetStartedRecord(accountId, workspaceSlug, "open");
			onOpenChange(true, "self");
		}
	}, [firstVisit, accountId, workspaceSlug, onOpenChange]);

	useEffect(() => {
		onShownChange(shown);
	}, [shown, onShownChange]);
	useEffect(() => () => onShownChange(false), [onShownChange]);

	if (!shown) {
		return null;
	}
	return (
		<GetStarted
			state={state}
			open={open}
			opening={opening}
			onOpenChange={(next, dismissed) => {
				// A reader who opens the card before it ever opened by itself has seen it, so it does not open
				// by itself on a later page, and it stays in the corner when the last setup step completes.
				if (next && record === undefined) {
					writeGetStartedRecord(accountId, workspaceSlug, "open");
				}
				// Every step is done and the reader closed the card: the list leaves the corner for good.
				if (!next && dismissed && done) {
					focusPage();
					writeGetStartedRecord(accountId, workspaceSlug, "finished");
				}
				onOpenChange(next);
			}}
			onHide={() => {
				focusPage();
				closeCard();
				writeGetStartedRecord(accountId, workspaceSlug, "hidden");
				toast("Get started is hidden. Get started in your account menu shows it again.", {
					action: {
						label: "Undo",
						onClick: () => writeGetStartedRecord(accountId, workspaceSlug, "open"),
					},
				});
			}}
		/>
	);
}
