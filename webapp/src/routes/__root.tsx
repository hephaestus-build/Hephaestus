import { ErrorBoundary } from "@sentry/react";
import { type QueryClient, useQuery } from "@tanstack/react-query";
import {
	createRootRouteWithContext,
	HeadContent,
	Link,
	Outlet,
	useLocation,
	useMatches,
	useNavigate,
	useRouter,
} from "@tanstack/react-router";
import { lazy, type ReactNode, Suspense, useEffect, useEffectEvent, useRef, useState } from "react";
import { toast } from "sonner";

import { getIntegrationCatalogOptions, listThreadsOptions } from "@/api/@tanstack/react-query.gen";
import type { SurveyInvitation } from "@/api/types.gen";
import { ImpersonationBanner } from "@/components/auth/ImpersonationBanner";
import { LoginDialog } from "@/components/auth/LoginDialog";
import { CookieConsentBanner } from "@/components/consent/CookieConsentBanner";
import Footer from "@/components/core/Footer";
import Header from "@/components/core/Header";
import { AppSidebar, type SidebarContext } from "@/components/core/sidebar/AppSidebar";
import { SkipToContent } from "@/components/core/SkipToContent";
import { StandardPageSurface } from "@/components/core/StandardPageSurface";
import {
	type FeedbackKind,
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
	USER_AGENT_MAX_LENGTH,
} from "@/components/feedback/ProductFeedbackDialog";
import { ProductFeedbackMenu } from "@/components/feedback/ProductFeedbackMenu";
import { ProductSurveyDialog } from "@/components/feedback/ProductSurveyDialog";
import {
	EMPTY_SURVEY_RESPONSE_DRAFT,
	type SurveyResponseDraft,
	surveyEstimate,
} from "@/components/feedback/survey-questions";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { Toaster } from "@/components/ui/sonner";
import environment from "@/environment";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useLoginNavigation } from "@/hooks/use-login-navigation";
import { useProductSurveys, useSubmitProductFeedback } from "@/hooks/use-product-feedback";
import { useSignInProviders } from "@/hooks/use-sign-in-providers";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspaceSwitcher } from "@/hooks/use-workspace-switcher";
import { type AuthContextType, useAuth } from "@/integrations/auth/AuthContext";
import { safeReturnTo } from "@/integrations/auth/guard";
import { FeatureFlagDevTools, useFeatureFlag } from "@/integrations/feature-flags";
import { isCopilotExcludedRoute } from "@/lib/copilot-route";
import { getProviderSlug } from "@/lib/provider";

const GlobalCopilot = lazy(() => import("./-GlobalCopilot"));

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
	const { login: loginOpen } = Route.useSearch();
	const { pathname } = useLocation();
	const surface = useMatches({
		select: (matches) => {
			for (let index = matches.length - 1; index >= 0; index -= 1) {
				const matchSurface = matches[index]?.staticData.surface;
				if (matchSurface) return matchSurface;
			}
			return "standard";
		},
	});
	const { isAuthenticated, isLoading } = useAuth();
	const { enabled: hasMentorAccess } = useFeatureFlag("MENTOR_ACCESS");
	const showCopilot =
		!isLoading && isAuthenticated && hasMentorAccess && !isCopilotExcludedRoute(pathname);

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
			{!loginOpen && <CookieConsentBanner />}
			<ImpersonationBanner />
			<ProviderColorScope>
				<SidebarProvider>
					<AppSidebarContainer />
					<SidebarInset
						className="min-w-0"
						style={{ marginRight: "var(--right-sidebar-width, 0)" }}
					>
						<HeaderContainer />
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
			</ProviderColorScope>
			<Toaster />
			<PublicLoginOverlay />
			{showCopilot && (
				<ErrorBoundary fallback={<></>} handled>
					<Suspense fallback={null}>
						<GlobalCopilot />
					</Suspense>
				</ErrorBoundary>
			)}
			<FeatureFlagDevTools />
		</>
	);
}

