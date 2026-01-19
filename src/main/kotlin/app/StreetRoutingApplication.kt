package app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["app", "domain", "solver", "rest"])
@EntityScan(basePackages = ["domain", "solver"])
class StreetRoutingApplication

fun main(args: Array<String>) {
    runApplication<StreetRoutingApplication>(*args)
}
