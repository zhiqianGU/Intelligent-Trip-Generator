package thesis.project.gu.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
public class SingleFlightService {
    private final ConcurrentMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

    public <T> T execute(String namespace, String key, Supplier<T> loader) {
        String flightKey = namespace + ":" + normalizeKey(key);
        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<?> existing = inFlight.putIfAbsent(flightKey, created);
        if (existing == null) {
            try {
                T result = loader.get();
                created.complete(result);
                return result;
            } catch (Throwable ex) {
                created.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlight.remove(flightKey, created);
            }
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<T> typed = (CompletableFuture<T>) existing;
            return typed.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase();
    }
}
