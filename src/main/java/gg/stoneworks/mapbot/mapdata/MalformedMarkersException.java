package gg.stoneworks.mapbot.mapdata;

/**
 * The payload parsed as JSON but is not a marker feed we recognise.
 *
 * <p>Distinct from returning an empty list on purpose. No claims and a missing Lands layer look
 * identical to a differ, which would read a broken payload as every land on the server being
 * deleted at once. Failing here means the poll cycle is skipped and the previous snapshot stands.
 */
public class MalformedMarkersException extends Exception {

    public MalformedMarkersException(String message) {
        super(message);
    }
}
