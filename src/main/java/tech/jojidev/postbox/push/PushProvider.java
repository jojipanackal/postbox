package tech.jojidev.postbox.push;

import java.util.Map;

/** Provider-specific validation and delivery; shared workers never interpret provider payloads. */
public interface PushProvider {
    record Message(String clientId, String recipient, String title, String body, Map<String, Object> data) {}
    record Submission(String reference, boolean receiptExpected) {}
    enum Receipt { PENDING, CONFIRMED }

    class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public Failure(String code, boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    /** Stable identifier persisted with every request (e.g. EXPO). Never rename without migrating records. */
    String id();
    void validate(Message message);
    Submission send(Message message);

    /** Called only when Submission.receiptExpected is true. No receipt is not success. */
    default Receipt receipt(String clientId, String reference) {
        throw new UnsupportedOperationException("This provider does not offer receipts");
    }
}
