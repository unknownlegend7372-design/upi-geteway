package com.example.demo;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class LicenseService {

    // अगर Render पर LICENSE_URL सेट होगा तो वह उठाएगा, नहीं तो डिफ़ॉल्ट लिंक लेगा
    private static final String DEFAULT_URL = "https://raw.githubusercontent.com/unknownlegend7372-design/master-license/main/status.txt";
    
    private final String masterControlUrl;
    private boolean isAllowed = true;
    private long lastCheckedTime = 0;
    private static final long CHECK_INTERVAL_MS = 60 * 1000; // 1 मिनट कैशिंग

    public LicenseService() {
        String envUrl = System.getenv("LICENSE_URL");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            this.masterControlUrl = envUrl.trim();
        } else {
            this.masterControlUrl = DEFAULT_URL;
        }
        System.out.println(">>> ACTIVE LICENSE CONTROL URL: " + this.masterControlUrl);
    }

    public boolean isServiceActive() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCheckedTime > CHECK_INTERVAL_MS) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.masterControlUrl))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String remoteStatus = response.body().trim();

                this.isAllowed = "ACTIVE".equalsIgnoreCase(remoteStatus);
                this.lastCheckedTime = currentTime;

                System.out.println(">>> REMOTE LICENSE STATUS: " + remoteStatus);
            } catch (Exception e) {
                System.out.println(">>> License check failed, keeping last known status: " + this.isAllowed);
            }
        }

        return this.isAllowed;
    }
}