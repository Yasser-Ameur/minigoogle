package com.minigoogle.crawler.normalization;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * URL normalization with tracking parameter removal and canonicalization.
 * Strips UTM and Facebook tracking parameters, removes duplicate slashes,
 * normalizes ports, and lowercases scheme and host for consistent URL identity.
 */
public class StandardUrlNormalizer implements UrlNormalizer {

    private static final List<String> TRACKING_PARAMS = List.of(
        "utm_source", "utm_campaign", "utm_medium", "fbclid"
    );

    @Override
    public Optional<URI> normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        
        try {
            URI uri = new URI(rawUrl).normalize();
            return normalize(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<URI> normalize(URI baseUri, String rawUrl) {
        if (baseUri == null || rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        
        try {
            URI resolved = baseUri.resolve(rawUrl).normalize();
            return normalize(resolved);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<URI> normalize(URI uri) {
        if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        
        try {
            String scheme = uri.getScheme().toLowerCase();
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : null;
            if (host == null) return Optional.empty();

            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = uri.getPath();
            if (path != null) {
                path = path.replaceAll("//+", "/"); // Remove duplicate slashes
                if (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1); // Remove trailing slash
                }
            }
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            String query = uri.getQuery();
            if (query != null) {
                query = Arrays.stream(query.split("&"))
                    .filter(param -> !isTrackingParam(param))
                    .sorted()
                    .collect(Collectors.joining("&"));
                if (query.isEmpty()) query = null;
            }

            URI normalized = new URI(scheme, uri.getUserInfo(), host, port, path, query, null); // fragment is null
            return Optional.of(normalized);
            
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private boolean isTrackingParam(String param) {
        String key = param.split("=")[0].toLowerCase();
        return TRACKING_PARAMS.contains(key);
    }
}
