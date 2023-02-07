package org.graalvm.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public class ResourcesExample {
    static class ResourceExampleMetadata {
        // file://, jar://, jrt://
        /*
         * GraalVM for JDK 20 only adds a resource.
         * GraalVM for JDK 21. Translation:
         *  jrt://<module.name>/path -> jrt://<module.name>/path
         *  file://<classpath>/path -> jrt://image/classpath/path
         *  jar://<classpath>/jar!path -> jrt://image/classpath/jar!path
         *
         * Implement custom jrt for native image (and JDK?). Object replacer until the JDK jrt doesn't support the `image/classpath` exception.
         */
        static final URL url = ResourcesExample.class.getResource("/url-image.txt");
        static final URI uri;

        static {
            URI tmp;
            try {
                tmp = Objects.requireNonNull(ResourcesExample.class.getClassLoader().getResource("uri-image.txt")).toURI();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            uri = tmp;
        }
    }

    public static String readURL(URL url) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(url.openStream()));
        StringBuilder res = new StringBuilder();
        String inputLine;
        while ((inputLine = in.readLine()) != null)
            res.append(inputLine);
        in.close();
        return res.toString();
    }

    static String resourcesSHA() {

        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            String res =
                    readURL(Objects.requireNonNull(ResourceExampleMetadata.url)) +
                            readURL(ResourceExampleMetadata.uri.toURL()) +
                            ResourcesExample.class.getResource("/url-code.txt") +
                            ResourcesExample.class.getClassLoader().getResource("/url-cl-code.txt");
            byte[] encodedhash = digest.digest(
                    res.getBytes(StandardCharsets.UTF_8));
            return new String(encodedhash);
        } catch (Exception e) {
            throw new RuntimeException("Resources failed: ", e);
        }
    }
}
