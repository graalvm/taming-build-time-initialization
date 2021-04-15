package org.graalvm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ConfigExample
{
    static List<Map<String, String>> employeeData;

    static {
        try {
            System.out.println("Parsing employee file.");
            long start = System.currentTimeMillis();
            ObjectMapper mapper = new ObjectMapper();
            employeeData = mapper.readValue(ConfigExample.class.getResource("/account-list.json"), new TypeReference<>() {});
            System.out.println("Employee file parsed in: " + (System.currentTimeMillis() - start) + " ms.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void storeEmployeeData(String filePath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(new File(filePath), employeeData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("Hello, world! Our fake employee list is " + employeeData.size() + " long!");
        if (employeeData.size() > 50) {
            System.out.println("Best fake employee ID: " + employeeData.get(50).get("_id") + ".");
        } else {
            System.out.println("We don't have a best fake employee!");
        }
        storeEmployeeData("data.json");
    }
}
