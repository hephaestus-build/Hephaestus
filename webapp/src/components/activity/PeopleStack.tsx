import { cn } from "cn";
import type { UserInfo } from "@/api/types.gen";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { ACTIVITY_TONES } from "./activity-tones";
import { MemberAvatar } from "./MemberAvatar";
import { REVIEWER_STATE_DEFS, type ReviewerState } from "./work-state-defs";

/** Primer's AvatarStack shows at most four faces; the rest are a count. */
const SHOWN = 4;

export interface StackedPerson {
	user: UserInfo;
	/** Where their review stands, drawn as a dot on the avatar; none for a person with no review. */
	state?: ReviewerState;
}

export interface PeopleStackProps {
	people: readonly StackedPerson[];
	providerType: ProviderType;
	/** What the people are to the row, since no heading names them: "Reviewers", "People". */
	"aria-label": string;
}

function describe(person: StackedPerson): string {
	return person.state === undefined
		? person.user.name
		: `${person.user.name} ${REVIEWER_STATE_DEFS[person.state].label}`;
}

/**
 * Up to four faces, then "+N", each named in a tooltip and in its accessible text. A reviewer's
 * face carries a dot in the provider's icon and colour for where their review stands, as GitHub's
 * reviewer list and GitLab's reviewer avatars draw it, so the state is a shape as well as a colour.
 */
export function PeopleStack({ people, providerType, "aria-label": label }: PeopleStackProps) {
	const shown = people.slice(0, SHOWN);
	const rest = people.slice(SHOWN);
	return (
		<ul aria-label={label} className="flex shrink-0 items-center gap-1">
			{shown.map((person) => {
				const def = person.state === undefined ? undefined : REVIEWER_STATE_DEFS[person.state];
				const StateIcon = def?.icon(providerType);
				return (
					<li key={person.user.id} className="flex">
						<Tooltip>
							<TooltipTrigger
								render={<span role="img" aria-label={describe(person)} />}
								className="relative flex"
							>
								<MemberAvatar user={person.user} size="sm" />
								{def && StateIcon && (
									<span
										aria-hidden
										className="absolute -right-1 -bottom-1 flex size-3.5 items-center justify-center rounded-full bg-card"
									>
										<StateIcon
											size={12}
											className={cn("shrink-0", ACTIVITY_TONES[def.tone].text)}
										/>
									</span>
								)}
							</TooltipTrigger>
							<TooltipContent>{describe(person)}</TooltipContent>
						</Tooltip>
					</li>
				);
			})}
			{rest.length > 0 && (
				<li className="flex">
					<Tooltip>
						<TooltipTrigger
							render={
								<span
									role="img"
									aria-label={`and ${rest.map((person) => describe(person)).join(", ")}`}
								/>
							}
							className="flex h-6 min-w-6 items-center justify-center px-1 text-xs"
						>
							<span className="text-muted-foreground tabular-nums">+{rest.length}</span>
						</TooltipTrigger>
						<TooltipContent>{rest.map((person) => describe(person)).join(", ")}</TooltipContent>
					</Tooltip>
				</li>
			)}
		</ul>
	);
}
