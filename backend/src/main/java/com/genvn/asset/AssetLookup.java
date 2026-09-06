package com.genvn.asset;

import java.util.Optional;

/** Read-only view of a session's asset manifest, for resolution during scene generation. */
public interface AssetLookup {
    Optional<AssetManifest> snapshot(String sessionId);

    AssetLookup NONE = sessionId -> Optional.empty();
}