function ProductFeedbackControls({ workspaceSlug }: { workspaceSlug?: string }) {
	const { pathname } = useLocation();
	const feedback = useSubmitProductFeedback(workspaceSlug);
	const surveys = useProductSurveys(workspaceSlug);
	const [feedbackOpen, setFeedbackOpen] = useState(false);
	const [feedbackKind, setFeedbackKind] = useState<FeedbackKind>("FEEDBACK");
	const [survey, setSurvey] = useState<SurveyInvitation>();
	const [surveyOpen, setSurveyOpen] = useState(false);
	const [drafts, setDrafts] = useState<Record<string, SurveyResponseDraft>>({});
	const nudged = useRef(false);
	const invitations = surveys.query.data ?? [];
	const openSurvey = (surveyId: string) => {
		const next = invitations.find((candidate) => candidate.id === surveyId);
		if (!next) return;
		setSurvey(next);
		setSurveyOpen(true);
	};
	const closeSurvey = () => {
		surveys.reset();
		setSurveyOpen(false);
	};
	// The server remembers the acknowledgement, so a reload never nudges twice for one survey; the
	// ref keeps a second unseen invitation from nudging in the same visit.
	const unseen = invitations.find((candidate) => !candidate.seen);
	const nudge = useEffectEvent((invitation: SurveyInvitation) => {
		surveys.acknowledge(invitation.id);
		toast(`New survey: ${invitation.title}`, {
			description: surveyEstimate(invitation.questions),
			duration: 12000,
			action: { label: "Take survey", onClick: () => openSurvey(invitation.id) },
		});
	});
	useEffect(() => {
		if (!unseen || nudged.current) return;
		nudged.current = true;
		nudge(unseen);
	}, [unseen]);
	return (
		<>
			<ProductFeedbackMenu
				invitations={invitations}
				onSendFeedback={(kind) => {
					setFeedbackKind(kind);
					setFeedbackOpen(true);
				}}
				onOpenSurvey={openSurvey}
			/>
			<ProductFeedbackDialog
				open={feedbackOpen}
				onOpenChange={(open) => {
					if (!open) feedback.reset();
					setFeedbackOpen(open);
				}}
				kind={feedbackKind}
				onKindChange={setFeedbackKind}
				context={{
					pagePath: pathname.slice(0, PAGE_PATH_MAX_LENGTH),
					userAgent: navigator.userAgent.slice(0, USER_AGENT_MAX_LENGTH),
				}}
				isSubmitting={feedback.isPending}
				error={feedback.error}
				onSubmit={feedback.submit}
			/>
			{survey && (
				<ProductSurveyDialog
					survey={survey}
					open={surveyOpen}
					onOpenChange={(open) => {
						if (!open) closeSurvey();
					}}
					draft={drafts[survey.id] ?? EMPTY_SURVEY_RESPONSE_DRAFT}
					onDraftChange={(draft) => setDrafts((current) => ({ ...current, [survey.id]: draft }))}
					isSubmitting={surveys.isPending}
					error={surveys.error}
					onSubmit={async (answers) => {
						if (await surveys.submit(survey.id, answers)) {
							setDrafts(({ [survey.id]: _sent, ...rest }) => rest);
							closeSurvey();
						}
					}}
					onDecline={async () => {
						if (await surveys.decline(survey.id)) closeSurvey();
					}}
				/>
			)}
		</>
	);
}

export const Route = createRootRouteWithContext<MyRouterContext>()({
	validateSearch: (search): { login?: boolean } => ({
		login: search.login === true || search.login === "true" ? true : undefined,
	}),
	// Fallback tab title; the deepest match that sets its own `head` wins.
	head: () => ({ meta: [{ title: "Hephaestus" }] }),
	component: RootLayout,
	notFoundComponent: () => (
		<div className="mx-auto flex w-full max-w-2xl flex-col items-center justify-center py-16 text-center">
			<h1 className="text-3xl font-bold mb-4">Page Not Found</h1>
			<p className="text-muted-foreground mb-8">
				The page you're looking for doesn't exist or you don't have permission to view it.
			</p>
			<Link to="/" className="text-primary hover:underline font-medium">
				Return to Home
			</Link>
		</div>
	),
});

