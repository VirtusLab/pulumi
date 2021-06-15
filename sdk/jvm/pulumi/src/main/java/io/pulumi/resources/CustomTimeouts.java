package io.pulumi.resources;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Optional;

/** Optional timeouts to supply in @see {@link ResourceOptions#getCustomTimeouts()} */
public final class CustomTimeouts {
    @Nullable
    private final Duration create;
    @Nullable
    private final Duration update;
    @Nullable
    private final Duration delete;

    public CustomTimeouts(@Nullable Duration create, @Nullable Duration update, @Nullable Duration delete) {
        this.create = create;
        this.update = update;
        this.delete = delete;
    }

    /** The optional create timeout */
    public Optional<Duration> getCreate() {
        return Optional.ofNullable(create);

    }

    /** The optional update timeout.*/
    public Optional<Duration> getUpdate() {
        return Optional.ofNullable(update);
    }

    /* The optional delete timeout. */
    public Optional<Duration> getDelete() {
        return Optional.ofNullable(delete);
    }

    @Nullable
    public static CustomTimeouts copy(@Nullable CustomTimeouts timeouts) {
        return timeouts == null ? null : new CustomTimeouts(timeouts.create, timeouts.update, timeouts.delete);
    }
}
