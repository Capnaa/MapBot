package gg.stoneworks.mapbot.net;

import java.io.IOException;

/**
 * Where marker data comes from.
 *
 * <p>Exists so the poll cycle can be tested against scripted sequences (a restart returning claims
 * a few at a time, a 304 arriving mid-sequence, a truncated payload as the very first fetch) that
 * cannot be reproduced on demand against a live map.
 */
@FunctionalInterface
public interface MarkersSource {

    /**
     * @param previousEtag validator from the last response, or {@code null} to force a full transfer
     * @throws MapOfflineException if the source served the offline page rather than data
     */
    MarkersResponse fetch(String previousEtag) throws IOException, MapOfflineException;
}
