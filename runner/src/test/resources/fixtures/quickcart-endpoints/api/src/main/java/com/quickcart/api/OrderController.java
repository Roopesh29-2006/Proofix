package com.quickcart.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/")
public class OrderController {

    @GetMapping("/{id}")
    public Object getOrder(@PathVariable long id) {
        return null;
    }

    @PostMapping
    public Object createOrder() {
        return null;
    }

    @GetMapping("/{id:\\d+}/items")
    public Object items(@PathVariable long id) {
        return null;
    }
}
