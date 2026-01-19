package rest

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Street Routing Optimizer API")
                    .version("1.0.0")
                    .description(
                        """
                        REST API for solving street routing optimization problems using Timefold Solver.
                        
                        ## Problem Description
                        Given a set of street segments (edges) that must or may be visited by vehicles:
                        - **Mandatory segments**: Must be visited by exactly one vehicle
                        - **Optional segments**: May be visited if beneficial (value vs travel cost)
                        - **Vehicles**: Have starting locations and optional range limits
                        
                        ## Workflow
                        1. POST a problem to `/routing` to start solving
                        2. Use the returned job ID to check status and get solutions
                        3. Stop solving early with POST to `/{jobId}/stop` if needed
                        4. Delete completed jobs with DELETE `/{jobId}`
                        
                        ## Score Explanation
                        - **Hard score**: Violations of mandatory constraints (lower is worse)
                        - **Medium score**: Benefits from optional segments (higher is better)
                        - **Soft score**: Optimization criteria like total distance (closer to 0 is better)
                        """.trimIndent()
                    )
                    .license(
                        License()
                            .name("Apache 2.0")
                            .url("https://www.apache.org/licenses/LICENSE-2.0")
                    )
            )
    }
}
