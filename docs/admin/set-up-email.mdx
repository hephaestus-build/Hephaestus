---
title: Set up email
description: Connect your instance to an SMTP relay with the Helm chart, send a test email, and prepare the sender domain.
page_type: how-to
---

Connect your instance to one SMTP relay so that Hephaestus can send email.
Account-security and account-deletion confirmations are essential mail.
Other mail, such as connection alerts and the weekly digest, is optional, and each person chooses it in their settings.
Hephaestus runs without email: then it sends no mail, and every operation still completes.

## Before you start

- An installed instance ([Install on Kubernetes](./kubernetes)).
- An SMTP relay on a public address, and its account.
- A sender address that the relay may send for.
- The review of the relay operator in the [processor checklist](./dsms/processor-checklist#follow-up-if-the-processing-surface-changes).
  The relay receives each address and the fact that its owner has an account.
- `kubectl` with access to the release namespace, and `jq`.

The commands use the release namespace `hephaestus` and the release Secret `hephaestus-app`.
Replace these names if your release uses other names.

## Connect the relay

1. Optional: type the password of the relay account. The shell does not show it:

   ```bash
   read -rs SPRING_MAIL_PASSWORD && export SPRING_MAIL_PASSWORD
   ```

2. Optional: add the password to the release Secret:

   ```bash
   jq -n --arg password "$SPRING_MAIL_PASSWORD" '{stringData: {SPRING_MAIL_PASSWORD: $password}}' |
     kubectl -n hephaestus patch secret hephaestus-app --type merge --patch-file /dev/stdin
   ```

3. Add the relay to `site.yaml`:

   ```yaml title="values.yaml"
   config:
     email:
       host: smtp.example.com
       port: 587
       tls: starttls
       username: hephaestus@example.com
       from: hephaestus@example.com
       fromName: Hephaestus
       replyTo: support@example.com
   ```

   | Value | What it sets | Default |
   | --- | --- | --- |
   | `config.email.host` | The relay host. A host turns email on. | Empty |
   | `config.email.port` | The relay port | `587` |
   | `config.email.tls` | `starttls` upgrades the connection, usually on port 587. `implicit` uses TLS from the first byte, usually on port 465. | `starttls` |
   | `config.email.username` | The relay account. Empty connects without a login, for an internal relay. | Empty |
   | `config.email.from` | The sender address. Required with a host. | Empty |
   | `config.email.fromName` | The name beside the sender address | `Hephaestus` |
   | `config.email.replyTo` | The address that replies go to. Empty sends replies to the sender address. | Empty |

   Both TLS modes verify the certificate and the host name of the relay.

4. Apply `site.yaml`.
   On a cluster, run the `helm upgrade` command of [Install](./kubernetes#install) again.
   On one host, run the installer again ([Run the installer](./k3s#2-run-the-installer)).
   If you changed only the password, restart the server, as [Restart a role](./secret-custody#restart-a-role) describes.

With a host, the network policy lets the server reach `config.email.port` on the addresses of `networkPolicy.externalEgress`.
These leave out the private ranges, so the server cannot reach a relay on a private address.

## Prepare the sender domain

Receivers refuse or sort as junk the mail of a sender that its domain does not authorize.

1. Ask the relay operator which sender domains it signs.
2. Set `config.email.from` to an address in one of those domains.
3. Align SPF, DKIM and DMARC with the sender domain.
4. Make the DKIM signature of the relay include the `List-Unsubscribe` and `List-Unsubscribe-Post` headers.

Optional mail has an unsubscribe link and the one-click unsubscribe of [RFC 8058](https://www.rfc-editor.org/rfc/rfc8058).
Large mail providers require both, as [Google's sender requirements](https://support.google.com/a/answer/81126?hl=en) state.

## Check that it worked

1. Open **Instance administration → Instance settings**.
2. Under **Email**, enter a **Recipient**, or leave it empty to use the address of your account.
3. Select **Send test email**.
   The result shows **Handed to mail server** and the message ID.
4. Find the message ID in the log of the relay.

**Handed to mail server** means that the relay took the message, not that it reached the inbox.
Other results name their cause:

| Result | Cause |
| --- | --- |
| **Withheld by silent mode** | [Silent mode](./instance-admin#silent-mode) is on, as on a new instance. |
| **Not configured** | The host or the sender address is empty. |
| **No recipient** | You left **Recipient** empty, and your account has no provider-verified email address. Enter a recipient. |
| **Address not valid** | **Recipient** is not one valid email address. |
| **Mail server unavailable** | The server cannot reach the relay, or the relay refuses the login. Check the host, port, TLS mode, account and network policy. |
| **Rejected by mail server** | The relay refuses the recipient. |
| **Not confirmed** | The relay may have taken the message without a confirmation. Look for it in the relay log before you send another test. |
| **Rate limited** | The instance used its attempt budget for the day, or it could not check it. The test email can still go out within two minutes. |
| **Not subscribed** | The recipient has not opted in to this optional email. |
| **Expired** | The email was too old to be useful when its turn came, so Hephaestus did not send it. |

[Configuration readiness](./configuration-readiness#email) reports **Action required** while a host is set without a sender address.

## Undo

1. Remove `config.email.host` from `site.yaml`.
2. Apply `site.yaml`.

Hephaestus then sends no mail, and each email completes as **Not configured**.
The email choices of each person stay, and apply again when you set a host again.

## Sending limits

Hephaestus makes at most 250 SMTP attempts in 24 hours, and at most 200 of them for optional mail.
The other 50 stay for essential mail.
An attempt counts also when the relay refuses it.
The chart has no value that changes these limits.
If your relay account allows fewer messages, or other applications share the account, ask the relay operator for a higher limit.

Each email is sent at most once.
If the connection breaks after the relay took the message, Hephaestus does not send it again.
