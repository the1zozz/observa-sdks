package com.integralways.observa.sdk;

/** What the SDK did so far, for monitoring: events waiting, sent, rejected by Observa or by the SDK, dropped, retries. */
public final class Stats {

    private final long queued;
    private final long sent;
    private final long rejected;
    private final long dropped;
    private final long retried;

    Stats(long queued, long sent, long rejected, long dropped, long retried) {
        this.queued = queued;
        this.sent = sent;
        this.rejected = rejected;
        this.dropped = dropped;
        this.retried = retried;
    }

    /** Waiting to be sent now. */
    public long queued() {
        return queued;
    }

    /** Accepted by Observa. */
    public long sent() {
        return sent;
    }

    /** Refused, by the SDK's checks or by Observa; resending cannot help. */
    public long rejected() {
        return rejected;
    }

    /** Lost: the queue was full, or the SDK closed before they could be sent. */
    public long dropped() {
        return dropped;
    }

    /** Times a batch had to wait and be sent again. */
    public long retried() {
        return retried;
    }

    @Override
    public String toString() {
        return "queued=" + queued + " sent=" + sent + " rejected=" + rejected + " dropped=" + dropped + " retried=" + retried;
    }
}
