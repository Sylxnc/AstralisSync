package com.sylxnc.astralis.sync.api;

/**
 * Static registry for the AstralisSync API.
 * <p>
 * Usage from another plugin:
 * <pre>{@code
 * AstralisSyncApi api = ApiProvider.get();
 * if (api != null) {
 *     int rows = api.getEnderChestRows(player.getUniqueId());
 * }
 * }</pre>
 */
public final class ApiProvider {

    private static volatile AstralisSyncApi instance;

    private ApiProvider() {
    }

    /** The active API or null when AstralisSync is not installed/enabled. */
    public static AstralisSyncApi get() {
        return instance;
    }

    /** Called by the plugin on enable. */
    public static void register(AstralisSyncApi api) {
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }
}
