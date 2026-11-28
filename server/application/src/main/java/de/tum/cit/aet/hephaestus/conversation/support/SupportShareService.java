package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory.AccountView;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The person's act that lets one instance admin read one of their turns (ADR 0164). Staff have no other path to a
 * person's conversation. The share copies no text: each read renders the turn from its current rows and shows the
 * messages only while they still match the digest the person confirmed.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
// A share row names the account whose turn its recipient may read, so the read binds that account as the owner.
@OwnerScope.StoredOwnerOperation
@WorkspaceAgnostic("A share belongs to its person and its one recipient; each turn read binds the share's workspace")
public class SupportShareService {

    private final SupportShareRepository shares;
    private final ConversationTurnRepository turns;
    private final ConversationEntryRepository entries;
    private final AccountDirectory accounts;
    private final WorkspaceQueries workspaces;
    private final OwnerScope owners;
    private final Clock clock;

    /** Exactly what the recipient would read. */
    @Transactional(readOnly = true)
    public SupportSharePreviewDTO preview(long workspaceId, PersonAct act, UUID turnId, boolean includeText) {
        long accountId = owners.fromPrincipal(act);
        ConversationTurn turn = ownTurn(workspaceId, accountId, turnId);
        List<SupportShareMessageDTO> messages = includeText ? messages(workspaceId, accountId, turnId) : List.of();
        return new SupportSharePreviewDTO(
                SupportShareTurnDTO.of(turn, slug(workspaceId)),
                messages,
                includeText ? digest(messages) : null,
                accounts.activeInstanceAdmins().stream()
                        .map(SupportShareService::recipient)
                        .toList(),
                (int) SupportShare.VALIDITY.toDays());
    }

