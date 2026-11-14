package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import org.jspecify.annotations.Nullable;

/** Live member choices, shared by review admission, conversation admission and the model proxy. */
public interface MemberAiPreferences {
    Decision forDeveloper(long workspaceId, @Nullable Long developerId);

    record Decision(boolean choiceRequired, @Nullable AiChoice choice) {
        public boolean permitsAi() {
            return choice != AiChoice.NO_AI && (!choiceRequired || choice != null);
        }
    }
}
