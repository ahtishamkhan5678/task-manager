package api;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton map from idempotency key -> (request fingerprint, stored response).
 * Methods are synchronized; TaskApi also locks the store around check-run-save,
 * so two threads retrying with the same key can never both create a task.
 */
public final class IdempotencyStore {

    private static IdempotencyStore instance;

    private final Map<String, Record> records = new HashMap<>();

    private IdempotencyStore() {
    }

    public static synchronized IdempotencyStore getInstance() {
        if (instance == null) {
            instance = new IdempotencyStore();
        }
        return instance;
    }

    public synchronized Record find(String key) {
        return records.get(key);
    }

    public synchronized void save(String key, String fingerprint, ApiResponse response) {
        records.put(key, new Record(fingerprint, response));
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized void clear() {
        records.clear();
    }

    /** What we remember for one key. */
    public static final class Record {
        private final String fingerprint;
        private final ApiResponse response;

        Record(String fingerprint, ApiResponse response) {
            this.fingerprint = fingerprint;
            this.response = response;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public ApiResponse getResponse() {
            return response;
        }
    }
}
