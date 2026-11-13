# ADR 0093: Members see activity, a workspace can publish its public work, and private practice feedback has one reader

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md), [ADR 0047](0047-the-practice-profile-has-one-reader.md), [ADR 0051](0051-practices-across-the-workspace-counts-developers.md), [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md)

**Builds on:** [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) (rule 16, read-only user views), [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) (standing), [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md) (the public page at the workspace address)

## Context

Four ADRs and nine updates or amendments decided who sees activity and practice data.

ADR 0052 reversed the "never ranks" rule of ADR 0045. ADR 0051 carried five update blocks.
Some text no longer held. The **Show me** default, the notice, the public fields, the visibility check and the anonymous endpoint count had changed.
This ADR states at 1.0 who sees what, as rules. Rule 9 records a product decision of 2026-11-11.

[Privacy](../user/privacy.mdx), [Public activity page](../user/public-activity.mdx) and [Public activity pages](../admin/public-activity.mdx) are the reader pages. [Activity people aggregate](../contributor/activity-people-aggregate.mdx) keeps the design.

## Decision drivers

- Every number opens the work that it counts. No weight, no score, no streak.
- Public data goes only where the provider already publishes the same work, and each person can leave.
- By default, nobody's data reaches an indefinite number of people without their own choice ([GDPR Art. 25(2)](https://gdpr-info.eu/art-25-gdpr/)).
- Feedback that skips approval has one reader. Other members see a developer's practice data only inside counts.
- A person who reads a named developer's private record gives a reason and leaves a record.

## Considered options

1. **Members only for activity, names only, no public read** (ADR 0045). The revisit trigger of ADR 0045 fired: a maintainer could not find the active people in a list by name.
2. **A public read of the whole workspace** (`isPubliclyViewable`). It opens every `GET` endpoint, and a person cannot leave.
3. **Sorted member activity, one narrow public page behind three switches, practice data for the developer and as counts (chosen).**
4. **A workspace-admin audience for the Practice profile** (ADR 0047). It reverses the promise that in-app feedback has one reader.

## Decision

1. **Activity needs a workspace membership.** It reads the workspace's repositories without a provider permission check for each viewer. The privacy page says so.
   Enforced by: `ActivityControllerIntegrationTest#shouldDenyEveryAnonymousActivityReadEvenWhenTheWorkspacePublicActivityEnabled`.
2. **Counts, never a score.** **Contributions** adds pull requests opened, pull requests reviewed and issues opened. Each reviewed pull request counts once. No count has a weight, and no other total exists. Reviews of one's own work and bots never count. An outcome counts for the author.
   Enforced by: `ActivityControllerIntegrationTest#shouldCountDistinctWorkAcrossReviewsAndWeeksWhenContributorReviewsRepeatedly`, `#shouldExcludeOwnReviewsAndUnmonitoredRepositoriesWhenCountingPeople` and `#shouldSeparateBotsAndIncludeOutsideContributorsWhenMembershipIsNotRequired`.
3. **Every count opens its list,** and the list follows the rules of its count. Charts are weekly, never a daily calendar.
   Enforced by: `ActivityControllerIntegrationTest#shouldAddUpToTheSummaryWhenEveryPageOfWorkspaceWorkIsWalked`. Weekly only: not enforced. Review catches a daily series in a DTO.
4. **People sort by a count.** The default is **Contributions**, most first, and a tie keeps name order. A sorted table shows positions by standard competition ranking (1, 2, 2, 4) from the shown counts. A name search does not renumber.
   Enforced by: `people-positions.test.ts` and `activity-people-rows.test.ts`.
5. **Active weeks is a figure, never a sort key or a position.**
   1.0 target: on main, `active-weeks` is a sort column with positions (`people-positions.ts`). No pull request delivers it yet.
