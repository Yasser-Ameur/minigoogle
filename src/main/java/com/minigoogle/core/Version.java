package com.minigoogle.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the running application version.
 *
 * Resolution order:
 *   1. The jar manifest's Implementation-Version attribute.
 *   2. The "version" key in the classpath resource /minigoogle-version.properties.
 *   3. "dev" when neither is available.
 */
public final class Version {

    private static final String RESOURCE_PATH = "/minigoogle-version.properties";

    private Version() {
    }

    public static String current() {
        String manifestVersion = Version.class.getPackage().getImplementationVersion();
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion;
        }

        String propertiesVersion = fromResource();
        if (propertiesVersion != null && !propertiesVersion.isBlank()) {
            return propertiesVersion;
        }

        return "dev";
    }

    private static String fromResource() {
        try (InputStream in = Version.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version");
        } catch (IOException e) {
            return null;
        }
    }
}