    /** Shares the turn that the person previewed. The text must be what the preview showed. */
    @Transactional
    public SupportShareDTO share(long workspaceId, PersonAct act, UUID turnId, SupportShareRequestDTO request) {
        long accountId = owners.fromPrincipal(act);
        ownTurn(workspaceId, accountId, turnId);
        AccountView recipient = accounts.activeInstanceAdmins().stream()
                .filter(admin -> Long.toString(admin.id()).equals(request.recipientAccountId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Choose one of the instance admins as the recipient."));
        long recipientId = recipient.id();
        String digest = null;
        if (request.includeText()) {
            digest = digest(messages(workspaceId, accountId, turnId));
            if (!digest.equals(request.textDigest())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "The messages changed after the preview. Look at the preview again.");
            }
        }
        SupportShare share =
                shares.save(new SupportShare(accountId, recipientId, workspaceId, turnId, digest, clock.instant()));
        return own(share, Map.of(recipientId, recipient), slugs(List.of(share)));
    }

    /** The person's own shares: every active one, then the latest ended ones, with each read the recipient made. */
    @Transactional(readOnly = true)
    public List<SupportShareDTO> ownShares(long accountId) {
        Instant now = clock.instant();
        List<SupportShare> list = new ArrayList<>(shares.findActiveOfAccount(accountId, now));
        list.addAll(shares.findRecentEndedOfAccount(accountId, now));
        Map<Long, AccountView> recipients = accounts.findAll(
                list.stream().map(SupportShare::getRecipientAccountId).collect(Collectors.toSet()));
        Map<Long, String> slugs = slugs(list);
        return list.stream().map(share -> own(share, recipients, slugs)).toList();
    }

    /** Ends the share now. It stays in the person's list as revoked. */
    @Transactional
    public SupportShareDTO revoke(long accountId, UUID shareId) {
        shares.revoke(shareId, accountId, clock.instant());
        SupportShare share = shares.findByIdAndAccountId(shareId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("SupportShare", shareId.toString()));
        return own(share, accounts.findAll(List.of(share.getRecipientAccountId())), slugs(List.of(share)));
    }

    /** The shares the instance admin can read now, without their turns or messages. */
    @Transactional(readOnly = true)
    public List<SupportShareReceivedDTO> received(long recipientId) {
        List<SupportShare> list = shares.findReadableBy(recipientId, clock.instant());
        Map<Long, AccountView> people =
                accounts.findAll(list.stream().map(SupportShare::getAccountId).collect(Collectors.toSet()));
        Map<Long, String> slugs = slugs(list);
        return list.stream()
                .filter(share -> person(people, share).isPresent())
                .map(share -> received(share, person(people, share).orElseThrow(), slugs, null, List.of(), false))
                .toList();
    }

    /**
     * One read of the share by its recipient. It counts the read in the statement that checks the recipient, the
     * revocation and the expiry. The messages show only while they match what the person confirmed.
     */
    @Transactional
    public SupportShareReceivedDTO read(long recipientId, UUID shareId) {
        Instant now = clock.instant();
        if (shares.markRead(shareId, recipientId, now) == 0) throw notFound(shareId);
        SupportShare share = shares.findById(shareId).orElseThrow(() -> notFound(shareId));
        AccountView person =
                person(accounts.findAll(List.of(share.getAccountId())), share).orElseThrow(() -> notFound(shareId));
        owners.ofJob(share.getAccountId());
        Map<Long, String> slugs = slugs(List.of(share));
        var turn = turns.findByIdAndWorkspaceId(share.getTurnId(), share.getWorkspaceId())
                .filter(found -> Objects.equals(found.getAccountId(), share.getAccountId()))
                .map(found -> SupportShareTurnDTO.of(found, slugs.getOrDefault(share.getWorkspaceId(), "")))
                .orElse(null);
        List<SupportShareMessageDTO> messages = List.of();
        boolean withheld = false;
        if (share.includesText()) {
            var current = messages(share.getWorkspaceId(), share.getAccountId(), share.getTurnId());
            if (digest(current).equals(share.getTextDigest())) messages = current;
            else withheld = true;
        }
        return received(share, person, slugs, turn, messages, withheld);
    }

    private ConversationTurn ownTurn(long workspaceId, long accountId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn -> turn.getAccountId() == accountId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationTurn", turnId.toString()));
    }

    private List<SupportShareMessageDTO> messages(long workspaceId, long accountId, UUID turnId) {
        return entries.findTurnMessages(workspaceId, accountId, turnId).stream()
                .map(message -> new SupportShareMessageDTO(
                        message.id(),
                        message.kind() == ConversationEntry.Kind.USER
                                ? SupportShareMessageDTO.Author.QUESTION
                                : SupportShareMessageDTO.Author.ANSWER,
                        message.text(),
                        message.createdAt()))
                .toList();
    }

    /** The SHA-256 of the messages: each entry id and text, length-prefixed, in order. */
    static String digest(List<SupportShareMessageDTO> messages) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
        for (var message : messages) {
            for (String part : List.of(message.entryId().toString(), message.text())) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                sha.update((byte) ':');
                sha.update(bytes);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private String slug(long workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(WorkspaceQueries.ReviewWorkspace::slug)
                .orElse("");
    }

    /** One lookup for each workspace that the shares name. */
    private Map<Long, String> slugs(List<SupportShare> list) {
        return list.stream()
                .map(SupportShare::getWorkspaceId)
                .distinct()
                .collect(Collectors.toMap(id -> id, this::slug));
    }

    private SupportShareDTO own(SupportShare share, Map<Long, AccountView> recipients, Map<Long, String> slugs) {
        Instant now = clock.instant();
        var state = share.getRevokedAt() != null
                ? SupportShareDTO.State.REVOKED
                : share.readable(now) ? SupportShareDTO.State.ACTIVE : SupportShareDTO.State.EXPIRED;
        AccountView recipient = recipients.get(share.getRecipientAccountId());
        return new SupportShareDTO(
                share.getId(),
                share.getTurnId(),
                slugs.getOrDefault(share.getWorkspaceId(), ""),
                new SupportShareRecipientDTO(
                        Long.toString(share.getRecipientAccountId()),
                        recipient == null || recipient.erased() ? "" : recipient.displayName()),
                share.includesText(),
                state,
                share.getCreatedAt(),
                share.getExpiresAt(),
                share.getRevokedAt(),
                share.getReadCount(),
                share.getLastReadAt());
    }

    private SupportShareReceivedDTO received(
            SupportShare share,
            AccountView person,
            Map<Long, String> slugs,
            @Nullable SupportShareTurnDTO turn,
            List<SupportShareMessageDTO> messages,
            boolean withheld) {
        return new SupportShareReceivedDTO(
                share.getId(),
                person.displayName(),
                slugs.getOrDefault(share.getWorkspaceId(), ""),
                share.includesText(),
                share.getCreatedAt(),
                share.getExpiresAt(),
                turn,
                messages,
                withheld);
    }

    /** The person who shared, unless their account is erased: an erased person's share is never read. */
    private static Optional<AccountView> person(Map<Long, AccountView> people, SupportShare share) {
        return Optional.ofNullable(people.get(share.getAccountId())).filter(person -> !person.erased());
    }

    private static SupportShareRecipientDTO recipient(AccountView admin) {
        return new SupportShareRecipientDTO(Long.toString(admin.id()), admin.displayName());
    }

    private static EntityNotFoundException notFound(UUID shareId) {
        return new EntityNotFoundException("SupportShare", shareId.toString());
    }
}
