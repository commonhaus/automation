package org.commonhaus.automation;

import io.quarkus.vertx.web.Route;

public class Ping {

    @Route(path = "/ping")
    String helloSync() {
        return "Hello world";
    }
}
