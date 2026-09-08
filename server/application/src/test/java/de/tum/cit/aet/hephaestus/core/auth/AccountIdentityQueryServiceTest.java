package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AccountIdentityQueryServiceTest {
    @Mock
    private IdentityLinkRepository links;

    @Mock
    private AccountRepository accounts;

    @InjectMocks
    private AccountIdentityQueryService query;

    @Test
    void shouldReturnEveryLinkedSubjectAcrossBoundedQueriesWithoutMatchingNames() {
        Set<String> subjects =
                LongStream.rangeClosed(1, 1005).mapToObj(String::valueOf).collect(Collectors.toSet());
        when(links.findActiveByProviderSubjects(eq(9L), anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(1);
            assertThat(batch.size()).isLessThanOrEqualTo(1000);
            return batch.stream()
                    .map(subject -> {
                        Account account = new Account("Same display name");
                        account.setId(Long.valueOf(subject));
                        IdentityLink link = new IdentityLink();
                        link.setAccount(account);
                        link.setProviderId(9L);
                        link.setSubject(subject);
                        return link;
                    })
                    .toList();
        });
        var resolved = query.accountsForSubjects(9L, subjects);
        assertThat(resolved.keySet()).containsExactlyInAnyOrderElementsOf(subjects);
        assertThat(resolved)
                .hasEntrySatisfying("1005", value -> assertThat(value.id()).isEqualTo(1005L));
        assertThat(resolved)
                .hasEntrySatisfying("1", value -> assertThat(value.id()).isEqualTo(1L));
        assertThat(resolved.values()).allMatch(value -> value.active());
    }

    @Test
    void shouldReturnEmptyInventoryForAnEmptySetOfAccountsAndSubjects() {
        assertThat(query.accounts(Set.of())).isEmpty();
        assertThat(query.accountsForSubjects(9L, Set.of())).isEmpty();
    }

    @Test
    void shouldKeepInactiveAccountsExplicitRatherThanOfferingTheirIdentityForAdmission() {
        Account account = new Account("Deleted account");
        account.setId(8L);
        account.setStatus(Account.Status.DELETED);
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setSubject("subject");
        link.setProviderId(9L);
        when(links.findActiveByProviderSubjects(9L, List.of("subject"))).thenReturn(List.of(link));
        when(accounts.findAllById(List.of(8L))).thenReturn(List.of(account));
        assertThat(query.accountsForSubjects(9L, Set.of("subject")))
                .hasEntrySatisfying(
                        "subject", value -> assertThat(value.active()).isFalse());
        assertThat(query.accounts(Set.of(8L)))
                .hasEntrySatisfying(8L, value -> assertThat(value.active()).isFalse());
    }
}
