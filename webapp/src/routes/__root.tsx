import {
	notifyManager,
	type QueryCache,
	type QueryClient,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";
import {
	CatchBoundary,
	createRootRouteWithContext,
	redirect,
	HeadContent,
	Outlet,
	useLocation,
	useMatch,
	useMatches,
	useNavigate,
	useRouter,
} from "@tanstack/react-router";
import { cn } from "cn";
import { lazy, type ReactNode, Suspense, useState, useSyncExternalStore } from "react";

import {
	getIntegrationCatalogOptions,
	getMemberOnboardingOptions,
} from "@/api/@tanstack/react-query.gen";
import { UserViewBanner } from "@/components/admin/users/UserViewBanner";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { LoginDialog } from "@/components/auth/LoginDialog";
import { PageError } from "@/components/common/PageError";
import Footer from "@/components/layout/Footer";
import Header from "@/components/layout/Header";
import { RouteAnnouncer } from "@/components/layout/RouteAnnouncer";
import { AppSidebar, type SidebarContext } from "@/components/layout/sidebar/AppSidebar";
import { SkipToContent } from "@/components/layout/SkipToContent";
import { StandardPageSurface } from "@/components/layout/StandardPageSurface";
import type { FeedbackKind } from "@/components/product-feedback/feedback-copy";
import {
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
	USER_AGENT_MAX_LENGTH,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { ProductFeedbackMenu } from "@/components/product-feedback/ProductFeedbackMenu";
import { Kbd } from "@/components/ui/kbd";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { Toaster } from "@/components/ui/sonner";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import environment from "@/environment";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { useFeedbackAwaitingApproval } from "@/hooks/use-feedback-awaiting-approval";
import { useLoginNavigation } from "@/hooks/use-login-navigation";
import { useSubmitProductFeedback } from "@/hooks/use-product-feedback";
import { useSignInProviders } from "@/hooks/use-sign-in-providers";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspaceCreation } from "@/hooks/use-workspace-creation";
import { useWorkspaceSwitcher } from "@/hooks/use-workspace-switcher";
import { isConversationOutOfReach } from "@/lib/conversation-availability";
import { stepUpChallengeOf } from "@/lib/problem-detail";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { WorkProviderContext } from "@/lib/work-provider-context";
import { type AuthContextType, useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/return-to";
import { FeatureFlagDevTools } from "@/runtime/feature-flags/FeatureFlagDevTools";
import { useProviderColors } from "@/runtime/theme/provider-colors";
import { exitUserView } from "@/runtime/user-view/session";
import { workspaceAddressConfig, publicHref } from "@/runtime/workspace-address";
import { isConversationPanelExcludedRoute } from "./-conversation-panel-route";
import { showConversationUnavailable } from "./-conversation-unavailable";

const GlobalConversationPanel = lazy(async () => import("./-GlobalConversationPanel"));

interface MyRouterContext {
	queryClient: QueryClient;
	auth: AuthContextType | undefined;
}

declare module "@tanstack/react-router" {
	interface StaticDataRouteOption {
		surface?: "standard" | "bleed" | "fullscreen" | "auth";
	}
}

function RootLayout() {
	const { pathname } = useLocation();
	const surface = useMatches({
		select: (matches) => {
			for (let index = matches.length - 1; index >= 0; index -= 1) {
				const matchSurface = matches[index]?.staticData.surface;
				if (matchSurface) {
					return matchSurface;
				}
			}
			return "standard";
		},
	});
	const { isAuthenticated, isLoading, userView } = useAuth();
	const showConversationPanel =
		!userView && !isLoading && isAuthenticated && !isConversationPanelExcludedRoute(pathname);

	if (surface === "auth") {
		return (
			<>
				<HeadContent />
				<SkipToContent />
				<main id="main-content" tabIndex={-1}>
					<Outlet />
				</main>
				<Toaster />
			</>
		);
	}

	return (
		<>
			<HeadContent />
			<SkipToContent />
			<ProviderColors />
			<WorkProviderScope>
				<SidebarProvider>
					<AppSidebarContainer />
					<SidebarInset
						className={cn(
							"mr-[var(--right-sidebar-width,0)] min-w-0",
							// A full-screen page scrolls inside itself: its own scroller follows new messages and
							// brings a linked one into view, which it cannot do while the page grows past the window.
							surface === "fullscreen" && "h-svh",
						)}
					>
						<HeaderContainer />
						{userView && (
							<UserViewBanner
								name={userView.name}
								workspace={userView.workspaceName}
								unclaimed={userView.unclaimed}
								onExit={exitUserView}
							/>
						)}
						<main id="main-content" tabIndex={-1} className="flex min-h-0 flex-1 flex-col">
							{surface === "standard" ? (
								<StandardPageSurface className="flex-1">
									<Outlet />
								</StandardPageSurface>
							) : (
								<div
									className={
										surface === "fullscreen" ? "flex min-h-0 min-w-0 flex-1 flex-col" : "flex-1"
									}
								>
									<Outlet />
								</div>
							)}
						</main>
						{surface !== "fullscreen" && (
							<Footer
								buildInfo={environment.buildInfo}
								isProduction={environment.deployment.isProduction}
							/>
						)}
					</SidebarInset>
				</SidebarProvider>
			</WorkProviderScope>
			<Toaster />
			<PublicLoginOverlay />
			{userView && <UserViewConfirmAccess />}
			{showConversationPanel && (
				<CatchBoundary
					getResetKey={() => 0}
					onCatch={() => showConversationUnavailable({ again: false })}
					errorComponent={ConversationPanelUnavailable}
				>
					<Suspense fallback={null}>
						<GlobalConversationPanel />
					</Suspense>
				</CatchBoundary>
			)}
			{!userView && <FeatureFlagDevTools />}
		</>
	);
}

/**
 * The panel's code did not load, often a file replaced by a deploy. `React.lazy` keeps that failure,
 * so the toast from `onCatch` offers a page load, not a retry. A panel that fails after it loaded
 * has its own boundary, in `-GlobalConversationPanel.tsx`.
 */
function ConversationPanelUnavailable() {
	return null;
}

/** The announcer sits above the layout, whose auth and app branches each remount what they hold. */
function RootRoute() {
	return (
		<>
			<RouteAnnouncer />
			<RootLayout />
		</>
	);
}

/**
 * The page a report is sent from, as its route: `/w/$workspaceSlug/practices`, never `/w/acme/practices`.
 * The route names the screen without the workspace, ids or search that the address carries.
 */
function routeOf(fullPath: string | undefined): string {
	if (fullPath === undefined || fullPath === "/") {
		return "/";
	}
	return fullPath.endsWith("/") ? fullPath.slice(0, -1) : fullPath;
}

function ProductFeedbackControls({ workspaceSlug }: { workspaceSlug?: string }) {
	const route = useMatches({ select: (matches) => routeOf(matches.at(-1)?.fullPath) });
	const feedback = useSubmitProductFeedback(workspaceSlug);
	const [feedbackOpen, setFeedbackOpen] = useState(false);
	const [feedbackKind, setFeedbackKind] = useState<FeedbackKind>("FEEDBACK");
	return (
		<>
			<ProductFeedbackMenu
				onSendFeedback={(kind) => {
					setFeedbackKind(kind);
					setFeedbackOpen(true);
				}}
			/>
			<ProductFeedbackDialog
				open={feedbackOpen}
				onOpenChange={(open) => {
					if (!open) {
						feedback.reset();
					}
					setFeedbackOpen(open);
				}}
				kind={feedbackKind}
				onKindChange={setFeedbackKind}
				context={{
					pagePath: route.slice(0, PAGE_PATH_MAX_LENGTH),
					userAgent: navigator.userAgent.slice(0, USER_AGENT_MAX_LENGTH),
				}}
				isSubmitting={feedback.isPending}
				error={feedback.error}
				onSubmit={feedback.submit}
			/>
		</>
	);
}

export const Route = createRootRouteWithContext<MyRouterContext>()({
	validateSearch: (search): { login?: boolean; chromeWorkspace?: string } => ({
		chromeWorkspace:
			typeof search.chromeWorkspace === "string" && search.chromeWorkspace.trim().length > 0
				? search.chromeWorkspace
				: undefined,
		login: search.login === true || search.login === "true" ? true : undefined,
	}),
	beforeLoad: ({ location }) => {
		if (!workspaceAddressConfig.enabled) {
			return;
		}
		const target = new URL(publicHref(location.href), window.location.origin);
		if (target.origin !== window.location.origin) {
			throw redirect({ href: target.href, reloadDocument: true });
		}
	},
	// Fallback tab title; the deepest match that sets its own `head` wins.
	head: () => ({ meta: [{ title: "Hephaestus" }] }),
	component: RootRoute,
	// An address no route matches is `routes/$.tsx`, which sets a title; this serves a thrown `notFound()`.
	notFoundComponent: NotFound,
});

function NotFound() {
	return <PageError cause="not-found" />;
}

function HeaderContainer() {
	const openLogin = useLoginNavigation();
	const {
		isAuthenticated,
		isLoading,
		session,
		retrySession,
		userProfile,
		userView,
		logout,
		getUserProfilePictureUrl,
		getUserId,
	} = useAuth();
	const { chromeWorkspaceSlug, selfLogin, userName: workspaceUserName } = useWorkspaceAccess();

	// The account menu signs the administrator out, so during a user view it names them.
	const effectiveUsername = userView ? userProfile?.username : selfLogin;
	const effectiveName = userView
		? userProfile?.name
		: (workspaceUserName ?? (userProfile && `${userProfile.firstName} ${userProfile.lastName}`));

	return (
		<Header
			sidebarTrigger={isAuthenticated && <SidebarToggle />}
			version={environment.version}
			environmentName={environment.deployment.name}
			pullRequest={environment.deployment.pullRequest}
			isProduction={environment.deployment.isProduction}
			session={session}
			name={effectiveName}
			username={effectiveUsername}
			avatarUrl={getUserProfilePictureUrl()}
			workspaceSlug={chromeWorkspaceSlug}
			readOnly={Boolean(userView)}
			feedbackDialog={
				!userView && !isLoading && isAuthenticated ? (
					<ProductFeedbackControls
						key={`${getUserId() ?? ""}:${chromeWorkspaceSlug ?? ""}`}
						workspaceSlug={chromeWorkspaceSlug}
					/>
				) : null
			}
			onLogin={openLogin}
			onRetrySession={retrySession}
			onLogout={() => {
				void logout();
			}}
		/>
	);
}

// The sidebar answers Cmd+B and Ctrl+B on every platform. The tooltip shows the key that the reader's
// keyboard has.
const SIDEBAR_SHORTCUT_LABEL = /Mac|iPhone|iPad|iPod/u.test(navigator.userAgent) ? "⌘B" : "Ctrl+B";

function SidebarToggle() {
	return (
		<Tooltip>
			<TooltipTrigger
				render={<SidebarTrigger className="-ml-1" aria-keyshortcuts="Control+B Meta+B" />}
			/>
			<TooltipContent side="bottom">
				<span className="flex items-center gap-2">
					Toggle sidebar <Kbd>{SIDEBAR_SHORTCUT_LABEL}</Kbd>
				</span>
			</TooltipContent>
		</Tooltip>
	);
}

/** The newest refusal, so a dismissed one still cached cannot hide a later one. */
function latestStepUpRefusal(queryCache: QueryCache): unknown {
	let latest: { error: unknown; at: number } | undefined;
	for (const { state } of queryCache.getAll()) {
		if (
			state.status === "error" &&
			(latest === undefined || state.errorUpdatedAt > latest.at) &&
			stepUpChallengeOf(state.error) !== undefined
		) {
			latest = { error: state.error, at: state.errorUpdatedAt };
		}
	}
	return latest?.error;
}

/** Every read in a user view needs a recent sign-in, so any page's refusal asks for one here. */
function UserViewConfirmAccess() {
	const queryCache = useQueryClient().getQueryCache();
	const refusal = useSyncExternalStore(
		(onStoreChange) => queryCache.subscribe(notifyManager.batchCalls(onStoreChange)),
		() => latestStepUpRefusal(queryCache),
	);
	const [dismissed, setDismissed] = useState<unknown>();
	const challenge = refusal === dismissed ? undefined : stepUpChallengeOf(refusal);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	return (
		<ConfirmAccessDialog
			open={challenge !== undefined}
			onOpenChange={(open) => {
				if (!open) {
					setDismissed(refusal);
				}
			}}
			maxAgeSeconds={challenge?.maxAgeSeconds}
			providers={confirmAccess.providers}
			loading={confirmAccess.loading}
			error={confirmAccess.error}
			onRetry={confirmAccess.retry}
			onSignIn={confirmAccess.signIn}
		/>
	);
}

/**
 * The active workspace's provider colours, on app surfaces only: sign-in reads no workspace. A public
 * activity page wears its own workspace's, whoever reads it, since a visitor has no workspace here.
 */
function ProviderColors() {
	const access = useWorkspaceAccess();
	const own = toScmProviderType(
		access.administrationWorkspace?.providerType ?? access.chromeWorkspace?.providerType,
	);
	const published = useMatch({
		from: "/w/$workspaceSlug/",
		shouldThrow: false,
		select: ({ loaderData }) =>
			loaderData?.status === "ready" ? loaderData.page.providerType : undefined,
	});
	useProviderColors(toScmProviderType(published ?? own));
	return null;
}

/**
 * The noun of the workspace's work, for every surface: only a provider the workspace names, never
 * the fallback. Context reaches portals, so drawers read it too.
 */
function WorkProviderScope({ children }: { children: ReactNode }) {
	const access = useWorkspaceAccess();
	const [workProvider] = scmKindOf(
		access.administrationWorkspace?.providerType ?? access.chromeWorkspace?.providerType,
	);
	return <WorkProviderContext value={workProvider}>{children}</WorkProviderContext>;
}

/** The workspace's own source-control provider, as the integration kind the sidebar lists it under. */
function scmKindOf(providerType: string | undefined): ("GITHUB" | "GITLAB")[] {
	if (providerType === "GITLAB" || providerType === "GITHUB") {
		return [providerType];
	}
	return [];
}

function sidebarContextOf(pathname: string): SidebarContext {
	if (pathname.startsWith("/admin")) {
		return "admin";
	}
	return "main";
}

function AppSidebarContainer() {
	const { pathname } = useLocation();
	const { isAuthenticated, username, isAppAdmin, userView } = useAuth();
	const navigate = useNavigate();
	const switchWorkspace = useWorkspaceSwitcher();
	const workspaceAccess = useWorkspaceAccess();
	const {
		chromeWorkspaceSlug,
		chromeWorkspace,
		workspaces,
		administrationWorkspace,
		administrationWorkspaceSlug,
	} = workspaceAccess;
	const adminSlug = workspaceAccess.adminWorkspaceSlug;
	const hasWorkspace = Boolean(chromeWorkspaceSlug);
	// Admin-only menu data belongs to an admin journey. Ordinary member pages keep static links
	// without asking protected endpoints for hints or an approval count.
	const onWorkspaceAdministration =
		useMatch({
			from: "/_authenticated/w/$workspaceSlug/admin",
			shouldThrow: false,
		}) !== undefined;
	const integrationCatalogQuery = useQuery({
		...getIntegrationCatalogOptions({ path: { workspaceSlug: adminSlug ?? "" } }),
		enabled: onWorkspaceAdministration && workspaceAccess.isAdmin && Boolean(adminSlug),
		placeholderData: (previousData) => previousData,
	});
	const integrationCatalog = Array.isArray(integrationCatalogQuery.data)
		? integrationCatalogQuery.data
		: [];
	const integrationKinds = [
		...new Set([
			...integrationCatalog.map((entry) => entry.kind),
			...scmKindOf(administrationWorkspace?.providerType ?? chromeWorkspace?.providerType),
		]),
	];

	const sidebarContext = sidebarContextOf(pathname);
	const awaitingApproval = useFeedbackAwaitingApproval(
		adminSlug,
		onWorkspaceAdministration &&
			workspaceAccess.isAdmin &&
			(administrationWorkspace?.practicesEnabled ?? chromeWorkspace?.practicesEnabled) === true &&
			sidebarContext === "main",
	);

	const isMember = workspaceAccess.role !== undefined;
	// The server refuses this read in a user view, since it carries the signed-in account's own
	// choice. A view keeps Hephaestus listed, as before, for the viewed member's saved conversations.
	const onboarding = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug: chromeWorkspaceSlug ?? "" } }),
		enabled: sidebarContext === "main" && isAuthenticated && isMember && !userView && hasWorkspace,
	});
	const showConversation =
		isMember &&
		(Boolean(userView) ||
			(onboarding.data !== undefined && !isConversationOutOfReach(onboarding.data)));

	// Read only where the sidebar says "No workspace".
	const workspaceCreation = useWorkspaceCreation({
		enabled: isAuthenticated && !workspaceAccess.isLoading && workspaces.length === 0,
	});

	if (!isAuthenticated || username === undefined) {
		return null;
	}

	const handleWorkspaceChange = (ws: typeof chromeWorkspace) => {
		if (!ws) {
			return;
		}
		void switchWorkspace(ws);
	};

	const handleAddWorkspace = () => {
		void navigate({ to: "/workspaces/new" });
	};

	return (
		<AppSidebar
			isAdmin={workspaceAccess.isAdmin}
			isOwner={workspaceAccess.role === "OWNER"}
			isAppAdmin={isAppAdmin}
			showConversation={showConversation}
			readOnly={Boolean(userView)}
			integrationKinds={integrationKinds}
			awaitingApproval={awaitingApproval}
			context={sidebarContext}
			workspaces={workspaces}
			activeWorkspace={chromeWorkspace}
			administrationWorkspace={administrationWorkspace}
			administrationWorkspaceSlug={administrationWorkspaceSlug}
			administrationWorkspaceError={
				administrationWorkspaceSlug !== undefined && workspaceAccess.error !== undefined
					? { error: workspaceAccess.error, onRetry: workspaceAccess.refetch }
					: undefined
			}
			onWorkspaceChange={handleWorkspaceChange}
			onAddWorkspace={handleAddWorkspace}
			workspacesLoading={workspaceAccess.isLoading}
			workspaceCreation={workspaceCreation}
		/>
	);
}

function PublicLoginOverlay() {
	const { login: open } = Route.useSearch();
	const { isAuthenticated, login } = useAuth();
	const location = useLocation();
	const router = useRouter();
	const providers = useSignInProviders(Boolean(open) && !isAuthenticated);
	const returnTo = safeReturnTo(
		location.maskedLocation?.search.returnTo ??
			router.buildLocation({
				to: ".",
				search: (previous) => ({ ...previous, login: undefined }),
				hash: true,
			}).href,
	);
	return (
		<LoginDialog
			open={Boolean(open) && !isAuthenticated}
			options={providers}
			onSignIn={(registrationId) => login(registrationId, returnTo)}
			devReturnTo={returnTo}
			onClose={() => {
				if (location.maskedLocation) {
					router.history.back();
				} else {
					void router.navigate({
						to: ".",
						search: (previous) => ({ ...previous, login: undefined }),
						hash: true,
						replace: true,
						resetScroll: false,
					});
				}
			}}
		/>
	);
}
