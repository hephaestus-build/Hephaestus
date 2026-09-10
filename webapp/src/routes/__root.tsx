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
import type React from "react";
import { toast } from "sonner";

import { getIntegrationCatalogOptions, listThreadsOptions } from "@/api/@tanstack/react-query.gen";
import { ImpersonationBanner } from "@/components/auth/ImpersonationBanner";
import { CookieConsentBanner } from "@/components/consent/CookieConsentBanner";
import Footer from "@/components/core/Footer";
import Header from "@/components/core/Header";
import { AppSidebar, type SidebarContext } from "@/components/core/sidebar/AppSidebar";
import { SkipToContent } from "@/components/core/SkipToContent";
import { StandardPageSurface } from "@/components/core/StandardPageSurface";
import { ActiveSurveyDialog } from "@/components/feedback/ActiveSurveyDialog";
import { ProductFeedbackDialog } from "@/components/feedback/ProductFeedbackDialog";
import { Chat } from "@/components/mentor/Chat";
import { Copilot } from "@/components/mentor/Copilot";
import { defaultPartRenderers } from "@/components/mentor/renderers";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { Toaster } from "@/components/ui/sonner";
import environment from "@/environment";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useMentorChat } from "@/hooks/use-mentor-chat";
import { useActiveSurvey, useSubmitProductFeedback } from "@/hooks/use-product-feedback";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useWorkspaceSwitcher } from "@/hooks/use-workspace-switcher";
import { type AuthContextType, useAuth } from "@/integrations/auth/AuthContext";
import { FeatureFlagDevTools, useFeatureFlag } from "@/integrations/feature-flags";
import { isCopilotExcludedRoute } from "@/lib/copilot-route";
import { getProviderSlug } from "@/lib/provider";

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
				<CookieConsentBanner />
				<ProviderColorScope>
					<main id="main-content" tabIndex={-1}>
						<Outlet />
					</main>
				</ProviderColorScope>
				<Toaster />
			</>
		);
	}

	return (
		<>
			<HeadContent />
			<SkipToContent />
			{/* Rendered early so keyboard/AT users reach the consent region before the app chrome. */}
			<CookieConsentBanner />
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
			{showCopilot && <GlobalCopilot />}
			<FeatureFlagDevTools />
			{!isLoading && isAuthenticated ? <GlobalSurvey /> : null}
		</>
	);
}

function GlobalSurvey() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const survey = useActiveSurvey(workspaceSlug);
	return (
		<ActiveSurveyDialog
			key={survey.survey?.id}
			survey={survey.survey}
			isSubmitting={survey.isSubmitting}
			isDismissing={survey.isDismissing}
			onSubmit={survey.submit}
			onDismiss={survey.dismiss}
		/>
	);
}

export const Route = createRootRouteWithContext<MyRouterContext>()({
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

function GlobalCopilot() {
	// No `onError`: `Chat` renders `status === "error"` inside the transcript, where the reader
	// already is, rather than as a toast away from the conversation that failed.
	const mentorChat = useMentorChat({});

	const router = useRouter();
	const { isAuthenticated, isLoading } = useAuth();
	const { enabled: hasMentorAccess } = useFeatureFlag("MENTOR_ACCESS");
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const { features, isLoading: featuresLoading } = useWorkspaceFeatures(workspaceSlug);

	const handleMessageSubmit = ({ text }: { text: string }) => {
		if (!text.trim()) return;
		mentorChat.sendMessage(text);
	};

	const handleVote = (messageId: string, isUpvote: boolean) => {
		mentorChat.voteMessage(messageId, isUpvote);
	};

	const handleMessageEdit = (messageId: string, content: string) => {
		const messageIndex = mentorChat.messages.findIndex((message) => message.id === messageId);
		if (messageIndex === -1) return;
		mentorChat.setMessages(mentorChat.messages.slice(0, messageIndex));
		mentorChat.sendMessage(content);
	};

	const handleCopy = (content: string) => {
		navigator.clipboard.writeText(content).catch(() => {
			toast.error("Couldn't copy that to the clipboard.");
		});
	};

	if (
		isLoading ||
		featuresLoading ||
		!isAuthenticated ||
		!workspaceSlug ||
		!hasMentorAccess ||
		!features?.mentorEnabled
	) {
		return null;
	}

	return (
		<Copilot
			hasMessages={mentorChat.messages.length > 0}
			onNewChat={() => {
				mentorChat.setMessages([]);
			}}
			onOpenFullChat={() => {
				const threadId = mentorChat.currentThreadId ?? mentorChat.id;
				if (threadId && workspaceSlug) {
					void router.navigate({
						to: "/w/$workspaceSlug/mentor/$threadId",
						params: { threadId, workspaceSlug },
					});
				}
			}}
		>
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				status={mentorChat.status}
				readonly={false}
				attachments={[]}
				onMessageSubmit={handleMessageSubmit}
				onMessageEdit={handleMessageEdit}
				onStop={() => void mentorChat.stop()}
				onFileUpload={() => Promise.resolve([])}
				onAttachmentsChange={() => {}}
				onCopy={handleCopy}
				onVote={handleVote}
				inputPlaceholder="Ask me anything..."
				disableAttachments
				className="h-full max-h-none"
				partRenderers={defaultPartRenderers}
			/>
		</Copilot>
	);
}

function HeaderContainer() {
	const {
		isAuthenticated,
		isLoading,
		username,
		userProfile,
		login,
		logout,
		getUserProfilePictureUrl,
	} = useAuth();
	const {
		chromeWorkspaceSlug,
		userLogin: workspaceUserLogin,
		userName: workspaceUserName,
	} = useWorkspaceAccess();

	const effectiveUsername = chromeWorkspaceSlug ? workspaceUserLogin : username;
	const effectiveName =
		workspaceUserName ?? (userProfile && `${userProfile.firstName} ${userProfile.lastName}`);
	// Feedback about the product reaches instance administrators either way; carrying the chrome's
	// workspace lets them answer a member in context, and the instance-only path is for an account
	// that belongs to no workspace at all.
	const feedback = useSubmitProductFeedback(chromeWorkspaceSlug);

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
				<ProductFeedbackDialog
					isSubmitting={feedback.isPending}
					onSubmit={(kind, message) =>
						feedback.submit({ kind, message, pagePath: window.location.pathname })
					}
				/>
			}
			onLogin={(idpHint) => login(idpHint)}
			onLogout={() => void logout()}
		/>
	);
}

function ProviderColorScope({ children }: { children: React.ReactNode }) {
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
