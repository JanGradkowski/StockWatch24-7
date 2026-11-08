package org.example.stockwatch247.assistant;

import java.util.List;

/**
 * The language model behind the assistant. One call takes the system instruction and the conversation and returns
 * the model's answer as text (JSON when a schema is given). Moving to another provider means one new implementation.
 */
public interface AssistantModel {
    /** True when a key and model are configured. */
    boolean configured();

    Reply generate(Request request);

    /** {@code role} is "user" or "model". */
    record Turn(String role, String text) { }

    /** {@code schema} is a JSON Schema the answer must follow, or null for plain text. */
    record Request(String system, List<Turn> turns, Object schema) {
        public Request {
            turns = List.copyOf(turns);
        }
    }

    /** Token counts as the provider reports them; {@code cached} is the part of the prompt read from its cache. */
    record Usage(int prompt, int cached, int output, int thoughts) {
        static final Usage NONE = new Usage(0, 0, 0, 0);
    }

    /** {@code model} is the model that answered, which may be a fallback. */
    record Reply(String text, Usage usage, String model) { }

    /**
     * The provider could not answer: down, timed out or refused the request. {@code busy}: overloaded or out of quota,
     * worth trying again later; {@code daily}: its daily quota is used up, so not before the provider's daily reset.
     */
    class UnavailableException extends RuntimeException {
        private final boolean busy;
        private final boolean daily;

        public UnavailableException(String reason, boolean busy, Throwable cause) {
            this(reason, busy, false, cause);
        }

        public UnavailableException(String reason, boolean busy, boolean daily, Throwable cause) {
            super(reason, cause);
            this.busy = busy;
            this.daily = daily;
        }

        public boolean busy() {
            return busy;
        }

        public boolean daily() {
            return daily;
        }
    }
}
