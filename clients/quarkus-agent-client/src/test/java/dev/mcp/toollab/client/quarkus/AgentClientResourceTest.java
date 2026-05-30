package dev.mcp.toollab.client.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class AgentClientResourceTest {
    @Test
    void startupHealthEndpointDoesNotRequireLiveMcpServer() {
        given()
          .when().get("/agent-demo/health")
          .then()
             .statusCode(200)
             .body(is("tool-lab-agent-client-ready"));
    }

}
