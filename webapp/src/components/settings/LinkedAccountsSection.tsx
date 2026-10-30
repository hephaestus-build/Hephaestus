import { LinkIcon, Unlink } from "lucide-react";
import { type ReactNode, type RefObject, useEffect, useRef } from "react";

import type { IdentityProviderView, IdentityView } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { getProviderIcon } from "@/components/icons/integration-provider-icons";
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
import { asDate, formatDate } from "@/lib/dates";
import { getProviderLabel } from "@/lib/provider/provider-labels";
import { firstNonBlank, hasText } from "@/lib/text";

/** Providers that can only be *linked* from Settings — they are never a sign-in method. */
const LINK_ONLY_PROVIDER_TYPES = new Set(["SLACK", "OUTLINE"]);

/**
 * The identities the server counts as a way in when it refuses to disconnect the last one. Named
 * rather than derived from the link-only set: the server counts no other type, so an identity of a
 * type not listed here never holds the account's last sign-in.
 */
const SIGN_IN_PROVIDER_TYPES = new Set(["GITHUB", "GITLAB"]);

const signsIn = (identity: IdentityView) =>
	SIGN_IN_PROVIDER_TYPES.has(identity.providerType.toUpperCase());

/**
 * Providers an instance can run more than one deployment of, each its own registration. An account
 * linked to one deployment is not linked to another, so these are matched by origin, not by type.
 */
const PER_INSTANCE_PROVIDER_TYPES = new Set(["GITLAB", "OUTLINE"]);

type LinkableProvider = IdentityProviderView & { registrationId: string };

/**
 * Why each link-only account is worth connecting. Both are linked, never signed in with, so the copy
 * has to earn the click on its own — the account it links to is not a way into Hephaestus.
 */
const LINK_ONLY_RATIONALE: Record<string, string> = {
	SLACK: "Connect Slack to manage your channel-message preference and talk to Hephaestus in a DM.",
	OUTLINE: "Connect Outline so the documents you write there are recognized as your work.",
};

