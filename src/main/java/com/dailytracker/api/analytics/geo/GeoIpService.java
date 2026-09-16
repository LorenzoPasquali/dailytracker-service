package com.dailytracker.api.analytics.geo;

import java.util.Optional;

/** IP to ISO 3166-1 alpha-2 country. Implementations must never throw; unknown = empty. */
public interface GeoIpService {
    Optional<String> lookupCountry(String ip);
}
