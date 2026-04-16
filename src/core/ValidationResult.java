package src.core;

/**
 * GRASP: Information Expert.
 * Knows the outcome of a validation policy check.
 */
public class ValidationResult {

    public final boolean approved;
    public final String message;

    private ValidationResult(boolean approved, String message) {
        this.approved = approved;
        this.message = message;
    }

    public static ValidationResult approved() {
        return new ValidationResult(true, "Approved by Policy Engine");
    }

    public static ValidationResult rejected(String reason) {
        return new ValidationResult(false, reason);
    }
}
