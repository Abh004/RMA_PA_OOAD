import src.core.ReturnRequest;
import src.service.RMAGatewayFacade;

/**
 * Entry point for the RMA subsystem.
 *
 * COMPILE (non-Maven):
 *   javac -cp "lib/database-module-1.0.0-SNAPSHOT-standalone.jar:." \
 *         SubsystemRunner.java src/service/*.java src/core/*.java src/patterns/*.java
 *
 * RUN (non-Maven):
 *   java -cp "lib/database-module-1.0.0-SNAPSHOT-standalone.jar:." SubsystemRunner
 *
 * On Windows replace ':' with ';' in the classpath.
 *
 * Maven: add the dependency from INTEGRATION.md to pom.xml, then:
 *   mvn compile exec:java -Dexec.mainClass="SubsystemRunner"
 */
public class SubsystemRunner {

    public static void main(String[] args) {
        RMAGatewayFacade rma = new RMAGatewayFacade();

        // Simulate a return
        ReturnRequest req = new ReturnRequest(
            "ORD-001",
            "PROD-001",
            "Electronics",
            10,
            "Device stopped working after two days.",
            1200.00
        );

        rma.processReturn(req, "Faulty", "Internal circuit failure suspected.");
    }
}
