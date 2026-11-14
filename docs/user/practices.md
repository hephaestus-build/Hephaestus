---
title: Practices
description: Add practices from the instance catalog, write your own, organize them in groups, and accept or decline catalog updates.
page_type: feature
---

Practices are the engineering practices that Hephaestus checks your workspace's work against.
Workspace admins and owners choose and edit them in **Practice setup**, under **Administration → Practices**.
Members see the practices on their practice profiles.

## Your practices and the instance catalog

The instance catalog holds the practices and practice groups that your instance admin includes.
When you add a practice from it, your workspace gets its own copy.
Your copy changes only when you edit it or accept a catalog update.

Each practice belongs to a practice group, or it is **Unassigned**.
Groups organize the practices on practice profiles.

Each save creates a new version of the practice.
Earlier observations keep the version that judged them, so your edits never rewrite history.

A badge on each practice tells you how it compares with the catalog:

| Badge | What it means |
| --- | --- |
| **Same as the catalog** | Your copy matches the catalog. |
| **Edited here** | Your workspace changed the practice. |
| **Catalog update waiting** | The catalog has a newer version. Your copy stays as it is until you decide in **Catalog updates**. |
| **No longer in the catalog** | New workspaces cannot add it. Your copy keeps working as it is. |

## Add practices from the catalog

1. Under **Administration → Practices**, open **Practice setup**.
2. Select **Show catalog**.
3. Select a practice, or select **Add group** on a group that your workspace does not have yet.
4. Read what adding it does.
5. Select **Add practice**. For a group, select **Add** with the number of practices.

The practice appears in its group, ready for review.
[Practice reviews and feedback mode](./practice-reviews.mdx) explains how to turn on reviews and choose who reviews.

## Create a practice

1. In **Practice setup**, select **Create practice**.
2. Enter **Name \*** and choose a **Practice group**.
3. Under **Reviews**, choose the kind of work. You cannot change it later.
4. Under **Review guidance**, write **What to look for \***.
5. Choose **Reviewed by**.
6. Under **When this practice is reviewed**, choose the events, the states, **About**, and **Reads**.
7. Optional: add conditions under **Applies when**, and write **Otherwise \***.
8. Select **Create practice**.

To change a practice later, open **More actions** on the practice, then select **Edit practice**.
Select **Save changes** to save a new version.

### Review guidance

Write **What to look for \*** as a positive standard: what the work must do to meet the practice.
Describe each required behavior once.
An optional improvement is not a problem.
A broad claim needs evidence for all the work that it covers.

**Why it matters**, **What good looks like**, and **Going well** are guidance for developers.
They do not change how the practice is reviewed.
**Going well** is shown to a developer whose work meets the practice.

### Reviewed by

| Choice | What happens |
| --- | --- |
| **Hephaestus** | An automated review reads the work and records an observation. When a source that it must read cannot be captured, the review is withheld. |
| **People** | A developer, peer, or mentor reviews the practice. No automated review runs. Describe what the review cannot support. |
| **Nobody** | The practice offers guidance without a review. |

Your own practice runs beside the catalog practices, and nothing compares them.
Two practices that judge the same concern can give opposite results on the same work.
Keep one of them, and set **Reviewed by** of the other to **Nobody**.
The feedback mode **Off** stops feedback, but reviews still run.

### When this practice is reviewed

**Review on** selects the events that start a review, such as **Opened**, **Marked ready for review**, **New commits pushed**, or **Merged**.
**When** selects the states of the work that a review accepts, such as **Draft status** and **State**.
A requested review also follows these settings.

