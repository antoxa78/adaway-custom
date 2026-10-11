package org.adaway.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * This entity represents the number of distinct domains blocked during a day.
 * <p>
 * Days are stored as ISO-8601 dates (for example <code>2026-10-11</code>) so they can be compared
 * and summed with plain SQLite string operators.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@Entity(tableName = "blocked_stats")
public class BlockedStat {
    @PrimaryKey
    @NonNull
    private String day;
    private int count;

    public BlockedStat(@NonNull String day, int count) {
        this.day = day;
        this.count = count;
    }

    @NonNull
    public String getDay() {
        return day;
    }

    public void setDay(@NonNull String day) {
        this.day = day;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
