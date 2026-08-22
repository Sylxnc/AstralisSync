package com.sylxnc.astralis.sync.api;

import com.sylxnc.astralis.sync.Main;

/** Internal bridge creating the API implementation. */
public final class ApiFactory {

    private ApiFactory() {
    }

    public static AstralisSyncApi create(Main plugin) {
        return new ApiImpl(plugin);
    }
}
