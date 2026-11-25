package de.tum.cit.aet.hephaestus.architecture;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

final class AccountOwnedWriteAdmissionFixtures {
    private static final String EXPORT_TABLE = "account_export";
    private static final String EXPORT_ENTITY = "AccountExport";

    private AccountOwnedWriteAdmissionFixtures() {}

    @SuppressWarnings({"unused", "UnusedMethod"}) // Read by ArchUnit only.
    interface ConstantQueries extends Repository<Account, Long> {
        @Modifying
        @Query(value = "UPDATE " + EXPORT_TABLE + " SET payload = NULL", nativeQuery = true)
        int nativeUpdate();

        @Modifying
        @NativeQuery("DELETE FROM " + EXPORT_TABLE)
        int nativeDelete();

        @Modifying
        @Query("UPDATE " + EXPORT_ENTITY + " e SET e.status = :status")
        int jpqlUpdate(String status);
    }

    @SuppressWarnings({"unused", "UnusedMethod"}) // Read by ArchUnit only.
    static final class CriteriaWrites {
        int rewritePayload(EntityManager entities, byte[] payload) {
            var update = entities.getCriteriaBuilder().createCriteriaUpdate(AccountExport.class);
            update.from(AccountExport.class);
            update.set("payload", payload);
            return entities.createQuery(update).executeUpdate();
        }

        int deleteExports(EntityManager entities) {
            var delete = entities.getCriteriaBuilder().createCriteriaDelete(AccountExport.class);
            delete.from(AccountExport.class);
            return entities.createQuery(delete).executeUpdate();
        }

        long readExports(EntityManager entities) {
            var builder = entities.getCriteriaBuilder();
            var query = builder.createQuery(Long.class);
            query.select(builder.count(query.from(AccountExport.class)));
            return entities.createQuery(query).getSingleResult();
        }
    }
}
