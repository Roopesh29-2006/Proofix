package com.quickcart.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Broken {
    private static final String STRIPE_SECRET = "sk_live___CANARY__";

    @GetMapping("/broken")
    public Object broken( {
        return STRIPE_SECRET;
    }
}
