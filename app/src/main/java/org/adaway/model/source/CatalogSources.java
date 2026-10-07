package org.adaway.model.source;

import org.adaway.R;

import java.util.List;

/**
 * This class provides the static catalog of recommended hosts sources.<br>
 * The catalog is a curated list of well-known and maintained hosts files the user can
 * subscribe to. It is intentionally shipped with the application (no remote fetch) to avoid
 * any supply-chain risk.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class CatalogSources {
    /**
     * The recommended hosts sources, ordered by category.
     */
    private static final List<CatalogSource> SOURCES = List.of(
            // General purpose ad blocking
            new CatalogSource(
                    "https://adaway.org/hosts.txt",
                    R.string.hosts_adaway_source,
                    R.string.catalog_source_adaway_description,
                    R.string.catalog_category_general
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                    R.string.hosts_stevenblack_source,
                    R.string.catalog_source_stevenblack_description,
                    R.string.catalog_category_general
            ),
            new CatalogSource(
                    "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
                    R.string.hosts_peterlowe_source,
                    R.string.catalog_source_peterlowe_description,
                    R.string.catalog_category_general
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/hosts",
                    R.string.catalog_source_easylist_adservers,
                    R.string.catalog_source_easylist_adservers_description,
                    R.string.catalog_category_general
            ),
            // Privacy and tracking
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyPrivacy/hosts",
                    R.string.catalog_source_easyprivacy,
                    R.string.catalog_source_easyprivacy_description,
                    R.string.catalog_category_privacy
            ),
            // Regional lists
            new CatalogSource(
                    "https://raw.githubusercontent.com/easylist/EasyListHebrew/master/hosts.txt",
                    R.string.catalog_source_hebrew,
                    R.string.catalog_source_hebrew_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyListHebrew+EasyList/hosts",
                    R.string.catalog_source_hebrew_easylist,
                    R.string.catalog_source_hebrew_easylist_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyListGermany+EasyList/hosts",
                    R.string.catalog_source_germany,
                    R.string.catalog_source_germany_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/ListeFR+EasyList/hosts",
                    R.string.catalog_source_french,
                    R.string.catalog_source_french_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyListItaly+EasyList/hosts",
                    R.string.catalog_source_italy,
                    R.string.catalog_source_italy_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyListDutch+EasyList/hosts",
                    R.string.catalog_source_dutch,
                    R.string.catalog_source_dutch_description,
                    R.string.catalog_category_regional
            ),
            new CatalogSource(
                    "https://raw.githubusercontent.com/ProgramComputer/Easylist_hosts/main/EasyListChina+EasyList/hosts",
                    R.string.catalog_source_china,
                    R.string.catalog_source_china_description,
                    R.string.catalog_category_regional
            )
    );

    /**
     * Private constructor of utility class.
     */
    private CatalogSources() {
    }

    /**
     * Get the recommended hosts sources.
     *
     * @return The recommended hosts sources, ordered by category.
     */
    public static List<CatalogSource> getSources() {
        return SOURCES;
    }
}
