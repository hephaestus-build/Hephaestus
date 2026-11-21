package de.tum.cit.aet.hephaestus.core.database.backup;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The backup schedules of the bundled PostgreSQL, which the server reads to report how old the newest backup is. The
 * properties bind on every start, also without backups, so both may be empty here. The server reads the schedules only
 * when the names are set, and KubernetesBackupScheduleConfiguration then refuses an empty list or a blank namespace.
 *
 * @param namespace The namespace of the backup CronJobs. The chart sets it to the release namespace with backups on.
 * @param cronJobs Names of the backup CronJobs, comma-separated. The chart sets them with backups on; unset, the server
 *     reads no schedule and reports no backup age.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.database.backup", ignoreUnknownFields = false)
public record BackupScheduleProperties(
        @DefaultValue("") @Pattern(regexp = "|[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")
        String namespace,

        @DefaultValue({}) List<@Pattern(regexp = "[a-z0-9](?:[-a-z0-9]{0,250}[a-z0-9])?") String> cronJobs) {}
