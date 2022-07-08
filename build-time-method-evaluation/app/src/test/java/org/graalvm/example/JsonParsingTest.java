package org.graalvm.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonParsingTest {

    private static final List<SimplifiedReflectConfig> expectedConfig = new ArrayList<>();

    static {
        expectedConfig.add(new SimplifiedReflectConfig("TypeX", true, false));
        expectedConfig.add(new SimplifiedReflectConfig("TypeY", false, true));
    }

    @Test
    public void testParsingFromConstant() throws JsonProcessingException {
        String json = "[" +
                "{ \"name\" : \"TypeX\", \"allDeclaredMethods\" : true, \"allDeclaredConstructors\" : false }," +
                "{ \"name\" : \"TypeY\", \"allDeclaredMethods\" : false, \"allDeclaredConstructors\" : true }" +
                "]";
        List<SimplifiedReflectConfig> actualConfig = JsonParsing.parseReflectJson(json);
        assertEquals(expectedConfig, actualConfig);
    }

    @Test
    public void testParsingFromResource() throws IOException {
        InputStream configResource = JsonParsingTest.class.getResourceAsStream("/simple-reflect-config.json");
        /* This call will not be constant folded due to `configResource` not being viewed as a constant */
        List<SimplifiedReflectConfig> actualConfig = JsonParsing.parseReflectJson(configResource);
        assertEquals(expectedConfig, actualConfig);
    }

}
