package org.adaway.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/**
 * This entity represents a domain blocked during a day.
 * <p>
 * It is the deduplication ledger used to count distinct blocked domains: a domain is only counted
 * once per day, even across application restarts. Rows of past days are pruned, only the
 * {@link BlockedStat} aggregate is kept.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Entity(tableName = "blocked_domains", primaryKeys = {"day", "domain"})
public class BlockedDomain {
    @NonNull
    private String day;
    @NonNull
    private String domain;

    public BlockedDomain(@NonNull String day, @NonNull String domain) {
        this.day = day;
        this.domain = domain;
    }

    @NonNull
    public String getDay() {
        return day;
    }

    public void setDay(@NonNull String day) {
        this.day = day;
    }

    @NonNull
    public String getDomain() {
        return domain;
    }

    public void setDomain(@NonNull String domain) {
        this.domain = domain;
    }
}
