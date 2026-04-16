import src.core.ReturnRequest;
import src.service.RMAGatewayFacade;

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
