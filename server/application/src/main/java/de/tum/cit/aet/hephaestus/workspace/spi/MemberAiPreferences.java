package de.tum.cit.aet.hephaestus.workspace.spi;

import org.jspecify.annotations.Nullable;

/** Live member choices, shared by review admission, mentor admission and the model proxy. */
public interface MemberAiPreferences {
    Decision forDeveloper(long workspaceId, @Nullable Long developerId);

    record Decision(boolean choiceRequired, @Nullable MemberAiChoice choice) {
        public boolean permitsAi() {
            return choice != MemberAiChoice.NO_AI && (!choiceRequired || choice != null);
        }
    }
}
