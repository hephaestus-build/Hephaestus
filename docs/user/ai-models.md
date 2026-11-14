---
title: AI models and spending
description: Choose the models for practice reviews and conversations, connect your workspace's own AI provider, cap its spend, and set up member onboarding.
page_type: feature
---

Practice reviews and conversations with Hephaestus run on AI models.
Workspace admins and owners choose the models on **AI models** and follow the spend on **AI usage**, under **Administration**.
Workspace owners also set up **Member onboarding**.

## Shared models and your own provider

A workspace can use two kinds of models:

- **Shared models** come from your instance admin, as [Connect an AI provider](/admin/ai-providers) describes. Their spend counts against the shared-model budget that the instance admin sets for your workspace.
- **Own provider** models run on a provider account that your workspace connects. That account pays for them, and your workspace sets their monthly cap.

The two budgets never add up, and neither one pauses the work of the other.
For a shared model, you see its name, its connection, its reasoning effort, and its price.
You never see its provider address or key.

If **New workspace providers and models are disabled** appears, your instance admin turned off workspace providers.
Your existing providers and models stay manageable.

## Data handling and AI choices

Each model declares who operates it, and Hephaestus shows its tier wherever the model appears:

- **In-house**: your organization operates the model.
- **Cloud**: a provider operates it, under terms that your organization accepted.
- **Not declared**: nobody declared it.

**Operated by** on a model sets its tier: **Your organization** makes the model **In-house**, and **A provider** makes it **Cloud**.
Declare what your agreement with the provider says, not what the address suggests.
A private endpoint alone does not mean that your organization operates the model.
Check the provider's retention and training terms before you make the model active.

Each member chooses which AI may handle their work: **In-house**, **Cloud**, or **No AI**.
The choice applies in every workspace on the instance.
[User settings](./user-settings.mdx#your-ai-choice) describes the choice as the member sees it.

On **AI models**, each purpose has one assignment for each tier:

| Assignment | Who it serves |
| --- | --- |
| **In-house** | Members who chose **In-house** or **Cloud** |
| **Cloud** | Members who chose **Cloud** |
| **Members who have not chosen** | Members without a choice, while choosing is optional. Hidden when every member must choose. |

A member who chooses **Cloud** gets the **Cloud** assignment when it is ready, and the **In-house** one when it is not.
A member who chooses **In-house** gets only the **In-house** assignment.
If no ready assignment fits a member's choice, that member gets no practice reviews and no answers in this workspace.

An assignment holds only a model that is declared for its tier.
If a model's declaration changes, its assignment shows **Not ready** until you assign another model.
Hephaestus never substitutes a model from another tier.

## Assign models

1. Under **Administration**, open **AI models**.
2. Under **Reviews and conversations**, select **Practice reviews** or **Conversations**.
3. Under **In-house**, **Cloud**, or **Members who have not chosen**, choose the **Model**.
4. Turn on **Use this model**.
5. Select **Save assignment**.

The assignment no longer shows **Not ready**.
A ready **Conversations** assignment lets every member ask Hephaestus.
If you turn off every **Conversations** assignment, Hephaestus cannot answer anyone in the workspace.

To stop using a model for one assignment, select **Clear assignment**.

## Connect your own provider

Before you start, get an API key from your provider account.

1. On **AI models**, under **Your providers**, select **Add provider**.
2. In **Endpoint preset**, choose **OpenAI**, **Azure OpenAI v1**, **Anthropic**, or **Other endpoint**.
3. Check **API** and **Base URL**, and enter the **API key**.
4. Optional: choose the **Service receiving requests**, such as Azure or a gateway.
5. Select **Add provider**. The provider starts inactive.
6. Select **Test connection**.
7. Select **Add model**, and enter the **Upstream model ID** that your provider expects.
8. Set the **Price**, and choose **Operated by** under **Data handling**.
9. Select **Add inactive model**.
10. Edit the provider, turn on **Active**, and select **Save changes**.
11. Edit the model, turn on **Active**, and select **Save changes**.

Then assign the model, as [Assign models](#assign-models) describes.
For **Operated by**, read [Data handling and AI choices](#data-handling-and-ai-choices).
The instance admin's guide, [Add and price a model](/admin/ai-providers#4-pricing-a-model), explains each price field and when a model can become active.

To remove a provider, delete its models first, then select **Disconnect**.

## Set a cap on your own provider

1. Under **Administration**, open **AI usage**.
2. Select **Set provider cap**, or **Change provider cap**.
3. Enter the **Monthly cap (USD)**.
4. Select **Save cap**.

To remove the cap, select **Remove cap**.
The button appears in the current month, when your workspace uses its own provider.
Your workspace uses its own provider when it has an active model on an active provider, or a call on one this month.

Your instance admin sets the shared-model budget of your workspace, and only they can raise it.
The shared-model budget and your provider cap each apply to one UTC calendar month.
A cap of $0 pauses your own provider at once.

When a budget or cap is reached, its models pause until the next month, or until the limit is raised or removed:

- A practice review is not started, and nothing is posted.
- A conversation answer says that the monthly limit is reached and who can raise it.
- Work that already runs stops at its next model call.

A call of a model without a price shows as **Unavailable cost**.
A capped month with such a call pauses, because Hephaestus cannot check the limit.
Raising the limit does not resume it. Work resumes when the month ends or the limit is removed.

## Read AI usage

**AI usage** shows one month.
**Spend this month** has one tile for **Shared models** and one for **Own provider**.

Each tile shows the spend and, when one is set, the budget or cap, such as `of $25`.
A tile without one shows **No budget set** or **No cap set**.
A tile can show one of these states:

- **Paused**: new calls on these models wait until the limit is raised or the month ends.
- **Near the budget** or **Near the cap**: most of the month's limit is used.
- **Unavailable cost**: a rate is missing or a measurement is unverified, so the real spend can be higher.

A tile can also show calls whose usage was not measured, such as `Up to $0.42 more from 3 calls whose usage was not measured`.
The limit counts this amount, but the spend on the tile does not.

**By use** shows the spend and **Avg per use** of each purse.
A use is one review attempt or one conversation answer.
**By day** shows the spend of each purse for each day.

## Set up member onboarding

Workspace owners can ask members to choose their AI and connect required accounts on their first visit.

1. Assign a model to each purpose and tier that your members need.
2. Under **Administration**, open **Member onboarding**.
3. Optional: under **Required account links**, select the Slack or Outline accounts that members must connect.
4. Turn on **Ask members to set up on their first visit**.
5. Select **Save onboarding settings**.

Members see the setup page once, and they can finish it later from **Your AI choice** in the sidebar.
A member who answered in another workspace is asked only to connect the required accounts.
An unavailable account link never holds a member up.

:::caution

Once this is on, every member must choose before AI runs for them.
Turning it off later does not undo that.

:::

To make a required link optional, clear it under **Required account links**.
New models, new providers, and new declarations never ask members again.

## Replace a model

When a provider withdraws a model, assign its replacement before you remove it.
For a shared model, your instance admin adds the replacement, as [Replace a model](/admin/ai-providers#replace-an-unavailable-model) describes.

1. For your own provider, add the replacement model, set its price and **Operated by**, and make it active.
2. Assign the replacement in each assignment that used the old model.
3. For your own provider, turn off the old model, or delete it.

Earlier feedback and conversations stay.
