package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Entity
@PersonOwned
@Table(name = "personal_goal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PersonalGoal {
    static final int MAX_WORDS_LENGTH = 500;

    @Id
    private Long accountId;

    @Column(unique = true)
    private @Nullable UUID goalId;

    @Column(length = MAX_WORDS_LENGTH)
    private @Nullable String words;

    private long revision;
}