6. **Needs you,** covered review requests and team review requests work as [Activity § Needs you](../user/activity.mdx#needs-you) describes.
   Enforced by: `ActivityControllerIntegrationTest#shouldListATeamRequestOnlyWhenNothingElseAsksTheMemberOrAnswersIt`.

7. **Three switches must all be on.** The instance setting **Allow public activity pages** is off by default. A workspace admin publishes the page after a confirmation that states what becomes public. Both take effect at once.
   Enforced by: `ActivityControllerIntegrationTest#shouldReservePublicationChangesForAdministratorsAndApplyBothSwitchesImmediately`.
8. **An account is hidden until its person answers.** This includes a roster member who has not signed in. The answer covers every public page at once.
   Enforced by: `ActivityControllerIntegrationTest#shouldShowOnlyTheMemberWhoAnsweredShowOnEveryPublicSurface` and `#shouldHideAnUnclaimedRosterMemberUntilTheyAnswerAndShowThemAgainAfterTheyLeave`.
9. **A contributor without an account is hidden from public activity by default.** The public page counts them only in an aggregate "and N more". A workspace admin can still hide a provider identity, and can show it again.
   Decided, not shipped: on main such a contributor shows until they object. No pull request delivers it yet.
   Enforced by: `ActivityControllerIntegrationTest#shouldKeepObjectionsReversibleWorkspaceScopedAndUnattributedToTheContributor` for the admin hide.
10. **The notice is part of the page.** `PublicActivityNotice` asks a member with **Show me** and **Hide me** of equal weight. It never takes focus. **Decide later** hides it for one visit, and it returns until the member answers. **User settings** has the same switch. There is no email, banner or notice period.
    Enforced by: the stories of `PublicActivityNotice.stories.tsx`. No test checks the return on the next visit.
11. **The page shows only human work in public GitHub and GitLab repositories.** For each person, it shows login, name, avatar, profile link, the counts of `PublicActivityCountsDTO`, first contribution and weekly counts. A person's detail adds review kinds, repositories and a work timeline without other participants. It never shows practices, feedback, observations, Slack, Outline, AI content, teams, comments or automation.
    Enforced by: `ActivityControllerIntegrationTest#shouldIncludeOutsideHumansButNotPrivateRepositoriesCommentsAutomationOrOtherWorkspaces` and `#shouldPublishOnlyGitHubAndGitLabActivity`. No test fails for a new practice field. Review of the DTO catches it.
12. **The page names no "top helpers".** `PublicActivityDTO` and `ActivityHighlightsDTO` have no `highlights.mostPeopleHelped` ([#4155](https://github.com/hephaestus-build/Hephaestus/pull/4155)). Not enforced.
13. **A repository is public on the page only while it is confirmed.** Its stored visibility must be public, read within `repository-visibility-max-age` (48 hours by default). A sync that records access loss stops publication at once.
    Enforced by: `ActivityControllerIntegrationTest#shouldExcludeUnconfirmedExpiredAndUnavailableRepositoriesAndRestoreAfterSync` and `#shouldStopPublicationAtOnceWhenTheSyncProviderRecordsAccessLoss`.
14. **A hidden, erased or deleted person leaves the page and every total.** Positions are computed after the removal. Admins see only how many people the page leaves out, never who hid themselves.
    Enforced by: `ActivityControllerIntegrationTest#shouldApplyAccountOptOutAndWorkspaceHideToRowsAndTotals`, `#shouldKeepLinkedWorkHiddenAfterAccountPurge` and `#shouldListOnlyWhatAnAdminHidAndNeverAPersonWhoHidThemselves`.
15. **One controller is the anonymous activity surface.** `PublicActivityController` serves three `GET` routes: the page, a person and a person's work. Every other workspace handler refuses an anonymous caller.
    Enforced by: `SecurityFilterChainRuntimeIntegrationTest#shouldRejectEveryAnonymousHandlerOutsideTheExplicitAllowlist` with `AnonymousEndpointAllowlist`, and `AdvancedArchitectureTest#shouldNameOnlyExistingClassesInTheAnonymousEndpointAllowlist`.
16. **Each public response sends `Cache-Control: no-store`,** and `X-Robots-Tag: noindex` unless the admin allows search engines. Anonymous reads share a limit of 60 for each minute and client IP. An unknown, unpublished or inactive workspace gives the same not-found answer.
    Enforced by: `ActivityControllerIntegrationTest#shouldExposeRobotsChoiceWithoutInternalIdentifiersAndSupportHead`, `#shouldReturnTheSameErrorForUnknownOffAndInactiveWorkspaces` and `AuthRateLimitFilterTest#shouldShareThePublicActivityLimitAcrossWorkspaceSlugsButNotClientIps`.
    The workspace API answers a signed-in non-member 404, as for an unknown slug ([#4163](https://github.com/hephaestus-build/Hephaestus/pull/4163)).
17. **Publication has no data-quality gate.** The admin guide asks the operator to check the [activity history repair](../admin/activity-history-repair.mdx) before publication. It also advises course workspaces to stay private.
    Not enforced. The operator owns both checks.

18. **The Practice profile has one reader.** Each endpoint serves the caller through `CurrentDeveloperLookup` and takes no user to read on behalf of.
    Enforced by: `PracticeProfileReviewIntegrationTest#foreignReviewIsNotFound` and `ResultControllerIntegrationTest#anotherDevelopersResultIsNotVisible`. No architecture rule stops a new handler with a user parameter. Review catches it.
19. **Workspace admins read observations, not private feedback text.** They read observations and the review and delivery pages, and can mark an observation incorrect. Practice-page and conversation feedback shows them its existence and delivery state, never its text (`FeedbackListReadQuery`, `FeedbackReadService`).
    Enforced by: `MigratedHistoryReadIntegrationTest#shouldKeepPrivateFeedbackOutOfOperatorProjectionsWithoutHidingItFromItsRecipient`.
20. **Admins read a developer's answer, not their rating.** They read the current answer and the explanation of a current **Disagree**. The form says so before sending. A rating and every other note stay the developer's.
    Enforced by: `ResultControllerIntegrationTest#shouldReadLatestFeedbackFactsAndKeepThemScopedToTheResultOwner` for observation pages, and `FeedbackAnswerPrivacyArchTest` for the admin feedback detail.
21. **Only an instance admin reads another member's Practice profile,** through a read-only user view with a stated reason. [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) rule 16 owns that view. Workspace admins have no such access.
    Enforced by: `UserViewIntegrationTest#shouldDenyPrivateContentToAWorkspaceOwnerWhoIsNotAnInstanceAdministrator`, `#shouldRejectAViewAndRecordNothingWhenTheReasonIsMissing` and `UserViewArchitectureTest`.
22. **Practices across the workspace counts developers and never names one.** Members read it. Each counted developer counts once, at their Practice profile standing. Hidden members, members who left, inactive accounts and suppressed people are not counted.
    Enforced by: `WorkspaceOverviewWireTest#shouldCarryOnlyCountsAndTheReadersOwnStandingWhenSerialized` and `PracticesAcrossWorkspaceServiceTest#shouldCountEachDeveloperOnceAtTheirProfileStandingWhenOneHasMuchMoreWork`.
23. **No count is too small to show.** Every split shows all its parts. Only the 25th and 75th percentiles leave the server, never a minimum, maximum or average. The **You** bracket spans the reader's part. Tiles read 7, 30 or 90 days, or all time. [Small counts](../user/practices-across-the-workspace.mdx#small-counts) tells the reader when a count can reveal one developer.
    Enforced by: `WorkspaceSplitsTest#shouldShowAllPartsWhenNobodyOrOneDeveloperIsCounted` and `#shouldInterpolateAQuartileWhenItFallsBetweenTwoValues`, and `PracticesAcrossWorkspaceIntegrationTest#shouldCountOnlyTheLastSevenDaysWhenTheTilesAskForThatWindow`.
24. **Practice data never sorts people and never reaches a public page.**
    Not enforced. The public and people DTOs have no practice field. Review of either DTO catches a new one.

## What changed, and why

- **Contributors without an account are hidden by default** (ADR 0052 showed them until they objected). ADR 0052 had declined an opt-in for them. EDPB Guidelines 4/2019 ¶56 says that the controller "shall by default limit accessibility". The person can then intervene before publication. The maintainers decided this on 2026-11-11.
- **Accounts are hidden until they answer** (ADR 0052 said "on by default"). [#3510](https://github.com/hephaestus-build/Hephaestus/pull/3510) decided it, with the notice of rule 10 in place of a first-visit step. The EDPB asks for "the possibility to intervene before publishing".
- **The public fields are listed** (ADR 0052 named three counts). The shipped DTO also sends merged pull requests, people helped, active weeks, first contribution and weekly counts. The unread `mostPeopleHelped` goes (rule 12). Art. 25(2) allows only the data that a purpose needs.
- **Visibility is stored and confirmed, not read at query time** (ADR 0052). A public read makes no provider call. A missing or expired confirmation removes the repository.
- **Three anonymous routes, not one endpoint** (ADR 0052). The person and work routes serve the person drill-down. All three stay on one controller and on the allowlist.
- **The provider comparison is advice, not a gate.** ADR 0052 said that a workspace goes public "only after it passes". No code checks it.
- **Active weeks loses its sort and positions.** A sortable count of active weeks rewards presence. ADR 0045 rejected that with the streak.
- **Admins read that a developer answered** (ADR 0047 said that only the explanation crosses). The privacy page says so since [#2793](https://github.com/hephaestus-build/Hephaestus/pull/2793). The rating and other notes stay private.
- **ADR 0047 named `bodyVisibleToOperator` in `ReviewFeedbackQueryService`.** Neither exists. Rule 19 names the classes.
- **The non-member answer becomes 404.** ADR 0052 said that nobody can probe which workspaces exist. The API answers 403 to a signed-in non-member, which shows that the workspace exists. The maintainers chose 404 on 2026-11-10.

## Consequences

- Good: nobody with an account appears in public before they choose.
- Bad: a sorted table with positions is a leaderboard in function. It keeps out weights, points, leagues, streaks and a leaderboard digest.
- Bad: members can game Contributions with small pull requests. Each count opens its list, so every reader sees the gaming.
- Bad: hiding is from the public, not from the workspace. Members can find who is hidden by a comparison.
- Bad: a small count on Practices across the workspace can tell members one developer's standing.
- Bad: the page reads other developers' standings under `PRACTICE_FEEDBACK_DELIVERY`. The controller has not recorded if a count needs its own purpose.

## Confirmation

Each rule names its test or says `Not enforced.` Rule 5 is a 1.0 target.
No architecture rule covers rules 18 and 24. Code review catches a user parameter on a Practice profile handler and a practice field on an activity DTO.

## Revisit trigger

- Evidence that Contributions is used as a target, or in a performance review or grade.
- A complaint from a person on a public page.
- A full DPIA that does not confirm the public or the workspace audience.
- A member who reports that Practices across the workspace told others their standing.
- Workspace admins who cannot do their job from the practice-review pages.

## Sources

- [GDPR Art. 25](https://gdpr-info.eu/art-25-gdpr/). By default, data must not be "accessible without the individual's intervention to an indefinite number of natural persons". This gives rules 8 and 9. It is also why rule 12 deletes an unread field.
- EDPB, [Guidelines 4/2019 on Article 25, v2.0](https://www.edpb.europa.eu/our-work-tools/our-documents/guidelines/guidelines-42019-article-25-data-protection-design-and_en), § 56–57. The controller should "give the data subject the possibility to intervene before publishing". This supports rule 8. It leaves rule 9 as an open risk, listed under Consequences.

## Already priced and declined

- [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md#considered-options): gamification kept behind its switches, and stored dismissals or snoozes of review requests.
- [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md#considered-options): a radar chart of the mix, and a calendar heat map.
- [ADR 0047](0047-the-practice-profile-has-one-reader.md#considered-options): a workspace-admin audience, and a member opt-in to show the profile to admins.
- [ADR 0051](0051-practices-across-the-workspace-counts-developers.md#considered-options): the comparison as a layer or a tab in the Practice profile.
- [ADR 0051](0051-practices-across-the-workspace-counts-developers.md#considered-options): a standing badge and a trend on each group.
- [ADR 0051](0051-practices-across-the-workspace-counts-developers.md#considered-options): a smallest count of 5, 10 or 3.
- [ADR 0051](0051-practices-across-the-workspace-counts-developers.md#considered-options): a switch that hides the comparison, and a question about the reader's estimate.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#considered-options): names only, a name sort as the default, and a weighted score.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#considered-options): commits, pull requests only, or comments as the default measure.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#considered-options): no position number, and dense ranking.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#considered-options): a public read of the whole workspace, and opt-in for people without an account. Rule 9 now hides them by default.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#considered-options): an email with a notice period, and a banner on every page.
- [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md#access): `Cache-Control: public, max-age=60`.
