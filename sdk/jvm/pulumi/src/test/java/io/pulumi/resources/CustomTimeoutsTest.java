package io.pulumi.resources;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class CustomTimeoutsTest {

    @Test
    void testHashCodeEqualsContract() {
        EqualsVerifier.forClass(CustomTimeouts.class).verify();
    }

}