package org.adaway.model.adblocking;

/**
 * This enum represents the ad blocking state of the application.
 * <p>
 * It refines {@link AdBlockModel#isApplied()} with the transient states where ad blocking is
 * active but not actually blocking yet (starting, waiting for a network or reconnecting).
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public enum AdBlockState {
    /**
     * Ad blocking is stopped.
     */
    STOPPED,
    /**
     * Ad blocking is starting (the tunnel is not established yet).
     */
    STARTING,
    /**
     * Ad blocking is running (the tunnel is established).
     */
    RUNNING,
    /**
     * Ad blocking is waiting for a network to become available.
     */
    WAITING_FOR_NETWORK,
    /**
     * Ad blocking is reconnecting (the tunnel is broken or being rebuilt).
     */
    RECONNECTING;

    /**
     * Check if ad blocking is active.
     *
     * @return {@code true} if ad blocking is active (even if it is not running yet), {@code false} if stopped.
     */
    public boolean isActive() {
        return this != STOPPED;
    }
}
