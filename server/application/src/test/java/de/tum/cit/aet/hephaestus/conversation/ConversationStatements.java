package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import java.util.List;
import java.util.regex.Pattern;

/** The measured statements that read or write a table which the conversation's owner policy guards. */
final class ConversationStatements {
    private static final Pattern TABLES =
            Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+(?:public\\.)?conversation(?:_entry|_turn)?\\b");

    private ConversationStatements() {}

    static List<SqlStatementCounter.Prepared> of(SqlStatementCounter.Measurement<?> measured) {
        return measured.prepared().stream()
                .filter(statement -> TABLES.matcher(statement.sql()).find())
                .toList();
    }
}
