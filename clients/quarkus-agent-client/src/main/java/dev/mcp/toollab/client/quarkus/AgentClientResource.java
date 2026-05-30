package dev.mcp.toollab.client.quarkus;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/agent-demo")
public class AgentClientResource {
    private final AgentDemoService demoService;

    public AgentClientResource(AgentDemoService demoService) {
        this.demoService = demoService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<AgentTranscript> run(@QueryParam("scenario") @DefaultValue("all") String scenario) {
        return demoService.run(scenario);
    }

    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String health() {
        return "tool-lab-agent-client-ready";
    }
}