To comment on a draft once it is ready, a practice needs **Marked ready for review**, as [Control when feedback reaches the work](./practice-reviews.mdx#control-when-feedback-reaches-the-work) describes.

**Checks finished** reviews a pull request again when every check that GitHub listed has ended.
A check that starts later can add a new review.
If the checks never finish, or the push has none, no review starts for this event.
Add **Opened** or **New commits pushed** if the practice must always run.
Only GitHub reports checks. On GitLab, a practice that needs them is not reviewed.

**About** selects whose work an observation is about: **Author**, **Reviewer**, **Assignee**, or **Whoever merged it**.
Choose **The work** when the observation is about no developer.
If the role has no person, or more than one possible person, the practice is withheld for that work.

**Reads** lists the sources that a review uses.
Set at least one source to **Required**, because a review cannot run without one.
**Must be captured whole** lets the review say that something is missing from a source.
With it, a partial capture withholds the review.
A failure to capture a required source never becomes an observation.

### Applies when

A practice with no condition applies to all work of its kind.
A condition limits the work that it applies to:

- **A file changed**, with **File paths**, such as `**/*.swift`.
- **The diff contains text**, with **Text to find**.
- **A source is not empty**.
- **The work has merged**.

A review can start when any condition holds.
If every condition is false, the observation is **Does not apply**, with your **Otherwise \*** text.
Missing or incomplete evidence never makes a condition false.
Issues have no conditions.

### Practice script

A practice uses the built-in practice script unless you add one under **Script**.
A practice script can prepare the review, or decide from the captured records.
If your script is not valid, the practice stops with a reason and never falls back to the built-in script.
[Practice script API](/admin/practice-scripts) lists what a script can use.

### Practices for one technology

A practice has no technology field.
To limit a practice to one platform, add the condition **A file changed** with its paths, such as `**/*.swift`.
To make a catalog practice stricter for your course, edit your copy and save it.
Do not create one copy of a practice for each technology. The copies drift apart, and their observations stop being comparable.

## Add a visual

A visual is one SVG picture and its description.
The developer's practice panel shows it between **Why it matters** and **What good looks like**.
Add one only when it shows the idea better than a sentence.

1. Open the practice with **Edit practice**.
2. In **Visual**, select **Upload SVG** and choose a file. You can also paste the markup into **SVG markup**.
3. In **Description \***, say what the picture means. Do not describe its shapes or colors.
4. Check the **Light theme** and **Dark theme** previews.
5. Select **Save changes**.

To remove the visual, select **Remove** in **Visual**, then save the practice.

Hephaestus reads the description, never the picture.
Thus, the description must give the full meaning.
[Practice visual rules](./practice-visual-rules.md) lists the markup that Hephaestus accepts, the color classes, and starter templates.

## Add a guide

A guide is a longer explanation in Markdown, with up to 4 figures.
The developer opens it in the **Guide** tab of the practice panel.

1. In **Guide**, write the text in **Guide text**.
2. To add a figure, put the cursor where the figure goes.
3. Select **Add figure** and choose an SVG file.
4. In the new line, replace "Describe the figure" with what the figure means.
5. Select **Preview** to check the text and the figures.
6. Select **Save changes**.

To remove a figure, select **Remove** beside it.
To remove the guide, select **Remove guide**, then save the practice.
A link in a guide opens in a new tab.

## Organize groups

1. In **Practice setup**, select **Create group** to add a group.
2. To change a group, open **More actions** on it. Select **Rename**, **Move up**, **Move down**, or **Delete group**.
3. To move a practice, open **More actions** on it, then choose a group under **Move to**.

The switch on a group shows or hides it on practice profiles.

## Review catalog updates

When the catalog changes a practice that you added, the change waits for you.
Each workspace decides for itself.

1. In **Practice setup**, select **Catalog updates**.
2. Open an update.
3. For each changed field, compare **Base**, **Current**, and **Proposed**.
   **Conflict** marks a field that both your workspace and the catalog changed.
4. Choose **Current** or **Proposed** for each changed field.
5. Select **Accept selected fields**, or select **Decline this update**.

Accepting creates a new version of the practice.
Declining keeps your practice as it is until the catalog changes again.

## Reset a practice to the catalog

1. Open the practice.
2. Select **Reset to catalog**.
3. Read **What the reset changes**.
4. Select **Reset to catalog**.

The catalog's version is saved as a new version of your practice.
Earlier versions and their observations stay.
On a group, **Reset to catalog** under **More actions** brings back the catalog's name, description, icon, and color.

## Delete a practice

1. Open **More actions** on the practice.
2. Select **Delete practice**, and confirm.

:::caution

Deleting a practice also deletes its observations. You cannot undo this.
To stop a practice for a while, set its feedback mode to **Off**, or set **Reviewed by** to **Nobody**.

:::
