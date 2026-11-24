---
"hephaestus": minor
---

Counts of people now show only from 5 people, to protect individuals.
**Practices across the workspace** shows a count, a split or a typical range only from 5 developers, up from 3.
A split with a part of 1 to 4 developers, or with every developer in one part, shows only its total.
The page now counts everyone whose work was reviewed in the period and who has a standing in a practice.
Hidden members, members who left, the review scope and the groups the page shows no longer change who it counts.
**Open feedback** shows only your own figure, without a typical range.
A suspended account, and a change to a practice's reviewer, criteria or group, can still change the counts.
The page says that standings rest on earlier reviews only when at least 5 developers have such standings.
The public activity page no longer counts the contributors it does not name, and its period no longer moves when a person hides.
Its people helped count now includes every human author of a reviewed pull request, whatever that author or the workspace chose.
Workspace settings no longer count the people that the public activity page leaves out.
The Slack channel list no longer counts members who opted out, and the sync view no longer counts each channel's stored messages.
The instance console counts the members each workspace's member list shows, and withholds a count of 1 to 4.
In the API, the public-hidden-count operation, the Slack channel's `optedOutMemberCount` and the public page's `unlistedContributors` are removed, and the instance workspace's `memberCount` can be absent.
