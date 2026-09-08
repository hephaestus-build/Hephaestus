import { LinkIcon, type LucideIcon, Unlink } from "lucide-react";
import { useEffect, useRef } from "react";

import type { IdentityProviderView, IdentityView } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	type BrandIcon,
	GithubIcon,
	GitlabIcon,
	OutlineIcon,
	SlackIcon,
} from "@/components/icons/brand";
import {
	AlertDialog,
	AlertDialogCancel,
	AlertDialogClose,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";
import { getProviderLabel } from "@/lib/provider";
import { firstNonBlank } from "@/lib/text";

/** Both types are named because `BrandIcon` is a plain component and `LucideIcon` is not. */
const PROVIDER_ICONS: Record<string, LucideIcon | BrandIcon> = {
	GITHUB: GithubIcon,
	GITLAB: GitlabIcon,
	SLACK: SlackIcon,
	OUTLINE: OutlineIcon,
};

/** Providers that can only be *linked* from Settings — they are never a sign-in method. */
const LINK_ONLY_PROVIDER_TYPES = new Set(["SLACK", "OUTLINE"]);

type LinkableProvider = IdentityProviderView & { registrationId: string };

/**
 * Why each link-only account is worth connecting. Both are linked, never signed in with, so the copy
 * has to earn the click on its own — the account it links to is not a way into Hephaestus.
 */
const LINK_ONLY_RATIONALE: Record<string, string> = {
	SLACK: "Connect Slack to manage your channel-message preference and reach the mentor in a DM.",
	OUTLINE: "Connect Outline so the documents you write there are recognised as your work.",
};

/**
 * Resolve a brand icon from a provider type (e.g. "GITHUB", "GITLAB"). Falls back
 * to a generic link icon for unknown providers so new IdPs render gracefully.
 */
function getProviderIcon(providerType?: string): LucideIcon | BrandIcon {
	if (!providerType) return LinkIcon;
	return PROVIDER_ICONS[providerType.toUpperCase()] ?? LinkIcon;
}

function matchesProvider(identity: IdentityView, provider: IdentityProviderView): boolean {
	return (
		Boolean(identity.serverUrl) &&
		identity.providerType?.toUpperCase() === provider.providerType?.toUpperCase() &&
		identity.serverUrl === provider.baseUrl
	);
}

function formatLastLogin(lastLoginAt?: Date): string | undefined {
	const date = asDate(lastLoginAt);
	if (!date) return undefined;
	return date.toLocaleDateString(undefined, {
		year: "numeric",
		month: "short",
		day: "numeric",
	});
}

export interface LinkedAccountsSectionProps {
	/** Identities already federated to this account. */
	identities: IdentityView[];
	/** Sign-in providers available to link (offered when not already linked). */
	providers: IdentityProviderView[];
	/**
	 * Start the link flow for a provider. Re-runs sign-in with that provider and
	 * attaches the resulting identity to the current account (top-level redirect).
	 */
	onLink: (registrationId: string) => void;
	/**
	 * Disconnect a linked identity. Disabled for the account's only remaining sign-in
	 * method — removing the last identity would lock the user out, so they delete the
	 * account instead.
	 */
	onUnlink: (identityId: number) => void;
	/** Id of the identity currently being disconnected — shows a spinner and blocks repeat clicks. */
	unlinkingId?: number | null;
	isLoading?: boolean;
	isError?: boolean;
	/** The thrown query error behind `isError`. */
	error?: unknown;
	/** Refetch the identities/providers after a failure. */
	onRetry?: () => void;
}

/**
 * Settings section for federated identities (ADR 0017 native auth).
 *
 * Users can connect additional providers (re-running sign-in, which links the resulting
 * identity to this account) and disconnect ones they no longer want — except the last
 * remaining identity, which is kept so the account can never be locked out of sign-in.
 */
export function LinkedAccountsSection({
	identities,
	providers,
	onLink,
	onUnlink,
	unlinkingId = null,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: LinkedAccountsSectionProps) {
	// Two realms or self-hosted instances of the same provider type are distinct identities.
	const linkableProviders = providers.filter((provider) => {
		const type = provider.providerType?.toUpperCase();
		if (type === "DEV") return false;
		return !identities.some((identity) => matchesProvider(identity, provider));
	});

	// Slack and Outline link an identity but are never a way in, so they cannot be offered among the
	// sign-in providers — they get their own explained CTA instead. An instance can run SEVERAL of
	// either (Outline is unique on (type, base_url), one row per deployment), so this is a list and
	// never a single `find(...)` match; each unconnected one is named by its display name.
	const linkOnlyProviders = linkableProviders.filter(
		(provider): provider is LinkableProvider =>
			LINK_ONLY_PROVIDER_TYPES.has(provider.providerType?.toUpperCase() ?? "") &&
			Boolean(provider.registrationId),
	);
	const signInProviders = linkableProviders.filter(
		(provider) => !LINK_ONLY_PROVIDER_TYPES.has(provider.providerType?.toUpperCase() ?? ""),
	);

	// Lockout guard: the account's only remaining sign-in method cannot be removed.
	const signInIdentities = identities.filter((identity) =>
		providers.some(
			(provider) =>
				!LINK_ONLY_PROVIDER_TYPES.has(provider.providerType?.toUpperCase() ?? "") &&
				matchesProvider(identity, provider),
		),
	);

	// Focus restoration: when a disconnect succeeds, the row and its trigger unmount and focus
	// would otherwise drop to <body>. Move focus to the section heading so keyboard/SR users
	// land back at "Connected Accounts". We detect a removal by the identities list shrinking.
	const headingRef = useRef<HTMLHeadingElement>(null);
	const prevIdentityCount = useRef(identities.length);
	useEffect(() => {
		if (identities.length < prevIdentityCount.current) {
			headingRef.current?.focus();
		}
		prevIdentityCount.current = identities.length;
	}, [identities.length]);

	return (
		<section className="space-y-4" aria-labelledby="linked-accounts-heading">
			<div className="space-y-1">
				{/* Programmatic focus target (see above). It's removed from the tab order (tabIndex={-1}),
				    so :focus-visible would not fire on the post-deletion .focus(); use :focus so focus
				    landing here after a row unmounts is actually visible, instead of disappearing. */}
				<h2
					ref={headingRef}
					tabIndex={-1}
					id="linked-accounts-heading"
					className="text-xl font-semibold rounded-sm outline-none focus:ring-2 focus:ring-ring"
				>
					Connected Accounts
				</h2>
				<p className="text-sm text-muted-foreground">
					The identity providers you can sign in to Hephaestus with, plus content tools you connect
					so your work is attributed to you. Connect another provider, or disconnect ones you no
					longer use.
				</p>
			</div>

			{isLoading ? (
				<div className="space-y-2" aria-busy="true" aria-label="Loading connected accounts">
					{[0, 1].map((row) => (
						<div
							key={row}
							className="flex items-center gap-4 rounded-lg border p-4"
							aria-hidden="true"
						>
							<Skeleton className="size-8 rounded-md" />
							<div className="flex-1 space-y-2">
								<Skeleton className="h-4 w-40" />
								<Skeleton className="h-3 w-56 max-w-full" />
							</div>
						</div>
					))}
				</div>
			) : isError ? (
				<QueryErrorAlert
					error={error}
					title="Could not load connected accounts"
					onRetry={onRetry}
				/>
			) : (
				<>
					{identities.length === 0 ? (
						<Empty className="border">
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<LinkIcon aria-hidden="true" />
								</EmptyMedia>
								<EmptyTitle>No connected accounts yet</EmptyTitle>
								<EmptyDescription>
									Connect a provider below to sign in with it or attribute your work to this
									account.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					) : (
						<ItemGroup>
							{identities.map((identity) => {
								const identityId = identity.id;
								const Icon = getProviderIcon(identity.providerType);
								const name =
									firstNonBlank(identity.displayName, identity.username, identity.subject) ??
									"Account";
								const lastLogin = formatLastLogin(identity.lastLoginAt);

								return (
									<Item
										key={identityId ?? `${identity.providerType}:${identity.subject}`}
										variant="outline"
										role="listitem"
									>
										<ItemMedia variant="icon">
											<Icon aria-hidden="true" />
										</ItemMedia>
										<ItemContent>
											<ItemTitle>
												<span className="truncate">{name}</span>
												{identity.providerType && (
													<Badge variant="secondary" className="text-xs">
														{getProviderLabel(identity.providerType)}
													</Badge>
												)}
											</ItemTitle>
											{lastLogin && <ItemDescription>Last sign-in {lastLogin}</ItemDescription>}
											{identity.serverUrl && (
												<ItemDescription className="break-all">
													{identity.serverUrl}
												</ItemDescription>
											)}
										</ItemContent>
										{identityId != null && (
											<ItemActions>
												<UnlinkControl
													identityId={identityId}
													name={name}
													providerType={identity.providerType}
													isOnlyIdentity={
														!signInIdentities.some((other) => other.id !== identityId)
													}
													isUnlinking={unlinkingId === identityId}
													onConfirm={() => onUnlink(identityId)}
												/>
											</ItemActions>
										)}
									</Item>
								);
							})}
						</ItemGroup>
					)}

					{linkOnlyProviders.length > 0 && (
						<ItemGroup>
							{linkOnlyProviders.map((provider) => {
								const type = provider.providerType?.toUpperCase() ?? "";
								const Icon = getProviderIcon(type);
								const label =
									firstNonBlank(provider.displayName) ?? getProviderLabel(type, "this account");
								const registrationId = provider.registrationId;
								return (
									<Item key={registrationId} variant="outline" role="listitem">
										<ItemMedia variant="icon">
											<Icon aria-hidden="true" />
										</ItemMedia>
										<ItemContent>
											<ItemTitle>{label} is not connected</ItemTitle>
											<ItemDescription>{LINK_ONLY_RATIONALE[type]}</ItemDescription>
										</ItemContent>
										<ItemActions>
											<Button
												variant="outline"
												size="sm"
												onClick={() => onLink(registrationId)}
												aria-label={`Connect ${label}`}
											>
												<Icon className="size-3.5 mr-1.5" aria-hidden="true" />
												Connect
											</Button>
										</ItemActions>
									</Item>
								);
							})}
						</ItemGroup>
					)}

					{signInProviders.length > 0 && (
						<div className="space-y-2 pt-2">
							<h3 className="text-sm font-medium">Connect another account</h3>
							<p className="text-xs text-muted-foreground">
								Connecting a provider sends you to its sign-in page; the identity you sign in with
								is then linked to this account.
							</p>
							<div className="flex flex-wrap gap-2 pt-1">
								{signInProviders.map((provider) => {
									const Icon = getProviderIcon(provider.providerType);
									const label =
										firstNonBlank(provider.displayName, provider.registrationId) ?? "provider";
									return (
										<Button
											key={provider.registrationId ?? label}
											variant="outline"
											size="sm"
											onClick={() => provider.registrationId && onLink(provider.registrationId)}
											disabled={!provider.registrationId}
											aria-label={`Connect ${label}`}
										>
											<Icon className="size-3.5 mr-1.5" aria-hidden="true" />
											Connect {label}
										</Button>
									);
								})}
							</div>
						</div>
					)}

					{linkableProviders.length === 0 && identities.length > 0 && (
						<p className="pt-2 text-xs text-muted-foreground">
							You've connected all available providers.
						</p>
					)}
				</>
			)}
		</section>
	);
}

interface UnlinkControlProps {
	identityId: number;
	name: string;
	providerType?: string;
	isOnlyIdentity: boolean;
	isUnlinking: boolean;
	onConfirm: () => void;
}

/**
 * The per-identity disconnect control. For the account's only remaining identity it renders an
 * always-visible muted hint explaining the lockout guard (the action is intentionally absent —
 * removing it is done by deleting the account); otherwise a confirmation dialog gates the
 * (reversible) disconnect.
 */
function UnlinkControl({
	identityId,
	name,
	providerType,
	isOnlyIdentity,
	isUnlinking,
	onConfirm,
}: UnlinkControlProps) {
	if (isOnlyIdentity) {
		// The only sign-in method can't be removed (lockout guard). An always-visible sentence
		// states why — accessible to screen-reader/keyboard users, unlike a disabled button's
		// native title. Removing it is done by deleting the account in the Danger Zone.
		return (
			<p
				id={`lockout-hint-${identityId}`}
				className="shrink-0 max-w-3xs text-xs text-muted-foreground"
			>
				Your only sign-in method — delete your account in the Danger Zone to remove it.
			</p>
		);
	}

	const provider = getProviderLabel(providerType);
	// Slack and Outline are link-only — they are never a sign-in method, so the consequence copy must
	// not claim the user is losing one.
	const isLinkOnly = LINK_ONLY_PROVIDER_TYPES.has(providerType?.toUpperCase() ?? "");
	return (
		<AlertDialog>
			<AlertDialogTrigger
				render={
					<Button
						variant="ghost"
						size="sm"
						aria-busy={isUnlinking}
						aria-disabled={isUnlinking}
						aria-label={isUnlinking ? `Disconnecting ${name}` : `Disconnect ${name}`}
						className="shrink-0 text-muted-foreground hover:text-destructive"
					>
						{isUnlinking ? (
							<Spinner className="size-3.5 mr-1.5" aria-hidden="true" />
						) : (
							<Unlink className="size-3.5 mr-1.5" aria-hidden="true" />
						)}
						Disconnect
					</Button>
				}
			/>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogTitle>Disconnect {name}?</AlertDialogTitle>
					<AlertDialogDescription>
						{isLinkOnly
							? `Hephaestus will stop attributing your ${provider} activity to this account. You can reconnect ${provider} anytime from Settings.`
							: `You'll no longer be able to sign in to Hephaestus with this ${provider} account. You can reconnect it anytime by signing in with ${provider} again.`}
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogClose
						render={<Button variant="destructive">Disconnect</Button>}
						onClick={onConfirm}
					/>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
