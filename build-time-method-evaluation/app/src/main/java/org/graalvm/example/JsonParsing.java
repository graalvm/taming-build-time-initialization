package org.graalvm.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.example.nativeimage.meta.Constant;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public class JsonParsing {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Constant
    public static List<SimplifiedReflectConfig> parseReflectJson(String json) throws JsonProcessingException {
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            System.err.println("This must be computed only at build time!");
            System.exit(-1);
        }
        return mapper.readValue(json, new TypeReference<>() {
        });
    }

    public static List<SimplifiedReflectConfig> parseReflectJson(InputStream stream) throws IOException {
        return mapper.readValue(stream, new TypeReference<>() {
        });
    }
}

class SimplifiedReflectConfig {

    private String name;
    private boolean allDeclaredMethods;
    private boolean allDeclaredConstructors;

    public SimplifiedReflectConfig() {

    }

    public SimplifiedReflectConfig(String name, boolean allDeclaredMethods, boolean allDeclaredConstructors) {
        this.name = name;
        this.allDeclaredMethods = allDeclaredMethods;
        this.allDeclaredConstructors = allDeclaredConstructors;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAllDeclaredMethods() {
        return allDeclaredMethods;
    }

    public void setAllDeclaredMethods(boolean allDeclaredMethods) {
        this.allDeclaredMethods = allDeclaredMethods;
    }

    public boolean isAllDeclaredConstructors() {
        return allDeclaredConstructors;
    }

    public void setAllDeclaredConstructors(boolean allDeclaredConstructors) {
        this.allDeclaredConstructors = allDeclaredConstructors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SimplifiedReflectConfig that = (SimplifiedReflectConfig) o;
        return allDeclaredMethods == that.allDeclaredMethods && allDeclaredConstructors == that.allDeclaredConstructors && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, allDeclaredMethods, allDeclaredConstructors);
    }
}