export interface LinkedAccountsSectionProps {
	identities: IdentityView[];
	providers: IdentityProviderView[];
	/**
	 * The deployments the signed-in account is linked to, as the current user reports them: each
	 * provider type with the canonical origin its identity came from.
	 */
	linkedProviders: readonly { type: string; serverUrl?: string }[];
	/**
	 * Start the link flow for a provider. Re-runs sign-in with that provider and
	 * attaches the resulting identity to the current account (top-level redirect).
	 */
	onLink: (registrationId: string) => void;
	onUnlink: (identityId: number) => void;
	/** Id of the identity currently being disconnected — shows a spinner and blocks repeat clicks. */
	unlinkingId?: number | null;
	isLoading?: boolean;
	isError?: boolean;
	/** The thrown query error behind `isError`. */
	error?: unknown;
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
	linkedProviders,
	onLink,
	onUnlink,
	unlinkingId = null,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: LinkedAccountsSectionProps) {
	// A disconnect unmounts the row and its trigger, and focus would otherwise drop to <body>.
	const headingRef = useRef<HTMLHeadingElement>(null);
	const prevIdentityCount = useRef(identities.length);
	useEffect(() => {
		if (identities.length < prevIdentityCount.current) {
			headingRef.current?.focus();
		}
		prevIdentityCount.current = identities.length;
	}, [identities.length]);

	if (isLoading) {
		return (
			<LinkedAccountsFrame headingRef={headingRef}>
				<ItemGroup aria-busy="true" aria-label="Loading connected accounts…">
					{Array.from({ length: 2 }, (_, index) => (
						<Item key={index} variant="outline" role="listitem">
							<ItemMedia variant="icon">
								<Skeleton className="size-4" />
							</ItemMedia>
							<ItemContent>
								<Skeleton className="h-4 w-32" />
								<Skeleton className="h-4 w-44" />
							</ItemContent>
							<ItemActions>
								<Skeleton className="h-8 w-28" />
							</ItemActions>
						</Item>
					))}
				</ItemGroup>
			</LinkedAccountsFrame>
		);
	}
	if (isError) {
		return (
			<LinkedAccountsFrame headingRef={headingRef}>
				<QueryErrorAlert
					error={error}
					title="We could not load your connected accounts"
					onRetry={onRetry}
				/>
			</LinkedAccountsFrame>
		);
	}

	const linkedProviderTypes = new Set(
		identities.map((identity) => identity.providerType.toUpperCase()),
	);

	// Providers the account can still link. A GitLab or Outline deployment is linked only when the
	// account reports that type at that same origin; an origin missing on either side proves nothing,
	// so the deployment stays offered. Every other type allows one deployment, so a linked identity of
	// that type is enough. The synthetic DEV sign-in is not a federated identity — it is never offered
	// as something to "connect".
	const linkableProviders = providers.filter((provider) => {
		const type = provider.providerType?.toUpperCase();
		if (type === "DEV") {
			return false;
		}
		if (!hasText(type)) {
			return true;
		}
		if (PER_INSTANCE_PROVIDER_TYPES.has(type)) {
			const origin = provider.baseUrl;
			return !(
				hasText(origin) &&
				linkedProviders.some(
					(linked) => linked.type.toUpperCase() === type && linked.serverUrl === origin,
				)
			);
		}
		return !linkedProviderTypes.has(type);
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

	const signInCount = identities.filter(signsIn).length;
	// A GitHub or GitLab offered below would let the last one go; Slack and Outline would not.
	const canConnectSignIn = signInProviders.some(
		(provider) =>
			SIGN_IN_PROVIDER_TYPES.has(provider.providerType?.toUpperCase() ?? "") &&
			hasText(provider.registrationId),
	);

	return (
		<LinkedAccountsFrame headingRef={headingRef}>
			{identities.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<LinkIcon aria-hidden="true" />
						</EmptyMedia>
						<EmptyTitle>No connected accounts yet</EmptyTitle>
						<EmptyDescription>
							Connect a provider below to sign in with it or attribute your work to this account.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<ItemGroup>
					{identities.map((identity) => {
						const Icon = getProviderIcon(identity.providerType);
						const name =
							firstNonBlank(identity.displayName, identity.username, identity.subject) ?? "Account";
						const lastLogin = asDate(identity.lastLoginAt);

						return (
							<Item key={identity.id} variant="outline" role="listitem">
								<ItemMedia variant="icon">
									<Icon />
								</ItemMedia>
								<ItemContent>
									<ItemTitle>
										<span className="truncate">{name}</span>
										{hasText(identity.providerType) && (
											<Badge variant="secondary">
												{getProviderLabel(identity.providerType, "Another provider")}
											</Badge>
										)}
									</ItemTitle>
									{lastLogin && (
										<ItemDescription>Last sign-in {formatDate(lastLogin)}</ItemDescription>
									)}
								</ItemContent>
								<ItemActions>
									<UnlinkControl
										identityId={identity.id}
										name={name}
										providerType={identity.providerType}
										isOnlySignIn={signsIn(identity) && signInCount <= 1}
										canConnectSignIn={canConnectSignIn}
										isUnlinking={unlinkingId === identity.id}
										onConfirm={() => onUnlink(identity.id)}
									/>
								</ItemActions>
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
						const { registrationId } = provider;
						return (
							<Item key={registrationId} variant="outline" role="listitem">
								<ItemMedia variant="icon">
									<Icon />
								</ItemMedia>
								<ItemContent>
									<ItemTitle>Connect {label}</ItemTitle>
									<ItemDescription>{LINK_ONLY_RATIONALE[type]}</ItemDescription>
								</ItemContent>
								<ItemActions>
									<Button
										variant="outline"
										size="sm"
										onClick={() => onLink(registrationId)}
										aria-label={`Connect ${label}`}
									>
										<Icon className="mr-1.5 size-3.5" />
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
						Connecting a provider sends you to its sign-in page. After you sign in there, Hephaestus
						adds that identity to this account.
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
									onClick={() => {
										if (hasText(provider.registrationId)) {
											onLink(provider.registrationId);
										}
									}}
									disabled={!hasText(provider.registrationId)}
									aria-label={`Connect ${label}`}
								>
									<Icon className="mr-1.5 size-3.5" />
									Connect {label}
								</Button>
							);
						})}
					</div>
				</div>
			)}

			{linkableProviders.length === 0 && identities.length > 0 && (
				<p className="pt-2 text-xs text-muted-foreground">
					You’ve connected all available providers.
				</p>
			)}
		</LinkedAccountsFrame>
	);
}

function LinkedAccountsFrame({
	headingRef,
	children,
}: {
	headingRef: RefObject<HTMLHeadingElement | null>;
	children: ReactNode;
}) {
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
					className="rounded-sm text-xl font-semibold focus:ring-2 focus:ring-ring focus-visible:outline-hidden"
				>
					Connected accounts
				</h2>
				<p className="text-sm text-muted-foreground">
					The identity providers you sign in with, and the content tools you connect so your work is
					attributed to you. Connect another provider, or disconnect one you no longer use.
				</p>
			</div>

			{children}
		</section>
	);
}

interface UnlinkControlProps {
	identityId: number;
	name: string;
	providerType?: string;
	/** The account's one remaining sign-in identity, which the server refuses to disconnect. */
	isOnlySignIn: boolean;
	/** Another sign-in account is offered below, which would let this one go. */
	canConnectSignIn: boolean;
	isUnlinking: boolean;
	onConfirm: () => void;
}

/**
 * The account's only sign-in identity cannot be disconnected, since that would lock the account out,
 * so it gets a sentence saying why in place of the control; a disabled button's reason would be
 * unreachable by keyboard. A link-only account beside it stays free to disconnect.
 */
function UnlinkControl({
	identityId,
	name,
	providerType,
	isOnlySignIn,
	canConnectSignIn,
	isUnlinking,
	onConfirm,
}: UnlinkControlProps) {
	if (isOnlySignIn) {
		return (
			<p
				id={`lockout-hint-${identityId}`}
				className="max-w-3xs shrink-0 text-xs text-muted-foreground"
			>
				{canConnectSignIn
					? "This is your only sign-in method. To remove it, first connect another sign-in account below."
					: "This is your only sign-in method. To remove it, delete your account in Danger zone."}
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
						variant="quiet"
						size="sm"
						aria-busy={isUnlinking}
						aria-disabled={isUnlinking}
						aria-label={isUnlinking ? `Disconnecting ${name}` : `Disconnect ${name}`}
						className="shrink-0 hover:text-destructive"
					>
						{isUnlinking ? (
							<Spinner className="mr-1.5 size-3.5" />
						) : (
							<Unlink className="mr-1.5 size-3.5" aria-hidden="true" />
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
							? `Hephaestus will stop attributing your ${provider} activity to this account. You can reconnect ${provider} at any time from User settings.`
							: `You’ll no longer be able to sign in to Hephaestus with this ${provider} account. You can reconnect it at any time by signing in with ${provider} again.`}
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
