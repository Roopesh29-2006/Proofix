package com.quickcart.api;

import org.springframework.beans.factory.annotation.Value;

class Keys {
    @Value("${STRIPE_KEY}")
    String stripeKey;

    String dbPassword = System.getenv("DB_PASSWORD");

    String computed(String prefix) {
        return System.getenv(prefix + "_KEY");
    }
}
