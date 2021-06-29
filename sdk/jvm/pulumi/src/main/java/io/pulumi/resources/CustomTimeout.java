package io.pulumi.resources;

import java.time.Duration;
import java.util.Optional;

public class CustomTimeout {
    Optional<Duration> create;
    Optional<Duration> update;
    Optional<Duration> delete;
    
    public CustomTimeout clone() {
        CustomTimeout clone = new CustomTimeout();
        clone.create = this.create;
        clone.update = this.update;
        clone.delete = this.update;
        return clone;
    }
}
