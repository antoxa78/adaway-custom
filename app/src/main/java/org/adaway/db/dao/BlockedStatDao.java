package org.adaway.db.dao;

import static androidx.room.OnConflictStrategy.IGNORE;
import static androidx.room.OnConflictStrategy.REPLACE;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import org.adaway.db.entity.BlockedDomain;
import org.adaway.db.entity.BlockedStat;

/**
 * This interface is the DAO for the blocked domains statistics.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Dao
public interface BlockedStatDao {
    /**
     * Record a blocked domain for the given day.
     * <p>
     * The domain is only counted the first time it is blocked during the day, even across
     * application restarts: the insert is ignored when the domain is already in the ledger.
     *
     * @param day    The day (ISO-8601 date).
     * @param domain The blocked domain.
     */
    @Transaction
    default void recordBlocked(String day, String domain) {
        if (insertDomain(new BlockedDomain(day, domain)) != -1) {
            if (incrementStat(day) == 0) {
                insertStat(new BlockedStat(day, 1));
            }
        }
    }

    /**
     * Insert a blocked domain in the deduplication ledger.
     *
     * @param domain The blocked domain.
     * @return The inserted row id, or {@code -1} if the domain was already recorded.
     */
    @Insert(onConflict = IGNORE)
    long insertDomain(BlockedDomain domain);

    /**
     * Increment the blocked domains count of a day.
     *
     * @param day The day (ISO-8601 date).
     * @return The number of updated rows.
     */
    @Query("UPDATE blocked_stats SET count = count + 1 WHERE day = :day")
    int incrementStat(String day);

    /**
     * Insert a blocked domains count for a day.
     *
     * @param stat The count to insert.
     */
    @Insert(onConflict = REPLACE)
    void insertStat(BlockedStat stat);

    /**
     * Prune the deduplication ledger rows older than the given day.
     *
     * @param day The first day to keep (ISO-8601 date).
     */
    @Query("DELETE FROM blocked_domains WHERE day < :day")
    void pruneDomainsBefore(String day);

    /**
     * Get the number of distinct domains blocked during a day.
     *
     * @param day The day (ISO-8601 date).
     * @return The number of distinct domains blocked during the day.
     */
    @Query("SELECT COALESCE((SELECT count FROM blocked_stats WHERE day = :day), 0)")
    LiveData<Integer> getDayCount(String day);

    /**
     * Get the number of distinct domains blocked between two days (both inclusive).
     *
     * @param from The first day (ISO-8601 date).
     * @param to   The last day (ISO-8601 date).
     * @return The number of distinct domains blocked between the two days.
     */
    @Query("SELECT COALESCE((SELECT SUM(count) FROM blocked_stats WHERE day BETWEEN :from AND :to), 0)")
    LiveData<Integer> getCountBetween(String from, String to);

    /**
     * Get the total number of distinct domains blocked since the statistics exist.
     *
     * @return The total number of distinct domains blocked.
     */
    @Query("SELECT COALESCE((SELECT SUM(count) FROM blocked_stats), 0)")
    LiveData<Integer> getTotalCount();
}
