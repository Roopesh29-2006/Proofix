package com.quickcart.api;

import com.quickcart.common.Paths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

/** A controller whose name does not end in "Controller". */
@RestController
public class OrderApi {

    @GetMapping(Paths.ORDERS)
    public Object list() {
        return null;
    }

    @GetMapping(buildPath())
    public Object dynamic() {
        return null;
    }

    @RequestMapping(value = {"/a", "/b"}, method = {GET, POST})
    public Object multi() {
        return null;
    }

    @RequestMapping("/x")
    public Object any() {
        return null;
    }

    static String buildPath() {
        return "/dyn";
    }
}
