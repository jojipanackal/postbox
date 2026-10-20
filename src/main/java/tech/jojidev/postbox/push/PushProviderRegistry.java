package tech.jojidev.postbox.push;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PushProviderRegistry {
    private final Map<String, PushProvider> providers;

    public PushProviderRegistry(List<PushProvider> implementations) {
        for (PushProvider provider : implementations) {
            if (provider.id() == null || !provider.id().matches("[A-Z][A-Z0-9_]{0,31}")) {
                throw new IllegalArgumentException("Invalid push provider identifier");
            }
        }
        providers = implementations.stream().collect(Collectors.toUnmodifiableMap(
                PushProvider::id, Function.identity()));
    }

    public PushProvider get(String id) {
        if (id == null) throw new IllegalArgumentException("Unsupported push provider");
        PushProvider provider = providers.get(id);
        if (provider == null) throw new IllegalArgumentException("Unsupported push provider");
        return provider;
    }
}
