package com.example.bfh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
@EnableConfigurationProperties(App.AppProps.class)
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    CommandLineRunner run(AppProps props, RestTemplate http) {
        return args -> {
            System.out.printf("[INIT] name=%s, regNo=%s, email=%s%n",
                    props.getName(), props.getRegNo(), props.getEmail());

            // 1) Generate webhook + token
            var generateUrl = props.getEndpoints().get("generate");
            var reqBody = Map.of(
                    "name", props.getName(),
                    "regNo", props.getRegNo(),
                    "email", props.getEmail()
            );

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(reqBody, headers);

            GenerateResponse gen;
            try {
                var resp = http.postForEntity(generateUrl, entity, GenerateResponse.class);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                    throw new IllegalStateException("Generate webhook failed: " + resp.getStatusCode());
                }
                gen = resp.getBody();
            } catch (HttpClientErrorException e) {
                throw new IllegalStateException("Generate webhook request failed: "
                        + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            }

            System.out.println("[OK] webhook=" + gen.webhook + ", accessToken=*** (hidden)");

            // 2) Decide which SQL to submit (based on last two digits parity)
            String finalQuery = chooseFinalQuery(props.getRegNo());
            System.out.println("[SQL] Selected finalQuery:\n" + finalQuery);

            // 3) Submit SQL to the webhook with JWT in Authorization header
            String submitUrl = StringUtils.hasText(gen.webhook)
                    ? gen.webhook
                    : props.getEndpoints().get("fallbackSubmit");

            var submitHeaders = new HttpHeaders();
            submitHeaders.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(gen.accessToken)) {
                submitHeaders.set("Authorization", gen.accessToken);
            }
            var payload = Map.of("finalQuery", finalQuery);

            try {
                var submitResp = http.postForEntity(submitUrl,
                        new HttpEntity<>(payload, submitHeaders), String.class);
                System.out.println("[DONE] Submission status=" + submitResp.getStatusCode());
                System.out.println("[RESP] Body=" + submitResp.getBody());
            } catch (HttpClientErrorException e) {
                System.err.println("[ERROR] Submission failed: "
                        + e.getStatusCode() + " " + e.getResponseBodyAsString());
                throw e;
            }
        };
    }

    private static String chooseFinalQuery(String regNo) {
        int lastTwo = extractLastTwoDigits(regNo);
        boolean isEven = (lastTwo % 2 == 0);
        if (isEven) {
            // Question 2 SQL (younger employees per department)
            return (
                "SELECT\n" +
                "    e1.EMP_ID,\n" +
                "    e1.FIRST_NAME,\n" +
                "    e1.LAST_NAME,\n" +
                "    d.DEPARTMENT_NAME,\n" +
                "    COUNT(e2.EMP_ID) AS YOUNGER_EMPLOYEES_COUNT\n" +
                "FROM EMPLOYEE e1\n" +
                "JOIN DEPARTMENT d\n" +
                "    ON e1.DEPARTMENT = d.DEPARTMENT_ID\n" +
                "LEFT JOIN EMPLOYEE e2\n" +
                "    ON e1.DEPARTMENT = e2.DEPARTMENT\n" +
                "   AND e2.DOB > e1.DOB\n" +
                "GROUP BY e1.EMP_ID, e1.FIRST_NAME, e1.LAST_NAME, d.DEPARTMENT_NAME\n" +
                "ORDER BY e1.EMP_ID DESC;\n"
            );
        } else {
            // TODO: Replace with your final SQL for Question 1
            return "SELECT 1;";
        }
    }

    private static int extractLastTwoDigits(String regNo) {
        if (regNo == null) return 0;
        Pattern p = Pattern.compile("(\\d{2})\\D*$");
        Matcher m = p.matcher(regNo);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        // Fallback: pick last two digits anywhere
        Pattern any = Pattern.compile("(\\d{2})");
        Matcher m2 = any.matcher(regNo);
        if (m2.find()) return Integer.parseInt(m2.group(1));
        return 0; // default to even path if none found
    }

    @ConfigurationProperties(prefix = "app")
    public static class AppProps {
        private String name;
        private String regNo;
        private String email;
        private Map<String, String> endpoints;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRegNo() { return regNo; }
        public void setRegNo(String regNo) { this.regNo = regNo; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public Map<String, String> getEndpoints() { return endpoints; }
        public void setEndpoints(Map<String, String> endpoints) { this.endpoints = endpoints; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerateResponse {
        @JsonProperty("webhook")
        public String webhook;
        @JsonProperty("accessToken")
        public String accessToken;
    }
}
