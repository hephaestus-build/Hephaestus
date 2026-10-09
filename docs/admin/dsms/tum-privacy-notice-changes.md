# TUM privacy notice — change list for the legal review

This list gives the changes to the TUM privacy notice for the sorted Workspace activity, the public activity page and workspace addresses.
The decisions are in ADR 0052 and ADR 0053.
The risk reasoning is in the [DPIA pre-screen](./dpia-prescreen.md) § 6.
The notice source is `webapp/public/legal/profiles/tumaet/privacy.md`.

The legal reviewer approves each wording.
Then a change to the notice applies it.
Each item names the release that it waits for.
Items 1 and 2 replace the text about the older public workspace flag, which the public activity page removes.

| # | Section | Change | Waits for |
|---|---|---|---|
| 1 | § 5 Recipients, paragraph "A public workspace exposes …" | Replace the first two sentences with the text in [item 1](#1-public-workspaces). | Public activity page, which removes the older flag |
| 2 | § 5, avatar paragraph | Replace "public directory pages" with "the About page and the public activity page". | Public activity page |
| 3 | § 3 table, row **Activity** | Replace "listed by name and never scored or ranked" with "sortable by each count, with an unweighted Contributions total and a position number, never scored". | Sorted Workspace activity |
| 4 | § 5, first paragraph | Replace "Activity is never ranked" with "Workspace activity can sort people by their counts and show a position number". | Sorted Workspace activity |
| 5 | § 3 table, new row | Add the row in [item 5](#5-the-public-activity-page). | Public activity page |
| 6 | § 5, new paragraph | Add the public as a recipient. See [item 6](#6-recipients-and-objection). | Public activity page |
| 7 | § 7 Your rights | Add the objection paragraph in [item 6](#6-recipients-and-objection), separate from the other rights (Art. 21(4) GDPR). | Public activity page |
| 8 | § 10 Workspace configuration | Add: "Workspace administrators decide whether the workspace has a public activity page. Course workspaces stay private." | Public activity page |
| 9 | § 4 Cookies | Add the text in [item 9](#9-workspace-addresses). | Workspace addresses |
| 10 | § 5 and § 6 | Add Cloudflare as a recipient for the workspace hosts, with its transfer basis. | Workspace addresses, after the [processor checklist](./processor-checklist.md) records Cloudflare |

## Proposed wording

The wording follows the register of the notice.
Paste each block into the section that the table names.

### 1. Public workspaces

```md
Every workspace page requires sign-in, except a public activity page (section 3). The About page
lists the contributors to the Hephaestus open-source repository as GitHub publishes them.
```

### 5. The public activity page

```md
| **Public activity page**, if the workspace administrator publishes it: for each person who contributed to the workspace's public repositories, the display name, login and avatar; the pull requests opened and merged, the pull requests reviewed and their authors, the issues opened and the active weeks in the selected period; the weekly trend, the Contributions total, the position number and links to the work. It never shows practices, feedback, observations, Slack, Outline or AI review content | Show the public who contributes to an open-source project in teaching and research | Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 5(1) sentence 1 no. 1 BayDSG | The page is computed from the repository-activity mirror (row above) on each request and holds no copy. Responses may be cached for up to 60 seconds. When you hide, you leave the page and its totals at once. Copies that third parties made before cannot be recalled |
```

### 6. Recipients and objection

For § 5:

```md
If a workspace administrator publishes a public activity page, anyone can read it without sign-in. It shows the data listed in section 3 for each person who has not hidden. Search engines are asked not to
index it unless an administrator allows it.
```

For § 7:

```md
**Right to object to the public activity page.** You can object at any time to your appearance on
public activity pages. Turn off **Show me on public activity pages** in User settings, or select
**Hide me** when a workspace with a public activity page asks. Without an account, contact us at the address above. We stop
at once and do not ask for reasons. You also leave every total on the page.
```

### 9. Workspace addresses

```md
A workspace can have its own address, such as `artemis.hephaestus.build`. Sign-in and its cookies stay
on `hephaestus.build`. Each workspace address stores its own cookie choice and theme in your browser,
so it asks for your cookie choice once.
```
