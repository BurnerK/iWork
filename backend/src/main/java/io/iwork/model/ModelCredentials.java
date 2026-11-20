package io.iwork.model;

import io.iwork.identity.ApiFailure;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class ModelCredentials {
    private static final String KEY_PREFIX = "IWORK_MODEL_KEY_";
    private final Environment environment;

    ModelCredentials(Environment environment) {
        this.environment = environment;
    }

    String resolve(String url, String ref, int status) {
        httpsUrl(url, status);
        if (!ref.matches(KEY_PREFIX + "[A-Z0-9_]{1,80}"))
            throw new ApiFailure(status, "invalid_credential_reference");
        var approved =
                environment.getProperty("IWORK_MODEL_URL_" + ref.substring(KEY_PREFIX.length()));
        if (!url.equals(approved)) throw new ApiFailure(status, "credential_not_configured");
        var key = environment.getProperty(ref);
        if (key == null || key.isBlank()) throw new ApiFailure(status, "credential_not_configured");
        return key;
    }

    static void requireOrigin(URI approved, String url) {
        var request = httpsUrl(url, 503);
        if (!approved.getHost().equalsIgnoreCase(request.getHost())
                || approved.getPort() != request.getPort())
            throw new ApiFailure(503, "credential_not_configured");
    }

    private static URI httpsUrl(String url, int status) {
        try {
            var uri = URI.create(url);
            if ("https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null) return uri;
        } catch (IllegalArgumentException invalid) {
            throw new ApiFailure(status, "invalid_model_url");
        }
        throw new ApiFailure(status, "invalid_model_url");
    }
}
