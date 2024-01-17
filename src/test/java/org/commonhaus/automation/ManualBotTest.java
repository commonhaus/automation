package org.commonhaus.automation;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ManualBotTest {
    
    @Test
    @Order(1)
    public void testSetup() {
        given()
          .when().get("/setup")
          .then()
             .statusCode(200)
             .body(is("done"));
    }

    @Test
    @Order(2)
    public void testDiscussionReaction() {
        given()
          .when().get("/reaction")
          .then()
             .statusCode(200)
             .body(is("done"));
    }
}