function HeaderContainer() {
	const openLogin = useLoginNavigation();
	const {
		isAuthenticated,
		isLoading,
		username,
		userProfile,
		logout,
		getUserProfilePictureUrl,
		getUserId,
		isImpersonating,
	} = useAuth();
	const {
		chromeWorkspaceSlug,
		userLogin: workspaceUserLogin,
		userName: workspaceUserName,
	} = useWorkspaceAccess();

	const effectiveUsername = workspaceUserLogin ?? username;
	const effectiveName =
		workspaceUserName ?? (userProfile && `${userProfile.firstName} ${userProfile.lastName}`);

	return (
		<Header
			sidebarTrigger={isAuthenticated && <SidebarTrigger className="-ml-1" />}
			version={environment.version}
			environmentName={environment.deployment.name}
			pullRequest={environment.deployment.pullRequest}
			isProduction={environment.deployment.isProduction}
			isAuthenticated={isAuthenticated}
			isLoading={isLoading}
			name={effectiveName}
			username={effectiveUsername}
			avatarUrl={getUserProfilePictureUrl()}
			workspaceSlug={chromeWorkspaceSlug}
			feedbackDialog={
				!isLoading && isAuthenticated && !isImpersonating ? (
					<ProductFeedbackControls
						key={`${getUserId()}:${chromeWorkspaceSlug}`}
						workspaceSlug={chromeWorkspaceSlug}
					/>
				) : null
			}
			onLogin={openLogin}
			onLogout={() => void logout()}
		/>
	);
}

function ProviderColorScope({ children }: { children: ReactNode }) {
	const { providerType } = useActiveWorkspaceSlug();
	return <div data-provider={getProviderSlug(providerType)}>{children}</div>;
}

function AppSidebarContainer() {
	const { pathname } = useLocation();
	const { isAuthenticated, username, isAppAdmin } = useAuth();
	const { enabled: hasMentorAccess } = useFeatureFlag("MENTOR_ACCESS");
	const navigate = useNavigate();
	const switchWorkspace = useWorkspaceSwitcher();
	const workspaceAccess = useWorkspaceAccess();
	const { chromeWorkspaceSlug, chromeWorkspace, workspaces } = workspaceAccess;
	const hasWorkspace = Boolean(chromeWorkspaceSlug);
	const integrationCatalogQuery = useQuery({
		...getIntegrationCatalogOptions({ path: { workspaceSlug: chromeWorkspaceSlug ?? "" } }),
		enabled: workspaceAccess.isAdmin && Boolean(chromeWorkspaceSlug),
		placeholderData: (previousData) => previousData,
	});
	const integrationCatalog = Array.isArray(integrationCatalogQuery.data)
		? integrationCatalogQuery.data
		: [];
	const integrationKinds = [
		...new Set([
			...integrationCatalog.map((entry) => entry.kind),
			...(chromeWorkspace?.providerType === "GITLAB"
				? (["GITLAB"] as const)
				: chromeWorkspace?.providerType === "GITHUB"
					? (["GITHUB"] as const)
					: []),
		]),
	];

	const sidebarContext: SidebarContext = pathname.startsWith("/admin")
		? "admin"
		: pathname === "/mentor" || /^\/w\/[^/]+\/mentor/.test(pathname)
			? "mentor"
			: "main";

	const {
		data: mentorThreads,
		isLoading: mentorThreadsLoading,
		error: mentorThreadsError,
	} = useQuery({
		...listThreadsOptions({
			path: { workspaceSlug: chromeWorkspaceSlug ?? "" },
		}),
		enabled: sidebarContext === "mentor" && isAuthenticated && hasWorkspace,
	});

	if (!isAuthenticated || username === undefined) {
		return null;
	}

	const handleWorkspaceChange = (ws: typeof chromeWorkspace) => {
		if (!ws) return;
		void switchWorkspace(ws);
	};

	const handleAddWorkspace = () => {
		void navigate({ to: "/workspaces/new" });
	};

	return (
		<AppSidebar
			username={username}
			isAdmin={workspaceAccess.isAdmin}
			isAppAdmin={isAppAdmin}
			hasMentorAccess={hasMentorAccess}
			integrationKinds={integrationKinds}
			context={sidebarContext}
			workspaces={workspaces}
			activeWorkspace={chromeWorkspace}
			onWorkspaceChange={handleWorkspaceChange}
			onAddWorkspace={handleAddWorkspace}
			workspacesLoading={workspaceAccess.isLoading}
			mentorThreads={sidebarContext === "mentor" ? mentorThreads : undefined}
			mentorThreadsLoading={sidebarContext === "mentor" ? mentorThreadsLoading : undefined}
			mentorThreadsError={
				sidebarContext === "mentor" && mentorThreadsError ? "Failed to load threads" : undefined
			}
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
				if (location.maskedLocation) router.history.back();
				else
					void router.navigate({
						to: ".",
						search: (previous) => ({ ...previous, login: undefined }),
						hash: true,
						replace: true,
						resetScroll: false,
					});
			}}
		/>
	);
}
